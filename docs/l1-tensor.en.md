# Per-SM L1, INT8 matrix MAC and FMA utilization

[简体中文](l1-tensor.md) · **English**

The first full configuration from `4517279` uses 2 KiB each of I/D cache per SM, 64 direct-mapped 32-byte lines, four lanes, eight threads per warp, four warp slots and K=4.

## Visibility and programming

Instructions fill on demand from HBM. Each SM has one outstanding instruction fetch and a hot line serving multiple PCs. Round-robin arbitration chooses among missing warps. PC is relative to the program base. Fills respect the descriptor's last word, partial lines, unaligned program bases and 4 KiB boundaries. Tags and data use synchronous RAM. Reset and submission invalidate lines. Hot-buffer fetches bypass the L1 hit counter.

The data cache only allocates complete lines inside the immutable physical interval [roBase,roEnd). Eligible vectors have an active lane 0 and either broadcast an aligned byte/half/word or access consecutive lane-aligned words. Other accesses and writable global data take the DMA path. On a miss, a successful fill sets valid. A failed fill reports the address. Each SM can keep its own clean copy. Before issuing a write burst, DMA checks every active lane's entire byte range and rejects a vector touching code or declared read-only data. Code protection also applies with I-cache alone. Scratchpad is private to the workgroup.

Completion waits for all SMs and accepted DMA traffic. Cancellation stops new groups while accepted requests drain. The next doorbell invalidates all private I/D lines and hot instruction buffers. Linux uses exclusive device open, blocks userspace buffer access while busy, allocates coherent DMA and orders submission/completion with dma_wmb()/dma_rmb(). Other masters including JTAG must respect ownership. [RTL](../src/main/scala/morganite/ReadOnlyL1.scala) · [DMA](../src/main/scala/morganite/Dma.scala) · [Driver](../linux/morganite.c)

| Control | Meaning |
| --- | --- |
| ic=N, dc=N | Bytes per SM. Zero disables. Enabled sizes are powers of two from two lines to 64 KiB. |
| line=N | Power-of-two 32-bit words per line, at most the warp width with caching. |
| tensor=0/1/2/4 | Zero disables. Enabled K requires a multiple of four physical lanes. |
| earlyBranch=1 | Direct jumps update PC at issue. Conditional jumps wait for predicates while independent arithmetic can retire. |
| bramRf=1 | Three read copies, masked write and same-cycle forwarding. Registers the RAM data output. Vivado can infer BRAM for either template. |
| 0x50 | I/D enable in bits 0/1, matrix K in bits 10:8. |
| 0x54, 0x58 | Immutable base and exclusive limit. Writable when idle. Equal addresses disable allocation. |
| 0x600 + 16×SM | Four 32-bit counters for I hits/misses and D hits/misses, cleared on submission. |

`mma.s8 rd,ra,rb,rc` uses R4 opcode 0x47, funct3 and bits 26:25 zero. In each group of four adjacent threads lanes 0/2 supply A rows 0/1 and lanes 0/1 supply B columns 0/1. The low byte is k=0. Each active thread computes its C element plus a signed INT8 dot product, retaining the low 32 bits. A partial four-lane tile faults. Each tile may be fully masked and destination may overlap sources. Product and partial sum stages share six-cycle writeback and the warp scoreboard. INT8 MACs and scalar FP32 FMAs have separate counters.

`mgpu submit desc_offset sm_mask ro_offset ro_length` uses MG_SUBMIT_RO. The driver checks offsets against the DMA allocation. Legacy MG_SUBMIT clears the interval. Read MG_SM_COUNTERS for SMs beyond the first four and for cache counters. Use `bash linux/build_userspace.sh` before `python3 linux/build_kernel.py --feature-register` for a bitstream with the feature register. Older device trees avoid probing that register.

## Rebuild and validation

First build the SoftFloat reference with `bash scripts/test_fp.sh`. The base tests compare the whole DMA image and check AXI-Lite submission under random backpressure. Additional tests exercise same-address program/data replacement, cross-SM producer/consumer, cancelled/failed fills, direct-map A/B/A/A conflicts, protection and INT8 mask and register overlap. With 2/8/16 SMs the first access to one shared immutable line fills each SM separately and the second hits. A held R response keeps busy asserted during cancellation and rejects another doorbell until drain.

```bash
source scripts/toolchain.sh
d=build/l1-check-2
sbt "runMain morganite.Generate Gpu $d 2 8 4 ic=2048 dc=2048 line=8 tensor=4 bramRf=1 earlyBranch=1"
"$VERILATOR" --cc --exe --build -j 4 --top-module Gpu --Mdir "$d/obj" "$d/Gpu.sv" "$PWD/sim/gpu.cpp"
python3 scripts/test_gpu.py "$d/obj/VGpu" 2 "$d/jobs"
python3 scripts/test_accelerators.py "$d/obj/VGpu" 2 "$d/features"
python3 scripts/measure_fma.py "$d/obj/VGpu" "$d/performance" --unroll 8
vivado -mode batch -source scripts/synth_gpu.tcl -tclargs Gpu "$d/Gpu.sv" "$d/synth"
python3 scripts/stage_soc.py build/soc-l1 --accelerators
# On the Vivado 2023.2 server, inside the staged soc-l1 directory
MORGANITE_SYNTH_ONLY=1 bash run_soc.sh
```

## Historical 32-SM configuration

Hardware inputs were generated from `626b31b` and the server software package from `d2514ae`. The configuration reuses the boot-20 CPU RTL, four lanes per SM, eight-thread warps, four slots, 4 KiB I-cache, 8 KiB read-only D-cache and 32-byte lines. Expected registers are 0x04080420 at 0x08 and 0x403 at 0x50. These are reproduction commands for the candidate. [Input manifest](../reports/l1-tensor/soc32-Vmx1k2nM/manifest.json)

```bash
python3 scripts/stage_soc.py build/rebuild32/soc --sms 32 --accelerators
bash linux/build_userspace.sh
python3 linux/build_kernel.py --feature-register
python3 scripts/stage_software.py build/rebuild32/software-board --sms 32 --gpu-features 0x403 --verify-load
(cd build/rebuild32/soc && sha256sum --quiet -c files.sha256)
(cd build/rebuild32/software-board && sha256sum --quiet -c files.sha256)
```

Stage the two directories in a fresh Vivado 2023.2 server workspace. Check routed timing, pulse width, DRC, CDC and constraints before submitting matched BIT, LTX and Linux image.

```bash
MORGANITE_SYNTH_ONLY=0 bash run_soc.sh
sha256sum --quiet -c implementation/program.sha256
python3 software-board/start_test.py . software-board board-01
python3 software-board/uart_jobs.py board-01 --full
```

The separate cache test uses `python3 software-board/uart_jobs.py board-02 --cache-test`. A copy kernel runs one warp per SM with the parameter line bypassed, then immutable, then updated at the same address between jobs. Compare buffer SHA-256 after each run and record idle D hit/miss counters. Expected pairs are 0/0, 1/1 and 1/1 per SM. The board CLI form is `mgpu kernel_ro copy groups threads sm_mask ro_offset ro_length`. The first candidate ram.dat hash was `ab96ec949de15d62018e33f245e5c68f0f7b077b02f481038f42c3573f9dc470`; with kernel_ro it was `39bec2d0c3d54317749419938b42df6f9041f1f63be196164275a7a3130deb2f`. Verify installed CLI/program hashes. After retrieving board-01/frames, use `python3 graphics/test_render.py board board-01/frames`, then `python3 graphics/export_png.py board-01/frames board-01/frames/comparisons.json board-01/frames`. Keep raw depths and primitive IDs. [Synthesis and validation records](../reports/l1-tensor)

## Design sources

AMD UG901 v2022.2 informed synchronous RAM inference, bypass and registered products. Vortex (279e65b, Apache-2.0) informed cache parameter checks and scoreboard timing. Turing Scheduling Guide v13.5 informed latency hiding across warps and accumulators. PTX ISA 9.5 and CUTLASS (f8acf1a, BSD-3-Clause) informed the four-thread tile contract. Morganite defines its own 2×2 layout and instruction encoding. The Linux DMA API informed coherent mappings, ordering and drain. [UG901](https://docs.amd.com/r/en-US/ug901-vivado-synthesis) · [Vortex](https://github.com/vortexgpgpu/vortex/tree/279e65b47e63f6b87d043729d9afbbcb12fc198c) · [Turing](https://docs.nvidia.com/cuda/turing-tuning-guide/index.html) · [PTX MMA](https://docs.nvidia.com/cuda/parallel-thread-execution/index.html#warp-level-matrix-instructions-mma) · [CUTLASS](https://github.com/NVIDIA/cutlass/tree/f8acf1aa78f96ff2f1ba7662fe495e5535c461bf) · [DMA API](https://docs.kernel.org/core-api/dma-api-howto.html)
