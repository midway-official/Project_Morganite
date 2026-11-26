# U50C synthesis and routing of the 16-SM graphics configuration

[简体中文](README.md)

An isolated checkout at `e46aeee8333676544123c8a3f5f5155b6f2af58f` generated the complete 4×4 SM SoC. Each SM has four physical lanes, giving 64 GraphicsLane instances. The capability mask for triangle setup, binning, raster, ROP and SFU is `0x1f`. The SFU supports approximate FP32 reciprocal and reciprocal square root. The CPU and GPU target 100 MHz. Two native 256-bit GPU AXI4 ports connect to the 225 MHz HBM interface. [Configuration and hashes](inputs.json)

| Subsystem | Build configuration |
| --- | --- |
| Compute | Each SM holds four eight-thread warps and issues a full warp across four physical lanes in two beats. Up to 512 threads can reside on the GPU. The lanes support FP32 FMA and signed INT8 matrix multiply-accumulate. Four lanes produce a 2×2 output tile with K=4 and 32-bit accumulators. |
| Registers and on-chip memory | Each thread has 32 registers of 32 bits. The BRAM register-file and early-branch options are enabled. Each SM has 8 KiB of scratchpad, a 2 KiB instruction L1 and a 2 KiB read-only data L1. Lines are 32 bytes. Only a designated immutable range is cacheable. Writes bypass the data L1. |
| Memory access | Each SM has two global LSU slots and two data L1 miss entries. Four clusters of four SMs feed a tree NoC. Each GPU port supports eight outstanding reads and four outstanding writes. The DMA window is striped across the stacks in 4 KiB pages. The GPU uses HBM SAXI01 and SAXI17, the CPU and JTAG share SAXI00, and the GPU control port uses 32-bit AXI4-Lite. |
| Graphics | Programs issue instructions for triangle edge coefficients and top-left bias, 8×8-pixel binning, edge evaluation and coverage, FP32 depth comparison, RGBA8 alpha blending, and approximate reciprocal and reciprocal square root. Programs control primitive traversal and framebuffer writes. |

At 100 MHz the 64 lanes can deliver **12.8 GFLOPS** if every lane accepts an FP32 FMA every cycle. The two 256-bit masters together can transfer **6.4 GB/s** in one direction at full utilization. Board measurements of throughput and render time are pending for this build. A same-port write followed by a read returned all X in the vendor HBM simulation. [Pin-level trace](../16sm-wide-hbm-rtl-20261126/pin-handshakes.txt)

The Vivado 2023.2 build started at 19:08:59 UTC+8 on 2026-11-26 in `/home/fpga/morganite-16sm-graphics-e46aeee-qQitZVZs`, targeting `xcu50-fsvh2104-2-e`. CPU and GPU RTL hashes were verified after transfer. The previous timing build was stopped during rip-up and reroute with its opt, placed and physopt checkpoints retained. [Run status](status.json) · [Previous run stop record](previous-build-stopped.json)

Generate the upload package from a clean checkout of that revision. Run the second command from the package directory on the Vivado 2023.2 build host.

```bash
python3 scripts/stage_soc.py build/soc-gfx --grid 4x4 --wide-ports 2 --accelerators --units setup bins raster rop sfu
bash run_soc.sh
```

## Synthesis

| Hierarchy | LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU | 112,056 | 40,080 | 61 | 0 | 8 |
| Morganite GPU | 712,789 | 290,794 | 254 | 0 | 1,856 |
| SoC | 836,769 | 343,496 | 321.5 | 0 | 1,864 |

The 64 GraphicsLane instances account for 43,152 LUTs, 64,640 FFs and 1,280 DSPs within the GPU row. Synthesized SoC LUT utilization is 95.99%. [Vivado synthesis reports](synthesis.tar.gz) · [Hierarchy totals](synthesis.json)

Of the GPU core LUTs, the 16 SM instances use 388,364 (54.5%), the wide DMA and arbitration use 206,503 (29.0%), and the 16 data L1 instances with their control logic use 116,194 (16.3%). The GPU top level and control-port queues account for the remaining 1,728. The SM counts include the graphics lanes.
