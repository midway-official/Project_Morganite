#include "VSm.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <vector>

static std::vector<uint8_t> readFile(const char* name) {
    std::ifstream stream(name, std::ios::binary);
    if (!stream) throw std::runtime_error(name);
    return {std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>()};
}

int main(int argc, char** argv) {
    if (argc != 9) return 2;
    auto program = readFile(argv[1]), memory = readFile(argv[2]);
    unsigned gid = std::stoul(argv[4]), threads = std::stoul(argv[5]);
    unsigned base = std::stoul(argv[6], nullptr, 0), arg = std::stoul(argv[7], nullptr, 0);
    const unsigned expectedFault = std::stoul(argv[8]);
    VSm d;
    auto tick = [&]() { d.clock = 1; d.eval(); d.clock = 0; d.eval(); };
    d.clock = 0; d.reset = 1; d.io_result_ready = 0; d.io_start_valid = 0;
    d.io_program_valid = 0; d.io_cancel = 0; d.io_global_req_ready = 0; d.io_global_rsp_valid = 0;
    d.eval(); tick(); d.reset = 0;
    if (program.empty() || program.size() % 4 || program.size() > 4096) return 3;
    for (unsigned i = 0; i < program.size(); i += 4) {
        d.io_program_valid = 1; d.io_program_bits_address = i / 4;
        d.io_program_bits_data = uint32_t(program[i]) | uint32_t(program[i+1]) << 8
            | uint32_t(program[i+2]) << 16 | uint32_t(program[i+3]) << 24;
        tick();
    }
    d.io_program_valid = 0; d.io_start_valid = 1; d.io_start_bits_threads = threads;
    d.io_start_bits_id = gid; d.io_start_bits_argument = arg;
    d.io_start_bits_words = program.size() / 4; d.io_start_bits_programBase = base;
    d.eval(); if (!d.io_start_ready) return 4;
    tick(); d.io_start_valid = 0;
    struct Port {
        CData &ready, &valid, &rspReady, &rspValid, &rspFault, &mask, &size, &store, &uns;
        IData &rspAddress;
        std::array<QData*,8> addr;
        std::array<IData*,8> data, reply;
        unsigned due=0;
    };
#define FIELDS(x) {&x##_0,&x##_1,&x##_2,&x##_3,&x##_4,&x##_5,&x##_6,&x##_7}
#define PORT(x) {x##_req_ready,x##_req_valid,x##_rsp_ready,x##_rsp_valid,x##_rsp_bits_fault, \
        x##_req_bits_mask,x##_req_bits_size,x##_req_bits_store,x##_req_bits_unsigned,x##_rsp_bits_address, \
        FIELDS(x##_req_bits_addr),FIELDS(x##_req_bits_data),FIELDS(x##_rsp_bits_data)}
    Port ports[] = { PORT(d.io_global)
#ifdef WIDE_LSU
        ,PORT(d.io_global1)
#endif
    };
    unsigned requests=0, peak=0;
    for (unsigned cycle = 1; cycle < 1000000; ++cycle) {
        for (auto &p:ports) {
            p.ready = !p.due && cycle%31>7;
            p.rspValid = p.due && cycle>=p.due;
        }
        d.eval();
        unsigned active=0;
        for (auto &p:ports) {
            if (p.rspValid && p.rspReady) p.due=0;
            if (p.valid && p.ready) {
                const unsigned bytes=1u<<p.size;
                p.rspFault=0; p.rspAddress=0;
                for(unsigned i=0;i<8;i++) if(p.mask>>i&1) {
                    uint64_t a=*p.addr[i];
                    if(a%bytes || a<base || a+bytes>base+memory.size()) {
                        p.rspFault=3;p.rspAddress=a;break;
                    }
                }
                for(unsigned i=0;i<8;i++) if(!p.rspFault && (p.mask>>i&1)) {
                    unsigned pos=*p.addr[i]-base, value=0;
                    for(unsigned j=0;j<bytes;j++) {
                        if(p.store) memory[pos+j]=*p.data[i]>>(8*j);
                        value|=uint32_t(memory[pos+j])<<(8*j);
                    }
                    if(!p.uns && bytes<4 && (value>>(8*bytes-1))) value|=~0u<<(8*bytes);
                    *p.reply[i]=value;
                }
                ++requests; p.due=cycle+12+(requests*7%57);
            }
            active+=bool(p.due);
        }
        peak=std::max(peak,active);
        if (d.io_result_valid) {
            if (active || d.io_result_bits_group != gid) return 6;
            std::ofstream output(argv[3], std::ios::binary);
            output.write(reinterpret_cast<const char*>(memory.data()), memory.size());
            std::cout << "{\"cycles\":" << d.io_result_bits_cycles << ",\"requests\":" << requests
                << ",\"peak_global\":" << peak << ",\"fp_ops\":" << d.io_result_bits_fpOps << ",\"fmas\":" << d.io_result_bits_fmaOps
                << ",\"fault\":" << unsigned(d.io_result_bits_fault) << ",\"address\":" << d.io_result_bits_address << "}\n";
            return d.io_result_bits_fault != expectedFault;
        }
        tick();
    }
    std::cerr << "SM timed out, requests=" << requests << "\n";
    return 1;
}
