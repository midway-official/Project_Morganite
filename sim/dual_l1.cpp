#include "VDualDataL1.h"
#include <array>
#include <cassert>
#include <iostream>
struct Port {
    CData &req,&ready,&mask,&size,&valid,&accept,&fault,&fill,&fillReady,&fillMask,&ret,&retReady,&err;
    QData &addr,&line;
    IData &data,&word;
};
#define PORT(i) {d.io_core_##i##_req_valid,d.io_core_##i##_req_ready,d.io_core_##i##_req_bits_mask, \
 d.io_core_##i##_req_bits_size,d.io_core_##i##_rsp_valid,d.io_core_##i##_rsp_ready,d.io_core_##i##_rsp_bits_fault, \
 d.io_mem_##i##_req_valid,d.io_mem_##i##_req_ready,d.io_mem_##i##_req_bits_mask,d.io_mem_##i##_rsp_valid, \
 d.io_mem_##i##_rsp_ready,d.io_mem_##i##_rsp_bits_fault,d.io_core_##i##_req_bits_addr_3, \
 d.io_mem_##i##_req_bits_addr_0,d.io_mem_##i##_rsp_bits_data_2,d.io_core_##i##_rsp_bits_data_3}
struct Bench {
    VDualDataL1 d;
    Port p[2]={PORT(0),PORT(1)};
    unsigned count=0;
    bool active[2]={}; unsigned address[2]={};
    Bench() {
        d.clock=0;d.reset=1;d.io_roBase=0x1000;d.io_roEnd=0x4000;
        for(auto &x:p){x.mask=8;x.size=2;x.fillReady=1;}
        d.eval();tick();tick();d.reset=0;
    }
    void tick() {
        d.eval();
        for(unsigned i=0;i<2;i++) if(p[i].fill && p[i].fillReady) {
            assert(!active[i]); active[i]=true;address[i]=p[i].line;
            assert(p[i].fillMask==255);++count;
        }
        d.clock=1; d.eval();d.clock=0;d.eval();
    }
    void wait(unsigned n=9){while(n--)tick();}
    void pair(unsigned a,unsigned b) {
        for(unsigned i=0;i<2;i++){p[i].addr=(i?b:a)+8;p[i].req=1;}
        d.eval(); assert(p[0].ready && p[1].ready);tick();p[0].req=p[1].req=0;
    }
    void finish(unsigned i,unsigned value,unsigned fault=0) {
        assert(active[i]); p[i].data=value;p[i].err=fault;p[i].ret=1;
        d.eval();unsigned n=0;while(!p[i].retReady){tick();assert(++n<10);}
        tick();p[i].ret=0;active[i]=false;
    }
    void check(unsigned i,unsigned value,unsigned fault=0) {
        wait(2);assert(p[i].valid && p[i].fault==fault);
        if(!fault)assert(p[i].word==value);
        p[i].accept=1;tick();p[i].accept=0;
    }
    void invalidate(){d.io_invalidate=1;tick();d.io_invalidate=0;}
};
int main(){
    Bench b;
    b.pair(0x1000,0x1020);b.wait();assert(b.active[0] && b.active[1]);
    b.finish(1,22);b.finish(0,11);b.check(0,11);b.check(1,22);
    b.pair(0x1020,0x1000);b.wait();b.check(0,22);b.check(1,11);
    assert(b.d.io_hits==2 && b.count==2);
    b.invalidate();b.pair(0x1200,0x1200);b.wait();
    unsigned leader=b.active[0]?0:1;assert(b.count==3 && !b.active[1-leader]);
    b.finish(leader,55);b.check(0,55);b.check(1,55);
    b.pair(0x1400,0x1c00);b.wait();leader=b.active[0]?0:1;
    assert(b.count==4 && !b.active[1-leader]);
    b.finish(leader,100+leader);b.wait();assert(b.active[1-leader]);b.finish(1-leader,101-leader);
    b.check(0,100);b.check(1,101);
    b.pair(0x1600,0x1600);b.wait();leader=b.active[0]?0:1;
    b.d.io_cancel=1;b.invalidate();b.finish(leader,77);b.check(0,0,7);b.check(1,0,7);b.d.io_cancel=0;
    auto n=b.count;b.pair(0x1600,0x1600);b.wait();leader=b.active[0]?0:1;
    assert(b.count==n+1);b.finish(leader,88);b.check(0,88);b.check(1,88);
    b.invalidate();b.pair(0x1600,0x1600);b.wait();leader=b.active[0]?0:1;
    b.finish(leader,0,6);b.check(0,0,6);b.check(1,0,6);
    std::cout<<"PASS dual MSHR independent fills, hits, merge, conflict, fault fanout, stale fill and reuse\n";
}
