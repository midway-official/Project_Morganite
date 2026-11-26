# Morganite wide-memory GPU and optional graphics units

[简体中文](README.md) · [320×240 RTL color](images/cube_a/color.png) · [Depth](images/cube_a/depth.png)

## Special functions

The first SFU implements `rcp.approx.f32` for projective division, reciprocal triangle area and perspective-correct interpolation, and `rsqrt.approx.f32` for normalization, ray intersection and inverse-distance forces. Both use binary32 inputs and outputs. The existing ray tracing and N-body programs implement inverse square root in software. The optional raster library uses a hardware reciprocal seed followed by two fused residual refinements. Positive finite square root can reuse the reciprocal-square-root estimate and a multiply. A general square-root wrapper needs dedicated zero, infinity and domain handling.

Each enabled physical lane has a midpoint lookup with 128 intervals and 15 stored fraction bits. The inverse-square-root table has separate ranges for odd and even exponents. Results share the six-cycle arithmetic retirement path and the existing RF write port. Each function was tested on 165,040 inputs including all normal exponents at both ends of every interval and 100,000 seeded random bit patterns. The maximum observed relative errors on normal results were 0.389099% for reciprocal and 0.194998% for reciprocal square root. [Implementation](../../src/main/scala/morganite/GraphicsLane.scala) · [Independent test](../../sim/graphics_lane.cpp) · [Results](sfu.json)

Inputs and outputs flush subnormals to signed zero. Both signs of zero produce signed infinity. Reciprocal maps signed infinity to signed zero. Reciprocal square root maps positive infinity to positive zero, and negative normals or negative infinity to canonical quiet NaN. Both operations canonicalize NaN inputs to `0x7fc00000` and leave `fflags` untouched. Future candidates include `exp2/log2` for shading and fog, and shared-range-reduction `sincos` for procedural textures or angular fractal coordinates. Ordinary Mandelbrot and Julia escape loops predominantly use multiply-add and comparison. [PTX special functions](https://docs.nvidia.com/cuda/parallel-thread-execution/) · [AMD GPU ISA](https://gpuopen.com/amd-isa-documentation/)

### Application measurements

The same 1×2 RTL simulator ran both programs with four lanes per SM, eight threads per warp and two 256-bit memory ports. The counters measure GPU job cycles. The cube row sums vertex, setup, bin and pixel dispatches. The software and SFU runs use the same input, memory model, and enabled graphics hardware. The benchmark pins the original kernels to `d9295b4`. [Counters and output hashes](sfu-workloads.json) · [Kernel changes](rsqrt-kernels.patch)

| Workload | Software cycles | SFU cycles | Speedup | Comparison with independent reference |
| --- | ---: | ---: | ---: | --- |
| 256 bodies, one softened gravity step | 700,339 | 582,889 | 1.2015× | Maximum absolute state error 1.12×10⁻⁸ for both |
| Four spheres, primary and shadow rays, 64×48 | 146,920 | 139,446 | 1.0536× | Maximum RGBA8 channel error 1 for software, 0 for SFU |
| 12-triangle cube, 64×32 | 404,716 | 402,832 | 1.0047× | Both match color, coverage and depth winner exactly |

N-body computes inverse distance for all 65,536 ordered pairs. The SFU program replaces the software seed and two Newton steps with the hardware seed and one FMA-based refinement. The ray tracer only evaluates a square root for candidates with a positive discriminant. The cube already spends 386,308 of its 404,716 software cycles in its pixel pass, where coverage, triangle traversal, interpolation and output still execute. Only 483 pixels are covered. These speedups apply to these complete programs and inputs. [Reference workloads](../../scripts/stage_stress.py) · [Benchmark](../../scripts/bench_sfu.py)

On the target 4×4 RTL, using identical workloads within each pair, N-body fell from 116,319 to **103,517** cycles (1.124×), ray tracing went from 44,023 to **44,060** cycles (0.999×), and the cube fell from 69,392 to **68,429** cycles (1.014×). All 16 SMs completed N-body work. Both configurations met the same accuracy checks. The small ray scene shows no throughput gain on 16 SMs. The hardware seed improves local arithmetic but total time also depends on scheduling, instruction fetch and memory contention. These counters alone cannot assign the 37-cycle difference to a single cause. [16-SM counters and output hashes](sfu-workloads-16.json)

### Extension order

| Priority | Candidate and intended ABI | Workload and acceptance criterion |
| --- | --- | --- |
| In software now | Approximate division from `rcp`, positive normal square root from `rsqrt`, Euclidean normalization and integer powers from multiplies | Classify signed zero, infinity, NaN and domain errors in general library routines. Use existing FMA for application-selected refinement. Strict IEEE rounding requires separate validation. |
| Next candidate | Separately discoverable `exp2` and `log2` approximations | Test smooth escape coloring, fog or material shading. Compare error near 1, overflow and FTZ boundaries against a high-precision reference. Use absolute error close to a zero output. Positive-base `pow` composes these operations. |
| Driven by an angular workload | `sin` and `cos` with shared quadrant reduction | Mandelbulb and procedural textures. Measure a shader using both results. Separate single-destination opcodes fit the existing register-file write port. Specify the reduced argument range and handle large arguments in software. Evaluate `atan2` and `acos` in the Mandelbulb library first, including axes and poles. |

PTX exposes reciprocal, reciprocal square root, base-2 log/exp, and trig approximations. The RISC-V vector specification also describes reciprocal and reciprocal-square-root estimates as seeds for refinement. Morganite uses its own encoding, accuracy and FTZ semantics. Prior to allocating further tables per lane, compare one shared pipelined unit per SM against four lane-local units on the same shader. Record issue stalls, register writeback stalls, cycles, LUT/BRAM/DSP deltas and routed slack. [PTX instruction reference](https://docs.nvidia.com/cuda/parallel-thread-execution/#floating-point-instructions-rcp) · [RISC-V V 1.0 estimate instructions](https://github.com/riscvarchive/riscv-v-spec/blob/master/v-spec.adoc#vector-floating-point-reciprocal-square-root-estimate-instruction)

## Topology and graphics

`--grid MxN` controls the SM array and memory tree. IDs are row-major. Each row groups up to four adjacent SMs per arbiter and gets an extra registered arbitration stage when it has multiple clusters. The dispatch mask currently supports 32 SMs. MMIO `0x5c` encodes rows and columns in the upper and lower halves. Register `0x60` encodes five independently selectable features in bits 0 through 4.

| Option | Instruction-level acceleration |
| --- | --- |
| `setup` | Edge coefficients, constant term and top-left bias from signed 16-bit screen coordinates |
| `bins` | Triangle tile bounds and primitive-bit emission for 8×8 tiles. A programmable bin pass emits masks to HBM for up to 32 triangles per draw |
| `raster` | Signed edge evaluation and three-edge coverage at sample centers with four subpixel bits |
| `rop` | Strict ordered binary32 depth comparison and rounded RGBA8 source-over compositing. The pixel-owning thread commits depth and color through the LSU |
| `sfu` | Approximate binary32 reciprocal and reciprocal square root |

The 1×2 all-feature and 2×3 SFU-only configurations passed instruction, capability and fault recovery tests. The all-feature 4×4 board elaborated successfully. The renderer passed all eight scenes at 64×32. The 320×240 cube covered 17,980 pixels, with exact color and depth winners and maximum absolute depth error of 1.79×10⁻⁷. [Scene comparisons](render-64x32.json) · [Full-size cube](render-320x240.json)

## Memory and timing

The GPU coalesces authorized active lanes into 32-byte segments, preserving individual byte strobes. Tests cover all 256 masks. Each endpoint supports eight read and four write IDs. Each SM has two global slots, one scratch slot, 2 KiB each of I/D L1, and two outstanding immutable data misses. The suites include 84 SM/model comparisons, 11,532 endpoint cases and 48 AXI-Lite-submitted GPU jobs across four SM configurations. [SM results](sm.json) · [16-SM jobs](gpu-16.json) · [RTL checks](rtl-checks.txt)

The same 16-SM program and memory model copied 1 MiB in each direction. Before pipelining the fault distribution and segment selection, one and two wide ports required 148,765 and 83,614 cycles. After pipelining, they required 149,098 and 85,431 cycles. At an assumed 100 MHz, the two-port design delivered 1.227 GB/s of useful payload in each direction and a 1.745× speedup. These are RTL cycle measurements. The routed board baseline's critical setup path ran from the registered job fault to SM10 with +0.062 ns slack. Routing contributed 8.553 ns of its 9.534 ns data delay. The worst hold path was in the CPU LSU at +0.007 ns. The remote physical comparison uses revision `14d5252`, before the optional graphics blocks. [Copy program](../../examples/hbm_copy.s) · [Before](copy.json) · [After](copy-pipelined.json) · [Frozen timing paths](../u50c-16sm-20261124/physical/paths.tsv) · [Candidate manifest](timing-inputs.json)

The isolated SFU-only lane routed in Vivado 2019.2 with 190 LUTs, 81 FFs, zero BRAM and zero DSPs. Only its 10 ns clock is constrained. The timing report identifies 37 inputs and 24 outputs without external delays. Integrated area and timing require a complete-design comparison. [Utilization](sfu-ooc/utilization.rpt) · [Timing](sfu-ooc/timing.rpt) · [OOC script](../../scripts/synth_sfu.tcl)

Vivado 2023.2 synthesis for the 16-SM timing candidate yielded the following hierarchy totals. BRAM tiles count one per RAMB36 and half per RAMB18. [Raw synthesis reports](synthesis-reports.tar.gz)

| Hierarchy | LUT | FF | BRAM tile | DSP |
| --- | ---: | ---: | ---: | ---: |
| Aurora CPU (`u_cpu`) | 112,360 | 40,083 | 61 | 8 |
| Morganite GPU (`u_gpu_soc/gpu`) | 653,225 | 225,927 | 256 | 576 |
| SoC (`u50c_top`) | 779,006 | 278,633 | 323.5 | 584 |

### Dual-stack HBM vendor simulation

Vivado 2023.2 CDC and AXI4-to-AXI3 IP accepted 8R/4W and out-of-order responses. A shared byte-addressable endpoint model passed host/GPU visibility tests. The full HBM simulation returned `xxxxxxxx` from GPU address `0x01c01000` after the CPU wrote `55667788`. Moving the host path to stack-local SAXI16 reproduced the failure. A third run initialized one full line on each GPU port and then read back from the writing port. The first even-page read at `0x01c00000` returned `xxxxxxxx` instead of `aabbccdd` before any CPU access. This isolates a single-port reproducer. The bridge signals and model still need diagnosis. Run directories are `morganite-wide-hbm-RcGYZ01b` and `morganite-hbm-localports-jB8uByvO`. The complete timing comparison is in placement. Routed SoC timing and dual-stack board validation are pending. [First simulation](first-hbm-simulation.tar.gz) · [Stack-local host](local-host-hbm.tar.gz) · [Same-port full-line test](initialized-hbm.tar.gz)

## Reproduction

```bash
scripts/test_wide_dma.sh
scripts/test_wide_tree.sh
scripts/test_wide_memory.sh
scripts/test_wide_gpu.sh
scripts/bench_wide_hbm.sh
scripts/test_graphics_units.sh
source scripts/toolchain.sh
python3 scripts/bench_sfu.py build/graphics-units/gpu/obj/VGpu build/sfu-workloads.json
sbt 'runMain morganite.Generate Gpu build/sfu16 4x4 8 4 dmaBits=256 ports=2 reads=8 writes=4 ic=2048 dc=2048 line=8 earlyBranch=1 bramRf=1 tensor=4 setup=1 bins=1 raster=1 rop=1 sfu=1'
"$VERILATOR" --cc --exe --build --assert -j 4 --top-module Gpu --Mdir build/sfu16/obj build/sfu16/Gpu.sv "$PWD/sim/wide_gpu.cpp"
python3 scripts/bench_sfu.py build/sfu16/obj/VGpu build/sfu-workloads-16.json --grid 4x4
python3 scripts/stage_soc.py build/gfx-soc --grid 4x4 --wide-ports 2 --accelerators --units setup bins raster rop sfu
bash linux/build_userspace.sh --units setup bins raster rop sfu
python3 linux/build_kernel.py --feature-register --graphics-registers
```

The Linux CLI checks the shader package's required-unit mask and adds the bin dispatch when enabled. `MG_GRAPHICS` exposes the grid and unit capabilities. The LA32R CLI and driver object were cross-compiled. Board timing, live Linux execution and board frame times are separate measurements.
