#include "VGpu.h"
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <fstream>
#include <iostream>
#include <iterator>
#include <optional>
#include <random>
#include <stdexcept>
#include <vector>

static void check(bool condition, const char* text) { if (!condition) throw std::runtime_error(text); }
struct Addr { uint32_t addr, id, len, size; };
struct Beat { uint32_t data, strb, last; };
constexpr uint32_t base = 0x1e000000;

class Machine {
public:
    VGpu d;
    std::vector<uint8_t> mem;
    std::mt19937 rng{6227};
    unsigned timeout = 8000000;
    unsigned cycles = 0, overlap = 0, maxBurst = 0, byteWrites = 0, halfWrites = 0;
    unsigned failRead = 0, failWrite = 0;
    unsigned observedLine = 0, observedReads = 0;
    bool holdObserved = false;
    bool badId = false, lateLast = false;
    unsigned wBeforeAW = 0;
    bool awFire = false, wFire = false, arFire = false, bFire = false, rFire = false;
    unsigned hostB = 0, hostR = 0, hostData = 0;
    std::optional<Addr> rd, wr;
    std::deque<Beat> beats;
    unsigned ri = 0, wi = 0;
    bool rValid = false, bValid = false, waitingB = false;
    unsigned readValue = 0, readResp = 0, readID = 0, writeResp = 0, writeID = 0;
    bool readLast = false;
    std::array<std::vector<unsigned>, 3> held;

    explicit Machine(std::vector<uint8_t> bytes) : mem(std::move(bytes)) {
        d.clock = 0; d.reset = 1; d.io_host_aw_valid = 0; d.io_host_w_valid = 0;
        d.io_host_ar_valid = 0; d.io_host_b_ready = 0; d.io_host_r_ready = 0;
        for (int i = 0; i < 3; ++i) tick();
        d.reset = 0;
    }
    void stable(unsigned ch, bool v, bool ready, std::vector<unsigned> fields) {
        if (!held[ch].empty()) check(v && fields == held[ch], "unstable stalled AXI payload");
        held[ch] = v && !ready ? std::move(fields) : std::vector<unsigned>{};
    }
    void validate(Addr a, unsigned burst) const {
        uint64_t end = uint64_t(a.addr) + ((a.len + 1) << a.size);
        check(a.size <= 2 && burst == 1 && !(a.addr & ((1 << a.size)-1)), "bad AXI attributes");
        check(a.addr >= base && end <= base + mem.size() && (a.addr >> 12) == ((end - 1) >> 12),
              "AXI burst escaped range or crossed 4 KiB");
    }
    uint32_t get(uint32_t address) const {
        unsigned value = 0;
        for (unsigned i = 0; i < 4; ++i) value |= uint32_t(mem.at((address & ~3U) - base + i)) << (8*i);
        return value;
    }
    void tick() {
        check(++cycles < timeout, "GPU test timed out");
        d.io_dma_ar_ready = !rd && (rng() % 4) && cycles % 191 > 15;
        d.io_dma_aw_ready = !wr && !bValid && !waitingB && (rng() % 4) && cycles % 71 > 32;
        d.io_dma_w_ready = beats.size() < 32 && (rng() % 4) && cycles % 127 > 10;
        if (!rValid && rd && !(holdObserved && rd->addr == observedLine) && (rng() % 4) && cycles % 89 > 12) {
            readValue = get(rd->addr + (std::min(ri,rd->len) << rd->size));
            readResp = rd->addr == failRead ? 3 : 0;
            readID = rd->id ^ unsigned(badId && rd->addr == failRead);
            readLast = ri == rd->len + unsigned(lateLast && rd->addr == failRead);
            rValid = true;
        }
        if (!bValid && waitingB && (rng() % 4) && cycles % 67 > 18) { bValid = true; waitingB = false; }
        d.io_dma_r_valid = rValid; d.io_dma_r_bits_id = readID; d.io_dma_r_bits_resp = readResp;
        d.io_dma_r_bits_data = readValue; d.io_dma_r_bits_last = readLast;
        d.io_dma_b_valid = bValid; d.io_dma_b_bits_id = writeID; d.io_dma_b_bits_resp = writeResp;
        d.eval();
        awFire = d.io_host_aw_ready && d.io_host_aw_valid; wFire = d.io_host_w_ready && d.io_host_w_valid;
        arFire = d.io_host_ar_ready && d.io_host_ar_valid; bFire = d.io_host_b_ready && d.io_host_b_valid;
        rFire = d.io_host_r_ready && d.io_host_r_valid;
        hostB = d.io_host_b_bits; hostR = d.io_host_r_bits_resp; hostData = d.io_host_r_bits_data;
        if (!d.reset) {
            stable(0, d.io_dma_ar_valid, d.io_dma_ar_ready,
                {d.io_dma_ar_bits_addr,d.io_dma_ar_bits_id,d.io_dma_ar_bits_len,d.io_dma_ar_bits_size,
                 d.io_dma_ar_bits_burst,d.io_dma_ar_bits_cache,d.io_dma_ar_bits_prot,d.io_dma_ar_bits_qos,d.io_dma_ar_bits_lock});
            stable(1, d.io_dma_aw_valid, d.io_dma_aw_ready,
                {d.io_dma_aw_bits_addr,d.io_dma_aw_bits_id,d.io_dma_aw_bits_len,d.io_dma_aw_bits_size,
                 d.io_dma_aw_bits_burst,d.io_dma_aw_bits_cache,d.io_dma_aw_bits_prot,d.io_dma_aw_bits_qos,d.io_dma_aw_bits_lock});
            stable(2, d.io_dma_w_valid, d.io_dma_w_ready, {d.io_dma_w_bits_data,d.io_dma_w_bits_strb,d.io_dma_w_bits_last});
        }
        if (d.io_dma_ar_ready && d.io_dma_ar_valid) {
            rd = Addr{d.io_dma_ar_bits_addr,d.io_dma_ar_bits_id,d.io_dma_ar_bits_len,d.io_dma_ar_bits_size};
            validate(*rd, d.io_dma_ar_bits_burst); ri = 0; maxBurst = std::max(maxBurst, rd->len + 1);
            if (rd->addr == observedLine) ++observedReads;
        }
        if (d.io_dma_aw_ready && d.io_dma_aw_valid) {
            wr = Addr{d.io_dma_aw_bits_addr,d.io_dma_aw_bits_id,d.io_dma_aw_bits_len,d.io_dma_aw_bits_size};
            validate(*wr, d.io_dma_aw_bits_burst); wi = 0; maxBurst = std::max(maxBurst, wr->len + 1);
        }
        if (d.io_dma_w_ready && d.io_dma_w_valid) {
            beats.push_back({d.io_dma_w_bits_data,d.io_dma_w_bits_strb,d.io_dma_w_bits_last});
            if (!wr) ++wBeforeAW;
        }
        if (rd && (wr || bValid || waitingB)) ++overlap;
        if (rValid && d.io_dma_r_ready) { if (readLast) rd.reset(); ++ri; rValid = false; }
        if (bValid && d.io_dma_b_ready) bValid = false;
        if (wr && !beats.empty()) {
            auto beat = beats.front(); beats.pop_front();
            auto addr = wr->addr + (wi << wr->size), size = 1u << wr->size;
            unsigned strb = ((1u << size) - 1) << (addr & 3);
            check(beat.strb == strb && beat.last == (wi == wr->len), "bad WSTRB/WLAST");
            for (unsigned i = 0; i < 4; ++i) if (strb & (1u << i))
                mem.at((addr & ~3U) - base + i) = beat.data >> (8*i);
            byteWrites += size == 1; halfWrites += size == 2;
            if (beat.last) { writeResp = wr->addr == failWrite ? 2 : 0; writeID = wr->id; waitingB = true; wr.reset(); }
            ++wi;
        }
        d.clock = 1; d.eval(); d.clock = 0; d.eval();
    }
    unsigned write(unsigned offset, unsigned value, unsigned strb=15) {
        bool awDone = false, wDone = false;
        auto delay = rng() % 12, delayW = rng() % 12;
        d.io_host_aw_bits_addr = offset; d.io_host_aw_bits_prot = 0;
        d.io_host_w_bits_data = value; d.io_host_w_bits_strb = strb;
        for (unsigned i=0;;++i) {
            d.io_host_aw_valid = !awDone && i >= delay;
            d.io_host_w_valid = !wDone && i >= delayW;
            d.io_host_b_ready = i > 15 && (rng() & 1);
            tick(); awDone |= awFire; wDone |= wFire;
            if (bFire) { check(awDone && wDone, "B before AW/W"); break; }
        }
        d.io_host_aw_valid = d.io_host_w_valid = d.io_host_b_ready = 0;
        return hostB;
    }
    unsigned read(unsigned offset, unsigned resp=0) {
        d.io_host_ar_valid = 1; d.io_host_ar_bits_addr = offset; d.io_host_ar_bits_prot = 0;
        for (unsigned i=0;;++i) {
            d.io_host_r_ready = i > 6 && (rng() & 1);
            tick(); if (arFire) d.io_host_ar_valid = 0;
            if (rFire) break;
        }
        d.io_host_r_ready = 0; check(hostR == resp, "unexpected AXI-Lite RRESP"); return hostData;
    }
    void done() {
        unsigned status;
        do { status = read(0x14); } while (status & 1);
        check((status & 2) && !rd && !wr && !bValid && !waitingB && beats.empty(), "job did not drain");
    }
};

// Image contains one complete DMA window. argv[3] is the configured SM count.
int main(int argc, char** argv) {
    if (argc != 7) return 2;
    std::ifstream input(argv[1], std::ios::binary);
    check(bool(input), "input image");
    Machine m({std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()});
    unsigned sms = std::stoul(argv[3]), mask = std::stoul(argv[4]), expectedFault = std::stoul(argv[5]);
    std::string mode = argv[6];
    if (mode == "render") m.timeout = 40000000;
    check(m.read(0) == 0x4d4f5247 && m.read(4) == 0x10000 && (m.read(8) & 255) == sms, "identity");
    m.read(0x80,2);
    check(!m.write(0x30,base) && !m.write(0x34,base + m.mem.size()) && !m.write(0x38,mask), "window");
    check(!m.write(0x0c,base) && m.write(0x10,1,1) == 2 && m.write(0x74,1) == 2, "register decode");
    if (mode == "rerror" || mode == "rprotocol") {
        m.failRead = m.get(base + 8);
        m.badId = mode == "rprotocol"; m.lateLast = m.badId;
    }
    if (mode == "berror") m.failWrite = m.get(base + 16);
    const auto arg = m.get(base+16);
    if (mode == "l1" || mode == "ro-write" || mode == "fill-error" || mode == "fill-cancel" || mode == "conflict") {
        unsigned length = mode == "conflict" ? 4128 : 32;
        check(!m.write(0x54,arg) && !m.write(0x58,arg+length), "immutable window");
        m.observedLine = arg;
        if (mode == "fill-error") m.failRead = arg;
        m.holdObserved = mode == "fill-cancel";
    }
    if (mode == "cross-sm") check(!m.write(0x38,1), "producer SM0");
    check(!m.write(0x10,1), "doorbell");
    if (mode == "cancel") { for (int i=0; i<14; ++i) m.tick(); check(!m.write(0x2c,1), "cancel"); }
    if (mode == "fill-cancel") {
        while (!m.observedReads) m.tick();
        check(!m.write(0x2c,1) && (m.read(0x14)&1) && m.write(0x10,2)==2,
              "cancel must drain held AXI response before another submit");
        m.holdObserved = false;
    }
    m.done();
    unsigned fault = m.read(0x1c), address = m.read(0x20);
    check(fault == expectedFault && m.read(0x18) == unsigned(!fault) && m.read(0x24) == unsigned(bool(fault)), "fault or completion sequence");
    unsigned cycles = m.read(0x3c);
    unsigned hits = 0, misses = 0, reads = m.observedReads;
    if (mode == "cross-sm") {
        check(m.get(arg+32)==42 && m.read(0x104)==1, "SM0 output");
        unsigned code = m.get(base+8)-base;
        // The second program follows the four-word producer in the image.
        unsigned next = base+code+16;
        for (unsigned i=0;i<4;++i) m.mem.at(8+i)=next>>(8*i);
        m.mem.at(12)=6;
        check(!m.write(0x54,arg+32) && !m.write(0x58,arg+64) && !m.write(0x38,2), "consumer SM1");
        check(!m.write(0x10,2), "consumer doorbell"); m.done();
        check(m.read(0x18)==2 && m.get(arg+64)==84 && m.read(0x124)==1 &&
              m.read(0x618)==1 && m.read(0x61c)==1, "SM1 cache read after SM0 write");
    }
    if (mode == "l1" || mode == "conflict") {
        for (unsigned i=0; i<sms; ++i) {
            hits += m.read(0x608 + i*16);
            misses += m.read(0x60c + i*16);
        }
    }
    if (mode == "conflict") check(misses == 3 && hits == 1 && reads == 2, "A B A A cache replacement");
    if (mode == "l1") {
        check(misses == sms && hits >= sms && reads == misses, "expected private fills and reuse");
        // Each group writes its result to its own word beyond the immutable line.
        auto verify = [&](unsigned expected) {
            for (unsigned i=0; i<sms; ++i) check(m.get(arg+64+4*i)==expected, "stale job result");
        };
        verify(15);
        m.mem.at(arg-base) = 11;
        // Change addi r4,r4,1 to addi r4,r4,2 at the same program address.
        m.mem.at(m.get(base+8)-base+4*5+2) = 0x22;
        check(!m.write(0x10,2),"resubmit"); m.done();
        check(m.read(0x18)==2 && m.read(0x1c)==0 && m.observedReads == 2*reads, "refill after submit");
        verify(24);
    }
    std::vector<unsigned> groups, fmas, active;
    for (unsigned i=0;i<sms;++i) {
        active.push_back(m.read(0x100 + i*32)); groups.push_back(m.read(0x104+i*32)); fmas.push_back(m.read(0x10c+i*32));
    }
    if (mode == "ro-write") check(m.get(arg)==7 && m.halfWrites==0, "protected store escaped");
    if (fault) {
        // Recover on the same DUT after the injected error or cancellation.
        m.failRead = m.failWrite = 0; m.badId = m.lateLast = false;
        m.mem[12] = 1; m.mem[13] = m.mem[14] = m.mem[15] = 0;
        unsigned code = m.get(base+8)-base;
        m.mem.at(code) = 0x7b; for (int i=1;i<4;++i) m.mem.at(code+i)=0;
        check(!m.write(0x10,2), "recovery doorbell"); m.done();
        check(m.read(0x18)==2 && !m.read(0x1c), "recovery job");
    }
    std::ofstream out(argv[2], std::ios::binary);
    out.write(reinterpret_cast<const char*>(m.mem.data()), m.mem.size());
    auto list = [](const auto& v){ std::cout<<"["; for (size_t i=0;i<v.size();++i) std::cout<<(i?",":"")<<v[i]; std::cout<<"]"; };
    std::cout << "{\"cycles\":" << cycles << ",\"fault\":" << fault << ",\"address\":" << address
        << ",\"d_hits\":" << hits << ",\"d_misses\":" << misses << ",\"observed_reads\":" << reads
        << ",\"concurrent_rw_cycles\":" << m.overlap << ",\"longest_burst\":" << m.maxBurst
        << ",\"w_before_aw\":" << m.wBeforeAW << ",\"byte_writes\":" << m.byteWrites
        << ",\"half_writes\":" << m.halfWrites << ",\"groups\":"; list(groups);
    std::cout << ",\"active_cycles\":"; list(active); std::cout << ",\"fmas\":"; list(fmas); std::cout << "}\n";
}
