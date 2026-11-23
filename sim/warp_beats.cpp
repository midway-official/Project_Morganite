#include "VWarpBeats.h"
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <random>

struct Beat { unsigned index, mask; bool last; };

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    const unsigned width = std::stoul(argv[1]), lanes = std::stoul(argv[2]);
    const unsigned masks = 1u << width, laneMask = (1u << lanes) - 1;
    VWarpBeats dut;
    const auto tick = [&]() {
        dut.clock = 1; dut.eval();
        dut.clock = 0; dut.eval();
    };
    dut.clock = 0; dut.reset = 1; dut.io_issue_valid = 0; dut.io_beat_ready = 0;
    dut.eval(); tick(); dut.reset = 0;
    std::mt19937 rng(0x4d4f52);
    std::deque<Beat> expected;
    unsigned issued = 0, checked = 0;
    bool stalled = false, offering = false;
    Beat held{};
    for (unsigned cycle = 0; cycle < masks * 100 + 512; ++cycle) {
        if (issued < masks && (rng() & 3)) offering = true;
        dut.io_issue_valid = offering;
        dut.io_issue_bits = issued;
        dut.io_beat_ready = cycle % 151 > 77 && (rng() & 3);
        dut.eval();
        bool valid = dut.io_beat_valid, ready = dut.io_beat_ready;
        Beat actual{dut.io_beat_bits_index, dut.io_beat_bits_mask, bool(dut.io_beat_bits_last)};
        auto equal = [](Beat a, Beat b) { return a.index == b.index && a.mask == b.mask && a.last == b.last; };
        if (stalled && (!valid || !equal(actual, held))) return 3;
        if (valid != !expected.empty() || (valid && !equal(actual, expected.front()))) return 4;
        if (valid && ready) { expected.pop_front(); ++checked; }
        held = actual; stalled = valid && !ready;
        if (dut.io_issue_valid && dut.io_issue_ready) {
            for (unsigned offset = 0; offset < width; offset += lanes) {
                unsigned mask = (issued >> offset) & laneMask;
                if (mask) expected.push_back({offset / lanes, mask, (issued >> (offset + lanes)) == 0});
            }
            ++issued; offering = false;
        }
        tick();
        if (issued == masks && expected.empty()) {
            dut.eval();
            if (dut.io_beat_valid) return 5;
            std::cout << "{\"top\":\"WarpBeats\",\"width\":" << width << ",\"lanes\":" << lanes
                << ",\"masks\":" << issued << ",\"beats\":" << checked << ",\"cycles\":" << cycle + 1 << "}\n";
            return 0;
        }
    }
    return 1;
}
