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
struct mg_status {
    __u32 state, completed, fault, address, failed_sequence, cycles;
    __u32 read_bursts, write_bursts, read_bytes, write_bytes;
    __u32 sm[4][5];
};
#define MG_INFO   _IOR('M', 0, struct mg_info)
#define MG_SUBMIT _IOW('M', 1, struct mg_submit)
#define MG_STATUS _IOR('M', 2, struct mg_status)
#define MG_CANCEL _IO('M', 3)
#endif
