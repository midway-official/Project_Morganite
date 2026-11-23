# U50C Linux boot and GPU evidence

The `boot-NN` directories use the routed 2-SM Morganite BIT
`0acedd3bc9b6f4aaa0ee56d1426dd6614723d7e5bf6a76dbea12148bfa3d9fc0`
and the same frozen Aurora RTL. Each run's `request.json` identifies the
programmed BIT, LTX and Linux image. Its `launch.json` identifies the actual
board manager run. `build.json` also contains the historical rollback hashes.

Boot 01 reached a shell but the reserved DMA pool failed to register. Boot 02
stopped inside early workqueue allocation with the earlier Linux direct-window
implementation. Boot 04 stopped in the Vivado Tcl verifier on the unsupported
`binary encode` command while the CPU was held. Boot 05 ran the corrected
verifier. See [pool probe](boot-01/linux-uart.log), [early kernel panic](boot-02/linux-uart.log)
and [loader error](boot-04/manager/load.log).

## Confirmed Linux address conversion fault

Boots 03 and 05 faulted at the same virtual address `0xc774d708` and EPC
`0xa0a7fe78` in `tcp_write_queue_purge`. Boot 05 compared the entire loaded
image and BSS over JTAG with the CPU held in reset. The Linux TLB refill
handler masked both physical page-table walk addresses with `0x0fffffff`.
Aurora's direct window needs `0x1fffffff`. A table above 256 MiB could
therefore be read 256 MiB below its actual location.

Boot 06 used the same BIT. A comparison of all decoded RAM segments with boot
05 shows a single changed bit in the loader image at physical byte
`0x0030946e`. This changes the instruction at `0x0030946c` from
`0x141fffe1` to `0x143fffe1`. Linux then passed shell, compute and graphics
tests. In the LA32R instruction model, zero-filled RAM can mask the original
bug, whereas filling unused RAM with `0xa5` exposed a bad page-table walk.
Both fill patterns reached userspace with the fix. See
[`tlb-control`](tlb-control), [boot 05 UART](boot-05/linux-uart.log),
[boot 06 results](boot-06/uart-result.json) and the
[source fix](../../../linux/la32r-tlb.patch).

## Intermittent initramfs failure

Boots 07, 08, 10 and 11 used the same gzip image
`fa113a83bcad01fc08227855312358af28eff0858659f31db31a25349f90645b`.
Boot 07 panicked before the GPU driver printed its probe result. Boots 08
and 10 passed the full suite, and boot 11 passed shell, GPU identification and
DMA loopback. Boot 09 separately passed the full suite with a plain cpio image.
These are fresh FPGA programming and CPU reset sequences. The historical
boot 07 failure remains in the count.

The LA32R kernel's `init/initramfs.c` passed a `va_list` to the variadic
`panic(fmt, args)`. With the format `"%s"` this printed the four bytes of
the error pointer. The raw UART bytes `c4 d0 c8 a0` resolve to
`0xa0c8d0c4` in the exact boot 07 image, which contains
`junk within compressed archive`. This independently explains the
garbled UART error. See [boot 07 decode](boot-07/initramfs.json).

Boot 12 used the [trace patch](initramfs-trace.patch). The embedded gzip
member was byte-identical to boot 07 (SHA-256
`808e854f61af5f50016d216da127e14f392a4572df130db3efa48386426012d2`).
The CPU computed the CRC of the compressed input after the unpack attempt
through both the cached and uncached aliases. The flush callback computed a
rolling CRC over the decompressor output before passing each block to cpio.

| Measurement | WSL reference | Boot 12 on U50C |
| --- | ---: | ---: |
| Input CRC-32, 1,818,978 bytes | `1e28d3b4` | `1e28d3b4` through both aliases |
| Output CRC-32 for the 3,473,408 bytes delivered before stopping | `c5529353` | `01cc675e` |
| Byte at cpio offset 3,463,740, where parsing stopped | `0x30` | `0x38` |
| Expected complete cpio | 3,474,432 bytes, CRC-32 `0bc6d138` | Parser aborted |

The exact boot 12 image in NEMU delivered all 3,474,432 bytes with CRC-32
`0bc6d138`. A model-only negative control put `0x5a` in the first archive's
alignment padding. The probe reported the injected byte at offset 2,428,580.
These runs use the LA32R instruction model with a probe-only GPU MMIO stub.
See [board comparison](boot-12/initramfs.json), [board UART](boot-12/manager/uart.log)
and [model controls](model-control).

The first measured data discrepancy is in CPU-produced decompressor output.
The compressed input matched the host at both JTAG load verification and the
post-failure CPU checks. These observations leave transient reads, CPU
execution or cache behaviour, and timing-sensitive kernel behaviour open.
The board runs had submitted zero GPU jobs at the time of the panic.

Boot 13 ran the [isolated inflate replay](inflate-replay.patch) before the
normal cpio/VFS path. It reused two preallocated buffers for four passes
through the kernel's raw DEFLATE core. It initialized the workspace and
output before every pass and accumulated CRCs over each 32 KiB output block.
The same image in NEMU produced the expected CRC in all passes and answered a
command at the Linux shell.

| Boot 13 on the 2-SM SoC | Workspace fill | Timer interrupts | Complete output CRC-32 |
| --- | --- | --- | --- |
| Standalone pass 0 | `00` | enabled | `d1567745` (mismatch) |
| Standalone pass 1 | `00` | masked | `0bc6d138` |
| Standalone pass 2 | `a5` | enabled | `0bc6d138` |
| Standalone pass 3 | `a5` | masked | `0bc6d138` |
| Normal kernel unpack | kernel allocator | enabled | `760b193b` (mismatch) |

All four isolated passes returned Z_STREAM_END with the correct output length.
Boot 13 reached a shell and passed the GPU DMA smoke test even though its normal
unpack CRC failed. Its integrity result is **failed**. Interrupt masking and pass
order changed together, so these four measurements do not identify the interrupt
handler as the root cause. See [board comparison](boot-13/initramfs.json)
and [identical-image NEMU UART](model-control/replay-uart.log).

Run 14 repeated the exact boot 13 image on the original CPU-only rollback BIT
`8bc4c4fffeb9c4d59b557a994e1a7784f48daff0d990d78240f185b37e3c246e`.
It passed the 16-channel HBM diagnostic and the held-reset JTAG comparison of
13,848,936 image bytes and 508,616 BSS bytes. All four isolated inflate passes
produced 3,474,432 bytes and returned Z_STREAM_END. Their CRCs were
`8ee932b7`, `7ed8ea89`, `93a9574c` and `0bc6d138` in the same order as
the table above. One mismatch occurred while local interrupts were masked.
The normal unpack produced a correct 2,776,052-byte prefix and then stopped
with `uncompression error`. The CPU subsequently read the correct compressed
input CRC through both direct mappings.

The data corruption reproduces on the existing Aurora/U50C platform with the
entire Morganite integration removed. The CPU execution and data-memory path
are the leading hardware suspects. Identifying a specific CPU or cache RTL
error, fabric/HBM transaction, or kernel interaction requires the first
divergent instruction or memory access. A post-run input CRC cannot reveal
the value of an individual transient load. See [CPU-only result](cpu-only-14/initramfs.json)
and [request with BIT and image hashes](cpu-only-14/request.json).

## Halfword-store reproducer

The Linux probe extracts the instruction order from the kernel's
`lib/zlib_inflate/inffast.o` match-copy loop. Two byte loads construct a
halfword followed by an aligned `st.h`. Later iterations can read the bytes
written by earlier iterations. Each case initializes the destination to
`0xa5`, seeds a known pattern and checks the output against a separately
calculated sequence. A passing run validates 9 MiB across twelve distances,
six alignments and four patterns. The ELF is single threaded and its test
loop has no system calls. See [assembly](../../../board/inflate_copy_probe.S)
and [oracle](../../../board/inflate_copy_probe.c).

On boot 15 the original 1,496-byte ELF first failed with distance 5, offset
126 and output index 22,176. The expected byte was `0xee`, the observed value
was the initialization byte `0xa5`. The identical ELF passed in NEMU.
On the same board boot, four additional interleaved trials gave the following
results. Every mismatch retained the initialization byte. The NOP and DBAR
controls place a single instruction at the same program location following
the store.

| Loop variant | U50C matches out of four runs | NEMU |
| --- | ---: | --- |
| Original | 0 | pass |
| One `nop` after every `st.h` | 0 | pass |
| One `dbar 0` after every `st.h` | 4 | pass |

The execution of a single-threaded, overlapping copy has an observable
read-after-write dependency. The consistent effect of a barrier focuses
investigation on store visibility and the cached load/store path. The logs
record the value as checked after the copy. They do not distinguish a lost
store, a stale forwarded load subsequently propagated by the loop, or an
incorrect read during verification. Sources, binaries, independent model logs
and board measurements are retained. See [original UART](boot-15/copy-probe-uart.log),
[interleaved results](boot-15/copy-matrix/results.json) and [ELF hashes](copy-probe/binaries.json).

## Tested GPU configuration

Boot 10 recorded 130 completed kernel launches with zero GPU faults, including
100 consecutive integer/FP32 jobs and a transfer from SM0 to SM1 across two
job boundaries. The tested driver used the fixed, reserved 2 MiB coherent pool
at `0x1f800000`, mapped uncached at `0x9f800000`. The general-purpose DMA
allocation experiment in the development checkout is a separate configuration.
See [jobs](boot-10/jobs.json) and [Linux memory map](boot-10/linux-resources.txt).

Eight 320x240 board scenes passed the independent CPU comparison. Across
614,400 pixels, coverage and depth winner matched exactly. The largest RGBA8
channel difference was one code and the largest absolute FP32 depth difference
was `1.7881393432617188e-7`. The fixed-work FMA test measured 1.3233 GFLOPS
and 1.9979x for two enabled SMs compared with one enabled SM on the same BIT,
at 100 MHz. The copy kernel moved 5.645 MB/s of read-plus-write payload.
See [board frames and comparisons](boot-10/frames),
[counters](boot-10/performance.json) and [RTL images](../../graphics/rtl-320x240).

The exact GPU programs and packed scenes for these runs are saved under
[`programs`](programs). Their SHA-256 values are in each run's
`software.json`. Two cube views took 636.1 and 648.3 ms of GPU time and
2.434 and 2.439 s in the Linux render command, respectively, on boot 10.

Boot 15 restored the 2-SM design and used the plain cpio kernel from boot 09.
Linux SHA-256 values for the host CLI, every GPU program and every scene
matched the staging manifest before submission. It ran 130 GPU launches and
passed all eight image comparisons. See [installed payload hashes](boot-15/payload-sha256.json)
and [board frames with reference results](boot-15/frames).

| Routed hierarchy | CLB LUT | FF | BRAM tiles | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU | 104,716 | 40,037 | 61 | 0 | 8 |
| Morganite GPU | 40,305 | 15,287 | 16 | 0 | 40 |
| Full SoC including board fabric | 153,478 | 63,792 | 81.5 | 0 | 48 |

Whole-design routed WNS was +1.158 ns and WHS +0.010 ns. TNS and THS were
zero. See [per-SLR resources](../../resources/f2a3ce0-route/summary.json) and
[timing summary](../../physical/f2a3ce0-route/timing_impl.rpt).

## Reproduce the comparisons

From the GPU checkout, using the immutable software packages named in each
board request, these commands decode the panic pointer and compare the
diagnostic output with the host. The boot 12 command exits 1 on its recorded
output mismatch.

```sh
python3 board/check_initramfs.py build/sw-fbe17ff reports/bringup/f2a3ce0/boot-07
python3 board/check_initramfs.py --require-trace build/sw-gzip-trace reports/bringup/f2a3ce0/boot-12
python3 board/check_initramfs.py --require-replay build/sw-inflate-replay reports/bringup/f2a3ce0/cpu-only-14
python3 graphics/test_render.py board reports/bringup/f2a3ce0/boot-10/frames
python3 graphics/export_png.py reports/bringup/f2a3ce0/boot-10/frames reports/bringup/f2a3ce0/boot-10/frames/comparisons.json reports/bringup/f2a3ce0/boot-10/frames
```
