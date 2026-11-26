#include "VGpu.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <deque>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <vector>
constexpr unsigned base=0x1e000000;
using Data=std::array<uint32_t,8>;
struct Address {unsigned addr,id;};
struct Beat {Data data;unsigned mask;};
struct Hbm {
    CData &ar,&arready,&arid,&arsize,&arlen,&r,&rready,&rid,&resp,&last;
    IData &araddr;
    VlWide<8> &rdata;
    CData &aw,&awready,&awid,&awsize,&awlen,&w,&wready,&wlast,&b,&bready,&bid,&bresp;
    IData &awaddr,&strb;
    VlWide<8> &wdata;
    std::map<unsigned,Address> rd,wr;
    std::deque<Address> headers;
    std::deque<Beat> payloads;
    bool runRead=false,runWrite=false;
    unsigned transactions=0;
    bool idle() const {return rd.empty() && wr.empty() && headers.empty() && payloads.empty();}
};
#define PORT(p) {p##_ar_valid,p##_ar_ready,p##_ar_bits_id,p##_ar_bits_size,p##_ar_bits_len, \
 p##_r_valid,p##_r_ready,p##_r_bits_id,p##_r_bits_resp,p##_r_bits_last,p##_ar_bits_addr,p##_r_bits_data, \
 p##_aw_valid,p##_aw_ready,p##_aw_bits_id,p##_aw_bits_size,p##_aw_bits_len,p##_w_valid,p##_w_ready, \
 p##_w_bits_last,p##_b_valid,p##_b_ready,p##_b_bits_id,p##_b_bits_resp,p##_aw_bits_addr,p##_w_bits_strb,p##_w_bits_data}

struct System {
    VGpu d;
#ifdef SINGLE_HBM
    static constexpr unsigned portCount=1;
    Hbm h[1]={PORT(d.io_dma)};
#else
    static constexpr unsigned portCount=2;
    Hbm h[2]={PORT(d.io_dma),PORT(d.io_dma1)};
#endif
    std::vector<uint8_t> banks[2];
    unsigned cycles=0, overlap=0,dual=0, simultaneousData=0;
    unsigned cycleLimit=2000000;
    unsigned failRead=0,failWrite=0,readValue=0;
    std::array<unsigned,2> readBeats{}, writeBeats{};
    bool late=false, aw=false,w=false,b=false,ar=false,r=false;
    explicit System(const std::vector<uint8_t>& image) {
        for(auto &bank:banks)bank.resize(image.size());
        for(unsigned i=0;i<image.size();i++)host(base+i)=image[i];
        d.clock=0;d.reset=1;d.eval();tick();tick();d.reset=0;
    }
    uint8_t &host(unsigned addr){return banks[portCount==1 ? 0 : (addr>>12)&1].at(addr-base);}
    unsigned word(unsigned addr){
        unsigned v=0;for(unsigned i=0;i<4;i++)v|=uint32_t(host(addr+i))<<(8*i);return v;
    }
    void tick(){
        assert(++cycles<cycleLimit);
        for(unsigned i=0;i<portCount;i++){
            auto &p=h[i];
            p.arready=cycles%17>2;p.awready=cycles%19>6;p.wready=cycles%23<15;
            if(!p.r && !p.rd.empty() && cycles%31<8){
                const auto [id,a]=*p.rd.rbegin();
                p.r=1;p.rid=id;p.resp=(a.addr==failRead)?3:0;p.last=!(late && p.resp);
                for(unsigned j=0;j<8;j++){
                    unsigned v=0;for(unsigned k=0;k<4;k++)v|=uint32_t(banks[i].at(a.addr-base+j*4+k))<<(8*k);
                    p.rdata[j]=v;
                }
            }
            if(!p.b && !p.wr.empty() && cycles%37<13){
                const auto [id,a]=*p.wr.rbegin();p.b=1;p.bid=id;p.bresp=a.addr==failWrite?2:0;
            }
        }
        d.eval();
        aw=d.io_host_aw_valid && d.io_host_aw_ready;w=d.io_host_w_valid && d.io_host_w_ready;
        b=d.io_host_b_valid && d.io_host_b_ready;ar=d.io_host_ar_valid && d.io_host_ar_ready;
        r=d.io_host_r_valid && d.io_host_r_ready;if(r){assert(!d.io_host_r_bits_resp);readValue=d.io_host_r_bits_data;}
        if(b)assert(!d.io_host_b_bits);
        bool rdActive=false,wrActive=false;
        bool data[2]={};
        for(unsigned i=0;i<portCount;i++){
            auto &p=h[i];
            auto validate=[&](unsigned a,unsigned size,unsigned len) {
                assert((portCount==1 || ((a>>12)&1)==i) && !(a&31) && size==5 && !len && a>=base && a+32<=base+banks[i].size());
                ++p.transactions;
            };
            if(p.ar && p.arready){
                validate(p.araddr,p.arsize,p.arlen);
                assert(p.rd.emplace(p.arid,Address{p.araddr,p.arid}).second && p.rd.size()<=8);
            }
            if(p.aw && p.awready){
                validate(p.awaddr,p.awsize,p.awlen);
                p.headers.push_back({p.awaddr,p.awid});
            }
            if(p.w && p.wready){
                Beat beat;std::copy_n(&p.wdata[0],8,beat.data.begin());beat.mask=p.strb;
                assert(p.wlast);p.payloads.push_back(beat);data[i]=true;++writeBeats[i];
            }
            if(!p.headers.empty() && !p.payloads.empty()){
                auto a=p.headers.front();auto beat=p.payloads.front();p.headers.pop_front();p.payloads.pop_front();
                assert(p.wr.emplace(a.id,a).second && p.wr.size()<=4);
                if(a.addr!=failWrite)for(unsigned j=0;j<32;j++)if(beat.mask>>j&1)
                    banks[i].at(a.addr-base+j)=beat.data[j/4]>>(8*(j%4));
            }
            rdActive|=!p.rd.empty();wrActive|=!p.wr.empty();
            p.runRead=p.r && p.rready;p.runWrite=p.b && p.bready;
            if(p.runRead){data[i]=true;++readBeats[i];if(p.last)assert(p.rd.erase(p.rid));else late=false;}
            if(p.runWrite)assert(p.wr.erase(p.bid));
        }
        overlap+=rdActive && wrActive;if constexpr (portCount==2) dual+=!h[0].idle() && !h[1].idle();
        simultaneousData+=data[0] && data[1];
        d.clock=1;d.eval();d.clock=0;d.eval();
        for(auto &p:h){if(p.runRead)p.r=0;if(p.runWrite)p.b=0;}
    }
    void write(unsigned a,unsigned v){
        d.io_host_aw_bits_addr=a;d.io_host_w_bits_data=v;d.io_host_w_bits_strb=15;
        d.io_host_aw_valid=d.io_host_w_valid=d.io_host_b_ready=1;
        do{tick();if(aw)d.io_host_aw_valid=0;if(w)d.io_host_w_valid=0;}while(!b);
        d.io_host_b_ready=0;
    }
    unsigned read(unsigned a){
        d.io_host_ar_bits_addr=a;d.io_host_ar_valid=d.io_host_r_ready=1;
        do{tick();if(ar)d.io_host_ar_valid=0;}while(!r);
        d.io_host_r_ready=0;return readValue;
    }
    void done(){while(read(0x14)&1){}for(auto &p:h)assert(p.idle());}
};
int main(int argc,char**argv){
    assert(argc==7);std::ifstream f(argv[1],std::ios::binary);
    std::vector<uint8_t> image{std::istreambuf_iterator<char>(f),std::istreambuf_iterator<char>()};
    System s(image);
    unsigned sms=std::stoul(argv[3]),mask=std::stoul(argv[4]),fault=std::stoul(argv[5]);
    std::string mode=argv[6];
    if(mode=="render") s.cycleLimit=80000000;
    if(mode.rfind("units-",0)==0){
        auto split=mode.find('-',6);
        assert(s.read(0x5c)==std::stoul(mode.substr(6,split-6)) && s.read(0x60)==std::stoul(mode.substr(split+1)));
    }
    assert(s.read(0)==0x4d4f5247 && (s.read(8)&255)==sms);
    s.write(0x30,base);s.write(0x34,base+image.size());s.write(0x38,mask);s.write(0x0c,base);
    if(mode=="rerror" || mode=="rprotocol")s.failRead=s.word(base+8)&~31u;
    s.late=mode=="rprotocol";
    if(mode=="berror")s.failWrite=s.word(base+16)&~31u;
    if(mode=="immutable"){s.write(0x54,base+0x8000);s.write(0x58,base+0x9000);}
    s.write(0x10,1);if(mode=="cancel"){for(int i=0;i<100;i++)s.tick();s.write(0x2c,1);}s.done();
    unsigned status=s.read(0x1c),address=s.read(0x20),cycles=s.read(0x3c);
    assert(status==fault && s.read(0x18)==unsigned(!fault));
    std::cout<<"{\"cycles\":"<<cycles<<",\"address\":"<<address<<",\"fault\":"<<status;
    auto list=[&](const char* name,unsigned offset){
        std::cout<<",\""<<name<<"\":[";for(unsigned i=0;i<sms;i++)std::cout<<(i?",":"")<<s.read(offset+i*32);std::cout<<"]";
    };
    list("groups",0x104);list("fmas",0x10c);list("active_cycles",0x100);
    std::cout<<",\"concurrent_rw_cycles\":"<<s.overlap<<",\"dual_busy_cycles\":"<<s.dual
        <<",\"dual_data_cycles\":"<<s.simultaneousData<<",\"read_beats\":["<<s.readBeats[0]<<","<<s.readBeats[1]
        <<"],\"write_beats\":["<<s.writeBeats[0]<<","<<s.writeBeats[1]<<"]}\n";
    if(fault){
        s.failRead=s.failWrite=0;
        unsigned code=s.word(base+8);for(unsigned j=0;j<4;j++){s.host(code+j)=j?0:0x7b;s.host(base+12+j)=j?0:1;}
        s.write(0x10,2);s.done();assert(!s.read(0x1c) && s.read(0x18)==2);
    }
    std::ofstream out(argv[2],std::ios::binary);
    for(unsigned i=0;i<image.size();i++)out.put(s.host(base+i));
}
