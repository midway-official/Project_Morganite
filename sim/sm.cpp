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
    std::array<uint32_t, 8> response{};
    unsigned due = 0, fault = 0, faultAddr = 0, requests = 0;
    for (unsigned cycle = 1; cycle < 1000000; ++cycle) {
        d.io_global_req_ready = !due && (cycle % 31 > 7);
        d.io_global_rsp_valid = due && cycle >= due;
        d.io_global_rsp_bits_fault = fault; d.io_global_rsp_bits_address = faultAddr;
#define RSP(I) d.io_global_rsp_bits_data_##I = response[I];
        RSP(0) RSP(1) RSP(2) RSP(3) RSP(4) RSP(5) RSP(6) RSP(7)
#undef RSP
        d.eval();
        if (d.io_global_rsp_valid && d.io_global_rsp_ready) due = 0;
        if (d.io_global_req_valid && d.io_global_req_ready) {
            if (due) return 5;
#define ADDR(I) d.io_global_req_bits_addr_##I
#define DATA(I) d.io_global_req_bits_data_##I
            std::array<uint64_t, 8> addr{ADDR(0),ADDR(1),ADDR(2),ADDR(3),ADDR(4),ADDR(5),ADDR(6),ADDR(7)};
            std::array<uint32_t, 8> data{DATA(0),DATA(1),DATA(2),DATA(3),DATA(4),DATA(5),DATA(6),DATA(7)};
#undef ADDR
#undef DATA
            auto mask = d.io_global_req_bits_mask;
            unsigned size = 1u << d.io_global_req_bits_size;
            fault = 0; faultAddr = 0;
            for (unsigned i = 0; i < 8; ++i) if (mask & (1u << i)) {
                if ((addr[i] % size) || addr[i] < base || addr[i] + size > base + memory.size()) {
                    fault = 3; faultAddr = addr[i]; break;
                }
            }
            if (!fault) for (unsigned i = 0; i < 8; ++i) if (mask & (1u << i)) {
                unsigned pos = addr[i] - base, value = 0;
                for (unsigned j = 0; j < size; ++j) {
                    if (d.io_global_req_bits_store) memory[pos + j] = data[i] >> (8 * j);
                    value |= uint32_t(memory[pos + j]) << (8 * j);
                }
                if (!d.io_global_req_bits_unsigned && size < 4 && (value & (1u << (8 * size - 1))))
                    value |= ~0u << (8 * size);
                response[i] = value;
            }
            ++requests; due = cycle + 2 + (requests * 7 % 37);
        }
        if (d.io_result_valid) {
            if (due || d.io_result_bits_group != gid) return 6;
            std::ofstream output(argv[3], std::ios::binary);
            output.write(reinterpret_cast<const char*>(memory.data()), memory.size());
            std::cout << "{\"cycles\":" << d.io_result_bits_cycles << ",\"requests\":" << requests
                << ",\"fp_ops\":" << d.io_result_bits_fpOps << ",\"fmas\":" << d.io_result_bits_fmaOps
                << ",\"fault\":" << unsigned(d.io_result_bits_fault) << ",\"address\":" << d.io_result_bits_address << "}\n";
            return d.io_result_bits_fault != expectedFault;
        }
        tick();
    }
    std::cerr << "SM timed out, requests=" << requests << "\n";
    return 1;
}
