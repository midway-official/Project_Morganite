#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>
#include "morganite.h"

enum { DESC = 0, CODE = 4096, ARG = 8192, X = 65536, Y = 131072 };
static int fd;
static struct mg_info device;
static unsigned features;
static unsigned ro_offset, ro_length;

static unsigned all_sms(void) {
    unsigned count = device.caps & 255;
    return count == 32 ? UINT32_MAX : (1u << count) - 1;
}

static void die(const char *why) { perror(why); exit(1); }
static void require(int ok, const char *why) {
    if (!ok) { fprintf(stderr, "%s\n", why); exit(1); }
}
static void upload(unsigned offset, const void *ptr, size_t size) {
    if (pwrite(fd, ptr, size, offset) != (ssize_t)size) die("GPU upload");
}
static void download(unsigned offset, void *ptr, size_t size) {
    if (pread(fd, ptr, size, offset) != (ssize_t)size) die("GPU download");
}
static unsigned number(const char *s) {
    char *end;
    errno = 0;
    unsigned long value = strtoul(s, &end, 0);
    require(s[0] && s[0] != '-' && !*end && !errno && value <= UINT32_MAX, "invalid unsigned argument");
    return value;
}
static size_t read_file(const char *path, void **data) {
    FILE *f = fopen(path, "rb");
    if (!f) die(path);
    if (fseek(f, 0, SEEK_END)) die("seek");
    long n = ftell(f);
    require(n >= 0 && (unsigned long)n <= device.bytes, "file exceeds arena");
    rewind(f); *data = malloc(n ? n : 1);
    if (!*data || fread(*data, 1, n, f) != (size_t)n) die("read file");
    fclose(f);
    return n;
}
static void write_file(const char *path, const void *ptr, size_t size) {
    FILE *f = fopen(path, "wb");
    if (!f || fwrite(ptr, 1, size, f) != size || fclose(f)) die(path);
}
static unsigned program(const char *name) {
    char path[160];
    require(snprintf(path, sizeof(path), "/gpu/%s.bin", name) < (int)sizeof(path), "kernel name too long");
    void *data;
    size_t n = read_file(path, &data);
    require(n > 0 && n <= 4096 && !(n & 3), "bad program length");
    upload(CODE, data, n); free(data);
    return n / 4;
}
static uint64_t ns(void) {
    struct timespec t;
    if (clock_gettime(CLOCK_MONOTONIC, &t)) die("clock_gettime");
    return (uint64_t)t.tv_sec * 1000000000 + t.tv_nsec;
}
static struct mg_status run(unsigned desc, unsigned mask, const char *name) {
    struct mg_submit job = {desc, ++device.last_sequence, mask};
    struct mg_status s;
    if (!job.sequence) job.sequence = ++device.last_sequence;
    uint64_t begin = ns(), end;
    volatile unsigned cpu_private[64];
    unsigned samples=0;
    if (ro_length) {
        struct mg_submit_ro ro = {job, ro_offset, ro_length};
        if (ioctl(fd, MG_SUBMIT_RO, &ro)) die("MG_SUBMIT_RO");
    } else if (ioctl(fd, MG_SUBMIT, &job)) die("MG_SUBMIT");
    for (;;) {
        if (ioctl(fd, MG_STATUS, &s)) die("MG_STATUS");
        if (!(s.state & 1)) break;
        unsigned value=job.sequence^samples;
        cpu_private[samples & 63]=value;
        require(cpu_private[samples & 63]==value,"concurrent CPU private data");
        ++samples;
        if (ns() - begin > 180000000000ULL) {
            ioctl(fd, MG_CANCEL); die("GPU job timeout");
        }
    }
    end = ns();
    printf("{\"kernel\":\"%s\",\"seq\":%u,\"mask\":%u,\"cycles\":%u,\"host_ns\":%" PRIu64
           ",\"fault\":%u,\"fault_addr\":%u,\"read_bytes\":%u,\"write_bytes\":%u,"
           "\"read_bursts\":%u,\"write_bursts\":%u,\"cpu_busy_samples\":%u,\"sm\":[",
           name, job.sequence, mask, s.cycles, end-begin, s.fault, s.address,
           s.read_bytes, s.write_bytes, s.read_bursts, s.write_bursts, samples);
    for (unsigned i = 0; i < (device.caps & 255); ++i) {
        struct mg_sm_counters extra = {.index = i};
        if (i >= 4 && ioctl(fd, MG_SM_COUNTERS, &extra)) die("MG_SM_COUNTERS");
        const __u32 *v = i < 4 ? s.sm[i] : extra.values;
        printf("%s[%u,%u,%u,%u,%u]", i?",":"", v[0],v[1],v[2],v[3],v[4]);
    }
    puts("]}"); fflush(stdout);
    require(!s.fault && s.completed == job.sequence, "GPU sequence or fault");
    return s;
}
static struct mg_status launch(const char *name, unsigned groups, unsigned threads, unsigned mask) {
    unsigned desc[8] = {0x3144474d, 0x10000, device.device_base+CODE, program(name),
                        device.device_base+ARG, groups, threads, 0};
    upload(DESC, desc, sizeof(desc));
    return run(DESC,mask,name);
}
static unsigned as_word(float v) { unsigned w; memcpy(&w,&v,4); return w; }

static void loopback(void) {
    unsigned input[64], output[64];
    unsigned params[]={device.device_base+X,device.device_base+Y};
    for (unsigned i=0;i<64;++i) input[i]=0xa5a50000u+i;
    memset(output,0,sizeof(output)); upload(Y,output,sizeof(output));
    upload(X,input,sizeof(input)); upload(ARG,params,sizeof(params));
    launch("copy",2,32,all_sms());
    download(Y,output,sizeof(output));
    require(!memcmp(input,output,sizeof(input)),"DMA loopback mismatch");
    puts("MORGANITE_LOOPBACK_PASS=1");
}

static void selftest(unsigned n) {
    const unsigned mask = all_sms();
    static unsigned in[4096], out[4096];
    unsigned params[3], groups = (device.caps & 255) * 4;
    if (groups < 8) groups = 8;
    for (unsigned job=0; job<n; ++job) {
        unsigned words = groups * 32;
        if (job & 1) {
            params[0] = as_word(1.5f); params[1] = device.device_base+X; params[2] = device.device_base+Y;
            for (unsigned i=0; i<words; ++i) { in[i] = as_word((float)(i+job)); out[i] = as_word(0.25f); }
            upload(ARG,params,sizeof(params)); upload(X,in,words*4); upload(Y,out,words*4);
            launch("axpy",groups,32,mask); download(Y,out,words*4);
            for (unsigned i=0; i<words; ++i) require(out[i] == as_word(1.5f*(i+job)+0.25f),"AXPY mismatch");
        } else {
            memset(out,0xbf,words*4); upload(ARG,out,words*4);
            launch("integer",groups,32,mask); download(ARG,out,words*4);
            for (unsigned i=0; i<words; ++i) require(out[i] == i*i+13,"integer mismatch");
        }
    }
    // CPU -> SM0 -> distinct SM1 -> CPU without host access to the intermediate buffer.
    for (unsigned i=0;i<256;++i) in[i] = 0x76b71983u*(i+1);
    const size_t copy_bytes = 256 * sizeof(in[0]);
    upload(X,in,copy_bytes);
    params[0]=device.device_base+X; params[1]=device.device_base+Y;
    upload(ARG,params,8); launch("copy",8,32,1);
    params[0]=device.device_base+Y; params[1]=device.device_base+X+4096;
    upload(ARG,params,8); launch("copy",8,32,(mask & 2) ? 2 : 1);
    download(X+4096,out,copy_bytes); require(!memcmp(in,out,copy_bytes),"cross-SM visibility mismatch");
    printf("MORGANITE_SELFTEST_PASS=%u\n", n);
}

static void performance(void) {
    const unsigned mask=all_sms();
    unsigned params[] = {device.device_base+X,device.device_base+Y};
    static unsigned data[4096], output[4096];
    unsigned groups = (device.caps & 255) * 4;
    for (unsigned i=0; i<groups*32; ++i) data[i]=i*0x9e3779b9u;
    upload(ARG,params,sizeof(params)); upload(X,data,groups*32*4);
    struct mg_status bw = launch("copy",groups,32,mask);
    download(Y,output,groups*32*4); require(!memcmp(data,output,groups*32*4),"copy bandwidth mismatch");
    struct mg_status one = launch("fma_bench",groups,32,1);
    download(ARG,data,groups*32*4);
    struct mg_status all = launch("fma_bench",groups,32,mask);
    download(ARG,output,groups*32*4);
    for (unsigned i=0; i<groups*32; ++i)
        require(data[i]==as_word(16384.f) && output[i]==data[i],"FMA benchmark mismatch");
    unsigned fmas=0;
    for (unsigned i=0;i<(device.caps&255);++i) {
        struct mg_sm_counters extra = {.index = i};
        if (i >= 4 && ioctl(fd, MG_SM_COUNTERS, &extra)) die("MG_SM_COUNTERS");
        fmas += i < 4 ? all.sm[i][3] : extra.values[3];
    }
    require(fmas==groups*32*16*1024 && fmas==one.sm[0][3],"FMA operation count");
    unsigned tensor_k = (features >> 8) & 7;
    if (tensor_k) {
        struct mg_status tensor1 = launch("tensor_bench",groups,32,1);
        unsigned one_macs = 0;
        if (ioctl(fd, MG_SM_MACS, &one_macs)) die("MG_SM_MACS");
        download(ARG,data,groups*32*4);
        struct mg_status tensorAll = launch("tensor_bench",groups,32,mask);
        download(ARG,output,groups*32*4);
        for (unsigned i=0; i<groups*32; ++i)
            require(data[i]==16384u*tensor_k && output[i]==data[i],"INT8 matrix benchmark mismatch");
        unsigned macs=0;
        for (unsigned i=0; i<(device.caps&255); ++i) {
            unsigned count = i;
            if (ioctl(fd,MG_SM_MACS,&count)) die("MG_SM_MACS");
            macs += count;
        }
        require(macs==groups*32*16*1024*tensor_k && macs==one_macs,"INT8 MAC count");
        printf("{\"tensor_macs\":%u,\"tensor_cycles_1sm\":%u,\"tensor_cycles_all\":%u}\n",
               macs,tensor1.cycles,tensorAll.cycles);
    }
    printf("{\"perf_fmas\":%u,\"cycles_1sm\":%u,\"cycles_all\":%u,\"clock_hz\":100000000,"
           "\"copy_payload_bytes\":%u,\"copy_cycles\":%u}\n",
           fmas,one.cycles,all.cycles,2*groups*32*4,bw.cycles);
}

static void render_scene(const char *scene_path, const char *directory) {
    enum { VERTICES=0x3000, CLIP=0x4000, INDICES=0x5000, SETUP=0x6000,
           COLOR=0x10000, DEPTH=0x60000, WINNER=0xb0000, PIXELS=320*240 };
    uint64_t begin = ns();
    void *data;
    size_t size = read_file(scene_path, &data);
    unsigned *scene = data;
    require(size >= 208 && scene[0]==0x3153474d, "unknown scene format");
    unsigned vertices=scene[1], triangles=scene[2], flags=scene[3];
    require(vertices && vertices<=32 && triangles && triangles<=32 && flags<=3 &&
            size == 208 + 32*vertices + 12*triangles, "invalid scene dimensions");
    unsigned *indices = scene+52+8*vertices;
    for (unsigned i=0; i<3*triangles; ++i) require(indices[i]<vertices, "bad vertex index");
    unsigned base = device.device_base, mask=all_sms();
    unsigned params[] = {base+VERTICES,base+CLIP,base+INDICES,base+SETUP,triangles,base+COLOR,base+DEPTH,base+WINNER};
    upload(ARG,params,sizeof(params));
    upload(ARG+64,scene+4,192);
    upload(VERTICES,scene+52,vertices*32);
    upload(INDICES,indices,triangles*12);
    free(data);
    const unsigned offsets[] = {COLOR, DEPTH, WINNER};
    const char *files[] = {"color.rgba", "depth.f32", "winner.u32"};
    size_t bytes = PIXELS*4;
    void *frame = malloc(bytes);
    if (!frame) die("frame allocation");
    memset(frame,0xa5,bytes);
    for (unsigned i=0; i<3; ++i) upload(offsets[i],frame,bytes);
    uint64_t cycles=launch(flags & 1 ? "vertex_shift":"vertex",1,vertices,mask).cycles;
    cycles+=launch("setup",1,triangles,mask).cycles;
    cycles+=launch(flags & 2 ? "pixel_swap":"pixel",PIXELS/32,32,mask).cycles;
    if (mkdir(directory,0755) && errno != EEXIST) die(directory);
    for (unsigned i=0; i<3; ++i) {
        char filename[256];
        require(snprintf(filename,sizeof(filename),"%s/%s",directory,files[i])<(int)sizeof(filename),"output path length");
        download(offsets[i],frame,bytes);
        write_file(filename,frame,bytes);
    }
    free(frame);
    printf("{\"triangles\":%u,\"pixels\":%u,\"render_cycles\":%" PRIu64 ",\"end_to_end_ns\":%" PRIu64 "}\n",
           triangles,PIXELS,cycles,ns()-begin);
}

int main(int argc, char **argv) {
    require(argc>=2, "usage: mgpu info | loopback | selftest N | bench | render scene_file directory | upload offset file | download offset bytes file | submit desc_offset sm_mask | kernel name groups threads mask");
    fd = open("/dev/morganite",O_RDWR);
    if (fd<0 && errno==ENOENT) {
        unsigned major_id, minor_id;
        FILE *sysdev=fopen("/sys/class/misc/morganite/dev","r");
        if (!sysdev) die("Morganite driver registration");
        require(fscanf(sysdev,"%u:%u",&major_id,&minor_id)==2,"invalid misc device number");
        fclose(sysdev);
        if (mknod("/dev/morganite",S_IFCHR|0600,makedev(major_id,minor_id)) && errno!=EEXIST)
            die("create Morganite device node");
        fd=open("/dev/morganite",O_RDWR);
    }
    if (fd<0 || ioctl(fd,MG_INFO,&device)) die("/dev/morganite");
    if (ioctl(fd,MG_FEATURES,&features) && errno != ENOTTY) die("MG_FEATURES");
    if (!strcmp(argv[1],"info") && argc==2)
        printf("{\"id\":%u,\"abi\":%u,\"caps\":%u,\"features\":%u,\"dma\":%u,\"bytes\":%u,\"completed_or_active\":%u}\n",
               device.id,device.abi,device.caps,features,device.device_base,device.bytes,device.last_sequence);
    else if (!strcmp(argv[1],"selftest") && argc==3) selftest(number(argv[2]));
    else if (!strcmp(argv[1],"loopback") && argc==2) loopback();
    else if (!strcmp(argv[1],"bench") && argc==2) performance();
    else if (!strcmp(argv[1],"render") && argc==4) render_scene(argv[2],argv[3]);
    else if (!strcmp(argv[1],"upload") && argc==4) {
        void *data; size_t n=read_file(argv[3],&data); upload(number(argv[2]),data,n); free(data);
    } else if (!strcmp(argv[1],"download") && argc==5) {
        unsigned n=number(argv[3]); require(n<=device.bytes,"download size");
        void *data=malloc(n ? n:1); if(!data)die("malloc");
        download(number(argv[2]),data,n); write_file(argv[4],data,n); free(data);
    } else if (!strcmp(argv[1],"submit") && (argc==4 || argc==6)) {
        if (argc == 6) { ro_offset=number(argv[4]); ro_length=number(argv[5]); }
        run(number(argv[2]),number(argv[3]),"submitted");
    }
    else if (!strcmp(argv[1],"kernel") && argc==6) launch(argv[2],number(argv[3]),number(argv[4]),number(argv[5]));
    else require(0,"unknown command or argument count");
    return close(fd);
}
