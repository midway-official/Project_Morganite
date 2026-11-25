# Aurora + Morganite 16-SM U50C board report

[简体中文](README.md) · **English**

On November 24, 2026, Aurora booted Linux and loaded replaceable GPU programs on the Alveo U50C. CPU and GPU ran at 100 MHz, HBM AXI at 225 MHz. The Vivado 2023.2 bitstream SHA-256 is `b7a470eef97dc1b5b7d352d84475ac159244439ea7a44915ffbf06ccb3312609`. [Hardware manifest](manifest.json) · [Linux image](image.json) · [Programs](programs/suite.json)

## Implementation and board runs

| Routed scope | LUT | FF | BRAM tiles | DSP | URAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| CPU | 105,660 | 40,087 | 61 | 8 | 0 |
| GPU | 300,491 | 136,926 | 256 | 576 | 0 |
| Complete SoC | 417,161 | 185,487 | 321.5 | 584 | 0 |

Worst setup/hold was +0.062/+0.007 ns. TNS, THS and pulse-width violations were zero. All 587,792 routable nets were routed with zero errors. The worst setup path crossed SLRs from the GPU fault register to the SM10 result address; worst hold crossed SLRs in the CPU LSU from its CDB copy to the store queue. DRC reported 1,759 ordinary warnings and zero errors or critical warnings. CDC reported zero unsafe or unknown endpoints. HBM_CATTRIP has no output delay constraint, internal endpoints are constrained. TIMING-9 remained recorded without a waiver. [Resources per SLR](physical/resources.json) · [Timing](physical/timing_impl.rpt) · [Route](physical/route_impl.rpt) · [CDC](physical/cdc_impl.rpt) · [Methodology](physical/methodology_impl.rpt.gz)

1. Run `20261124T140435.335713Z` passed programming, two patterns on each of 16 HBM pseudo-channels and JTAG readback of 15,506,016 image bytes and 508,616 zeroed bytes. Linux ran DMA loopback, 100 consecutive integer/FP32 jobs, cross-SM visibility, concurrent CPU/JTAG/GPU traffic, four stress workloads and eight triangle scenes. [UART](logs/board-01/linux-uart.log.gz) · [Result](results/board-01/uart-result.json) · [Concurrency](results/concurrent.json)
2. Run `20261124T144409.648312Z` booted Linux, identified the GPU and passed loopback. A missing counter device node was created using its sysfs device number. The cache retry was stopped before same-address update verification completed. Between boots the manager initially rejected a request on UART ownership/heartbeat checks. Two actual programming runs occurred. A third boot was never started. Manager, worker and UART bridge were stopped. [Retry UART](logs/board-02/linux-uart-cache-retry.log.gz) · [Completed jobs](results/board-02/jobs.json) · [Manager log](logs/after-build.log.gz)

## Device performance

The full-mask test performed 33,554,432 FMAs across 64 groups. SM0 took 8,521,842 cycles (0.787 GFLOPS); all 16 took 579,391 cycles (11.583 GFLOPS, 90.49% of peak, 14.708×). Outputs matched. The INT8 test performed 134,217,728 MACs in 8,521,757 vs 580,480 cycles (23.122 GMAC/s, 14.681×). An 8 KiB copy read and wrote 8 KiB in 290,148 cycles, equivalent to 2.823 MB/s useful copies and 5.647 MB/s combined payload. These rates include job overhead. [Raw counters](results/performance.json)

| Workload | GPU, full frame or four steps | Linux submit to completion | Server, including UART and comparison | 1→16 SM speedup |
| --- | ---: | ---: | ---: | ---: |
| [Mandelbrot, 1024×768](images/mandelbrot.png) | 20.714 s | 20.756 s | 497.78 s | 1.859× |
| [Julia, 1024×768](images/julia.png) | 20.718 s | 20.760 s | 556.10 s | 1.795× |
| [Ray tracing, 512×384](images/raytrace.png) | 3.374 s | 3.394 s | 224.94 s | 1.035× |
| N-body, 512 bodies | 0.192 s | — | — | 2.139× |

Each fractal evaluated four samples per pixel and up to 192 iterations; every RGBA byte matched. The four-sphere image differed from the CPU reference by at most one channel unit and matched full-frame RTL byte for byte. N-body ran 1,048,576 interactions in four steps, with maximum absolute error 1.19×10⁻⁷ and identical final states. The single-SM run took 40,969,598 cycles; all SMs achieved 5.47 million interactions/s.

Image speedups refer to the same central 16 rows. Full-frame GPU time sums 16-SM tile cycles at 100 MHz. Linux time measures elapsed submission to polling completion. Server time ends after the final tile comparison, before PNG encoding or transfer to WSL. CPU process time was not sampled. [Per-job counters](results/stress.json)

| Central tile | 1 SM cycles | 16 SM cycles | Memory-state fraction, 1 SM / 16 SM |
| --- | ---: | ---: | ---: |
| Mandelbrot | 79,085,693 | 42,530,699 | 54.92% / 99.30% |
| Julia | 76,055,775 | 42,370,112 | 56.66% / 99.19% |
| Ray tracing | 18,745,746 | 18,104,820 | 98.69% / 99.90% |

Each SM completed 32 Mandelbrot groups (512 total); both masks performed 80,098,045 FP lane operations. On 16 SMs, 654,290 read bursts carried 2,627,808 bytes, 4.02 B per burst or 6.18 MB/s over the tile. Ray tracing used 278,905 bursts for 1,124,288 bytes. The memory-state fraction counts send/wait cycles divided by active cycles across SMs; another ready warp can issue during those cycles. The shared 32-bit read path accepts one burst at a time. Ordinary submission leaves the immutable window empty and repeated parameter/palette loads generate short transactions. HBM-side occupancy and a paired cache-on/off tile were not measured in this run. [DMA](../../src/main/scala/morganite/Dma.scala) · [SM counters](../../src/main/scala/morganite/Sm.scala)

| 320×240 scene | Triangles | GPU time | Linux end to end |
| --- | ---: | ---: | ---: |
| Triangle | 1 | 0.436 s | 2.136 s |
| Vertex swap | 1 | 0.437 s | 2.250 s |
| Fragment swap | 1 | 0.431 s | 2.243 s |
| Shared edge | 2 | 0.429 s | 2.244 s |
| Overlap | 3 | 0.397 s | 2.208 s |
| Degenerate | 2 | 0.264 s | 2.062 s |
| Cube A | 12 | 0.530 s | 2.352 s |
| Cube B | 12 | 0.544 s | 2.352 s |

All eight scenes passed coverage, winner, color and depth checks over 76,800 pixels each. Linux end-to-end time includes scene I/O, GPU submission, download and board-side file writes. [Comparisons](results/comparisons.json)

## Replay the archive

Use the hashes in the actual [programming request](results/board-01/request.json). The top-level manifest BIT hash is the original CPU rollback image. The [SoC inputs](artifacts/soc-inputs.tar.gz) include frozen CPU/GPU RTL and Tcl/XDC. The [Linux package](artifacts/linux-board.tar.gz) includes the image and payload hashes. [BIT](artifacts/u50c_top.bit.gz) · [Probes](artifacts/u50c_top.ltx) · [Raw and reference planes](results/frame-buffers.tar.gz) · [RTL ray tracing](images/rtl/raytrace-512x384.png)

Run from this directory with NumPy and Pillow:

```bash
sha256sum --quiet -c SHA256SUMS
scratch=$(mktemp -d)
tar -xzf results/frame-buffers.tar.gz -C "$scratch"
python3 ../../graphics/test_render.py board "$scratch/frames"
```
