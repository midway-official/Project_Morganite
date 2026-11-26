#include "VWideDma.h"
#include <array>
#include <cassert>
#include <deque>
#include <iostream>
#include <map>
#include <vector>

struct Port {
    CData &valid, &ready, &mask, &size, &store, &rsp, &rspReady, &fault;
    QData &addr, &next;
    IData &data, &reply;
};
#define P(i) {d.io_clients_##i##_req_valid,d.io_clients_##i##_req_ready,d.io_clients_##i##_req_bits_mask, \
 d.io_clients_##i##_req_bits_size,d.io_clients_##i##_req_bits_store,d.io_clients_##i##_rsp_valid, \
 d.io_clients_##i##_rsp_ready,d.io_clients_##i##_rsp_bits_fault,d.io_clients_##i##_req_bits_addr_0, \
 d.io_clients_##i##_req_bits_addr_1,d.io_clients_##i##_req_bits_data_0,d.io_clients_##i##_rsp_bits_data_0}
int main() {
    VWideDma d;
    Port ports[]={P(0),P(1),P(2),P(3),P(4),P(5),P(6),P(7),P(8),P(9),P(10),P(11)};
    auto edge=[&]{d.clock=1;d.eval();d.clock=0;d.eval();};
    d.clock=0;d.reset=1;d.eval();edge();edge();d.reset=0;
    d.io_base=0x1000;d.io_limit=0x4000;
    for(unsigned round=0;round<4;round++) {
        unsigned completed=0, nr=0, nw=0, writes=0;
        std::map<unsigned,unsigned> reads, stores;
        std::deque<unsigned> aw, words;
        std::array<std::vector<unsigned>,3> held;
        auto stable=[&](unsigned ch,bool valid,bool ready,std::vector<unsigned> value) {
            if(!held[ch].empty()) assert(valid && held[ch]==value);
            held[ch]=valid && !ready?value:std::vector<unsigned>{};
        };
        bool cancel=round==2;
        for(unsigned i=0;i<12;i++) {
            auto &p=ports[i];p.valid=1;p.size=2;p.mask=cancel?3:1;p.store=i>=8;
            p.addr=0x1000+64*i;p.next=p.addr+32;p.data=i+47;p.rspReady=0;
        }
        for(unsigned cycle=0;cycle<2000 && completed!=4095;cycle++) {
            d.io_cancel=cancel && cycle>=130;
            d.io_axi_aw_ready=cycle>(round%2?4:75) && cycle%7<3;
            d.io_axi_ar_ready=cycle%11<4;
            d.io_axi_w_ready=cycle>(round%2?75:4) && cycle%9<3;
            if(cycle>150 && !reads.empty()) {
                auto [id,source]=*reads.rbegin();
                d.io_axi_r_valid=1;d.io_axi_r_bits_id=id;d.io_axi_r_bits_last=1;
                d.io_axi_r_bits_resp=round==1 && source==2?3:0;
                d.io_axi_r_bits_data[0]=0x12340000+source;
            }
            if(cycle>175 && !stores.empty()) {
                auto [id,source]=*stores.rbegin();
                d.io_axi_b_valid=1;d.io_axi_b_bits_id=id;
                d.io_axi_b_bits_resp=round==1 && source==9?2:0;
            }
            for(auto &p:ports) p.rspReady=cycle>300;
            d.eval();
            stable(0,d.io_axi_ar_valid,d.io_axi_ar_ready,{d.io_axi_ar_bits_addr,d.io_axi_ar_bits_id,d.io_axi_ar_bits_size});
            stable(1,d.io_axi_aw_valid,d.io_axi_aw_ready,{d.io_axi_aw_bits_addr,d.io_axi_aw_bits_id,d.io_axi_aw_bits_size});
            std::vector<unsigned> payload(&d.io_axi_w_bits_data[0],&d.io_axi_w_bits_data[0]+8);
            payload.push_back(d.io_axi_w_bits_strb);
            stable(2,d.io_axi_w_valid,d.io_axi_w_ready,payload);
            if(d.io_axi_ar_valid && d.io_axi_ar_ready) {
                auto a=d.io_axi_ar_bits_addr;
                assert((a-0x1000)%64==0 && reads.emplace(d.io_axi_ar_bits_id,(a-0x1000)/64).second);
                ++nr;
            }
            if(d.io_axi_aw_valid && d.io_axi_aw_ready) {
                auto a=d.io_axi_aw_bits_addr;
                assert((a-0x1000)%64==0 && stores.emplace(d.io_axi_aw_bits_id,(a-0x1000)/64).second);
                aw.push_back((a-0x1000)/64); ++nw;
            }
            if(d.io_axi_w_valid && d.io_axi_w_ready) {
                assert(d.io_axi_w_bits_strb==15);words.push_back(d.io_axi_w_bits_data[0]);++writes;
            }
            if(!aw.empty() && !words.empty()) {
                assert(words.front()==aw.front()+47);aw.pop_front();words.pop_front();
            }
            if(cycle==130) assert(reads.size()==8 && stores.size()==4 && writes==4);
            bool r=d.io_axi_r_valid && d.io_axi_r_ready,b=d.io_axi_b_valid && d.io_axi_b_ready;
            if(r) assert(reads.erase(d.io_axi_r_bits_id)==1);
            if(b) assert(stores.erase(d.io_axi_b_bits_id)==1);
            unsigned accepted=0;
            for(unsigned i=0;i<12;i++) {
                auto &p=ports[i];
                if(p.valid && p.ready) accepted|=1<<i;
                if(p.rsp && p.rspReady) {
                    assert(!(completed>>i&1)); completed|=1<<i;
                    unsigned fault=cancel?7:round==1 && i==2?6:round==1 && i==9?5:0;
                    assert(p.fault==fault);
                    if(i<8 && !fault) assert(p.reply==0x12340000+i);
                }
            }
            edge();if(b)d.io_axi_b_valid=0;if(r)d.io_axi_r_valid=0;
            for(unsigned i=0;i<12;i++) if(accepted>>i&1) ports[i].valid=0;
        }
        d.io_cancel=0;edge();
        assert(completed==4095 && nr==8 && nw==4 && writes==4 && d.io_idle);
    }
    std::cout<<"PASS tree 8R/4W simultaneous, reverse IDs, AW/W stalls, SLVERR/DECERR, cancel and reuse\n";
}
