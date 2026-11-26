#include "VScratchpad.h"
#include <cassert>
#include <iostream>
#define LANES(f) {&d.f##_0,&d.f##_1,&d.f##_2,&d.f##_3,&d.f##_4,&d.f##_5,&d.f##_6,&d.f##_7}
int main(){
    VScratchpad d;
    QData* addr[]=LANES(io_req_bits_addr);
    IData* data[]=LANES(io_req_bits_data);
    IData* reply[]=LANES(io_rsp_bits_data);
    auto tick=[&]{d.clock=1;d.eval();d.clock=0;d.eval();};
    auto request=[&](bool store,unsigned size,unsigned mask){
        d.io_req_bits_store=store;d.io_req_bits_size=size;d.io_req_bits_mask=mask;d.io_req_valid=1;
        d.eval();assert(d.io_req_ready);tick();d.io_req_valid=0;unsigned t=0;
        while(!d.io_rsp_valid){tick();assert(++t<20);}
        assert(!d.io_rsp_bits_fault);return t;
    };
    auto accept=[&]{d.io_rsp_ready=1;tick();d.io_rsp_ready=0;};
    d.clock=0;d.reset=1;d.eval();tick();d.reset=0;
    for(unsigned i=0;i<8;i++){*addr[i]=16*i;*data[i]=0x8bab03f2+i;}
    request(true,2,255);accept();
    for(unsigned i=0;i<8;i++)*addr[i]=i%4;
    d.io_req_bits_unsigned=1;unsigned same=request(false,0,255);
    for(unsigned i=0;i<8;i++)assert(*reply[i]==((0x8bab03f2u>>(8*(i%4)))&255));
    accept();for(unsigned i=0;i<8;i++)*addr[i]=16*i;
    unsigned conflict=request(false,2,255);assert(conflict==same+7);
    for(unsigned i=0;i<8;i++)assert(*reply[i]==0x8bab03f2u+i);
    std::cout<<"PASS scratch broadcast response cycles="<<same<<" eight conflicting words="<<conflict<<"\n";
}
