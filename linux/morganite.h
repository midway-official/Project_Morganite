#ifndef MORGANITE_UAPI_H
#define MORGANITE_UAPI_H
#include <linux/ioctl.h>
#include <linux/types.h>

struct mg_info {
    __u32 id, abi, caps, device_base, bytes, last_sequence;
};
struct mg_submit {
    __u32 descriptor_offset, sequence, sm_mask;
};
struct mg_submit_ro {
    struct mg_submit job;
    __u32 offset, length;
};
struct mg_sm_counters {
    __u32 index, values[5], cache[4];
};
struct mg_status {
    __u32 state, completed, fault, address, failed_sequence, cycles;
    __u32 read_bursts, write_bursts, read_bytes, write_bytes;
    __u32 sm[4][5];
};
#define MG_INFO   _IOR('M', 0, struct mg_info)
#define MG_SUBMIT _IOW('M', 1, struct mg_submit)
#define MG_STATUS _IOR('M', 2, struct mg_status)
#define MG_CANCEL _IO('M', 3)
#define MG_SUBMIT_RO _IOW('M', 4, struct mg_submit_ro)
#define MG_SM_COUNTERS _IOWR('M', 5, struct mg_sm_counters)
#define MG_FEATURES _IOR('M', 6, __u32)
/* Supply an SM index and receive its retired INT8 scalar MAC count. */
#define MG_SM_MACS _IOWR('M', 7, __u32)
struct mg_graphics { __u32 rows, cols, units; };
#define MG_GRAPHICS _IOR('M', 8, struct mg_graphics)
#endif
