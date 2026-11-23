#include "VFp32Lane.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <iostream>

struct Request { unsigned op, a, b, c, result, flags; };
struct Expected { Request r; unsigned tag; bool active; };

int main() {
    std::ios::sync_with_stdio(false);
    std::cin.tie(nullptr);
    VFp32Lane dut;
    auto tick = [&]() { dut.clock = 1; dut.eval(); dut.clock = 0; dut.eval(); };
    dut.clock = 0; dut.reset = 1; dut.io_in_valid = 0; dut.io_out_ready = 0;
    dut.eval(); tick(); dut.reset = 0;
    std::array<unsigned, 13> checked{};
    std::deque<Expected> expected;
    Request r{};
    bool have = false, eof = false, masked = false, wasStalled = false;
    unsigned issued = 0, inactive = 0, consecutive = 0, longest = 0, cycles = 0, outputStalls = 0;
    for (; cycles < 10000000; ++cycles) {
        if (!have && !eof) {
            have = bool(std::cin >> std::hex >> r.op >> r.a >> r.b >> r.c >> r.result >> r.flags);
            if (!have && !std::cin.eof()) return 2;
            eof = !have;
        }
        dut.io_in_valid = have;
        dut.io_in_bits_op = r.op; dut.io_in_bits_a = r.a;
        dut.io_in_bits_b = r.b; dut.io_in_bits_c = r.c;
        dut.io_in_bits_tag = issued & 4095; dut.io_in_bits_active = !masked;
        dut.io_out_ready = cycles % 4096 >= 257 && cycles % 67 != 0;
        dut.eval();
        if (wasStalled && !dut.io_out_valid) return 3;
        if (dut.io_out_valid) {
            if (expected.empty()) return 4;
            const Expected& e = expected.front();
            if (dut.io_out_bits_tag != e.tag || dut.io_out_bits_active != e.active ||
                dut.io_out_bits_data != (e.active ? e.r.result : 0) ||
                dut.io_out_bits_flags != (e.active ? e.r.flags : 0)) {
                std::cerr << std::hex << "op=" << e.r.op << " a=" << e.r.a << " b=" << e.r.b
                    << " c=" << e.r.c << " expected=" << e.r.result << "/" << e.r.flags
                    << " got=" << dut.io_out_bits_data << "/" << unsigned(dut.io_out_bits_flags)
                    << " tag=" << e.tag << " active=" << e.active << "\n";
                return 5;
            }
            if (dut.io_out_ready) {
                if (e.active) ++checked.at(e.r.op); else ++inactive;
                expected.pop_front();
            } else { ++outputStalls; }
        }
        wasStalled = dut.io_out_valid && !dut.io_out_ready;
        if (have && dut.io_in_ready) {
            expected.push_back({r, issued & 4095, !masked});
            ++issued; ++consecutive;
            longest = std::max(consecutive, longest);
            if (!masked && issued % 97 == 0) masked = true;
            else { masked = false; have = false; }
        } else consecutive = 0;
        tick();
        if (eof && expected.empty()) break;
    }
    if (!eof || !expected.empty() || *std::min_element(checked.begin(), checked.end()) < 100000 ||
        !inactive || longest < 32 || outputStalls < 200) return 6;
    std::cout << "{\"seed_base\":31703,\"active_by_opcode\":[";
    for (unsigned i = 0; i < checked.size(); ++i) std::cout << (i ? "," : "") << checked[i];
    std::cout << "],\"masked\":" << inactive << ",\"cycles\":" << cycles + 1
        << ",\"output_stall_cycles\":" << outputStalls << ",\"longest_consecutive_accepts\":" << longest << "}\n";
}
