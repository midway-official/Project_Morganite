#include "VWideDma.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <map>
#include <vector>

using Words = std::array<uint32_t, 8>;
using Addresses = std::array<uint64_t, 8>;
#define LANES(field) { &d.field##_0, &d.field##_1, &d.field##_2, &d.field##_3, &d.field##_4, &d.field##_5, &d.field##_6, &d.field##_7 }
struct Bench {
    VWideDma d;
    std::array<QData*,8> addr = LANES(io_clients_0_req_bits_addr);
    std::array<IData*,8> data = LANES(io_clients_0_req_bits_data);
    std::array<IData*,8> reply = LANES(io_clients_0_rsp_bits_data);
    std::array<uint8_t, 256> ram{}, expected{};
    unsigned cycle = 0, cases = 0;
    void edge() { d.clock=1; d.eval(); d.clock=0; d.eval(); ++cycle; }
    Bench() {
        d.reset=1; d.clock=0; d.io_base=0x1fc0; d.io_limit=0x20c0;
        d.io_codeBase=0x2060; d.io_codeEnd=0x2080;
        d.io_roBase=0x2080; d.io_roEnd=0x20a0;
        for (unsigned i=0;i<ram.size();i++) ram[i]=i*37+0x91;
        expected=ram; d.eval(); edge(); edge(); d.reset=0;
    }
    void run(Addresses a, unsigned mask, unsigned size, bool store, bool uns,
             unsigned fault=0, unsigned faultAddr=0) {
        const unsigned bytes=1u<<size;
        std::map<unsigned,unsigned> reads;
        std::map<unsigned,std::vector<unsigned>> writes;
        Words result{};
        d.io_clients_0_req_bits_mask=mask; d.io_clients_0_req_bits_size=size;
        d.io_clients_0_req_bits_store=store; d.io_clients_0_req_bits_unsigned=uns;
        for (unsigned i=0;i<8;i++) {
            *addr[i]=a[i]; *data[i]=0x83bc7981u*(i+cases+1);
            if (!(mask>>i&1) || fault) continue;
            unsigned line=a[i]&~31u;
            bool full=line>=d.io_base && line+32<=d.io_limit;
            unsigned key=full?line:a[i];
            if (store) {
                unsigned bits=((1u<<bytes)-1)<<(a[i]&31);
                auto &list=writes[key];
                auto it=std::find_if(list.begin(),list.end(),[&](unsigned m){return !(m&bits);});
                if (it==list.end()) list.push_back(bits); else *it|=bits;
                for(unsigned b=0;b<bytes;b++) expected.at(a[i]-0x1fc0+b)=*data[i]>>(8*b);
            } else {
                reads[key]=full?5:size;
                for(unsigned b=0;b<bytes;b++) result[i]|=uint32_t(expected.at(a[i]-0x1fc0+b))<<(8*b);
                if(!uns && bytes<4 && (result[i]>>(8*bytes-1))) result[i]|=~0u<<(8*bytes);
            }
        }
        d.io_clients_0_req_valid=1; d.io_clients_0_rsp_ready=0;
        d.eval(); assert(d.io_clients_0_req_ready); edge(); d.io_clients_0_req_valid=0;
        bool aw=false,wd=false;
        unsigned awAddr=0,awID=0,strb=0;
        Words payload{};
        unsigned ticks=0;
        while(true) {
            assert(++ticks<1600);
            d.io_axi_aw_ready=cycle%11<3; d.io_axi_w_ready=cycle%13>5;
            d.io_axi_ar_ready=cycle%7==1;
            d.eval();
            if(d.io_clients_0_rsp_valid) break;
            bool ar=d.io_axi_ar_valid && d.io_axi_ar_ready;
            bool w=d.io_axi_w_valid && d.io_axi_w_ready;
            bool h=d.io_axi_aw_valid && d.io_axi_aw_ready;
            bool b=d.io_axi_b_valid && d.io_axi_b_ready;
            bool r=d.io_axi_r_valid && d.io_axi_r_ready;
            if(ar) {
                unsigned k=d.io_axi_ar_bits_addr;
                assert(!store && !fault && reads.count(k) && reads.at(k)==d.io_axi_ar_bits_size);
                assert(d.io_axi_ar_bits_len==0 && d.io_axi_ar_bits_burst==1);
                reads.erase(k);
                unsigned line=k&~31u;
                for(unsigned j=0;j<8;j++) {
                    unsigned value=0;
                    for(unsigned q=0;q<4;q++) value|=uint32_t(ram.at(line+j*4+q-0x1fc0))<<(8*q);
                    d.io_axi_r_bits_data[j]=value;
                }
                d.io_axi_r_bits_id=d.io_axi_ar_bits_id; d.io_axi_r_bits_last=1;
            }
            if(h) {
                assert(store && !aw && !fault && !d.io_axi_aw_bits_len && d.io_axi_aw_bits_burst==1);
                aw=true; awAddr=d.io_axi_aw_bits_addr; awID=d.io_axi_aw_bits_id;
                assert(writes.count(awAddr));
                assert(d.io_axi_aw_bits_size==((awAddr%32 || awAddr<d.io_base ||
                    (awAddr&~31u)+32>d.io_limit)?size:5));
            }
            if(w) {
                assert(store && !wd && !fault && d.io_axi_w_bits_last);
                wd=true; strb=d.io_axi_w_bits_strb;
                std::copy_n(&d.io_axi_w_bits_data[0],8,payload.begin());
            }
            edge();
            if(b) d.io_axi_b_valid=0;
            if(r) d.io_axi_r_valid=0;
            if(ar) d.io_axi_r_valid=1;
            if(aw && wd) {
                auto &v=writes.at(awAddr);
                assert(!v.empty() && v.front()==strb);
                v.erase(v.begin()); if(v.empty()) writes.erase(awAddr);
                for(unsigned j=0;j<32;j++) if(strb>>j&1)
                    ram.at((awAddr&~31u)+j-0x1fc0)=payload[j/4]>>(8*(j%4));
                d.io_axi_b_bits_id=awID; d.io_axi_b_valid=1; aw=wd=false;
            }
        }
        assert(reads.empty() && writes.empty() && !aw && !wd && expected==ram);
        assert(d.io_clients_0_rsp_bits_fault==fault);
        if(fault) assert(d.io_clients_0_rsp_bits_address==faultAddr);
        if(!store && !fault) for(unsigned i=0;i<8;i++) if(mask>>i&1) assert(*reply[i]==result[i]);
        d.io_clients_0_rsp_ready=1; edge(); ++cases;
    }
};
int main() {
    Bench b;
    for(unsigned m=0;m<256;m++) for(unsigned s=0;s<3;s++) for(unsigned pattern=0;pattern<5;pattern++) {
        Addresses a;
        for(unsigned i=0;i<8;i++) {
            unsigned lane=pattern==2?(5*i+3)%8:i;
            a[i]=pattern==3?0x200c:pattern==4?0x2000+(i%3)*(1<<s):
                0x1fe0+lane*(1<<s)+(pattern==1?4:0);
        }
        b.run(a,m,s,true,false); b.run(a,m,s,false,false); b.run(a,m,s,false,true);
    }
    Addresses a; a.fill(0x2000);
    for(uint64_t bad : {uint64_t(0x1fbc),uint64_t(0x20c0),uint64_t(0x2002),uint64_t(0x100002000)}) {
        a[7]=bad; b.run(a,255,2,true,false,3,uint32_t(bad)); b.run(a,127,2,true,false);
    }
    for(unsigned bad : {0x2060u,0x2080u}) {a[7]=bad; b.run(a,255,2,true,false,3,bad);}
    b.d.io_base=0x1fc4; b.d.io_limit=0x20bc;
    a.fill(0x1fc4); a[7]=0x20b8; b.run(a,255,2,true,false); b.run(a,255,2,false,true);
    std::cout<<"PASS wide DMA cases="<<b.cases<<" masks=256 aligned/offset/broadcast=1/2/1 beats\n";
}
