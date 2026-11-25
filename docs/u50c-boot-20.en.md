# Boot 20 on the Alveo U50C

[简体中文](u50c-boot-20.md) · **English**

Tag `u50c-boot-20` identifies one complete programming and Linux boot, manager run `20261123T203810.557293Z`. The transcript, counters and frames are from that run. [Request](../reports/bringup/f2a3ce0/board-test-20/request.json) · [Manager](../reports/bringup/f2a3ce0/board-test-20/manager) · [Result](../reports/bringup/f2a3ce0/board-test-20/uart-result.json)

## Revisions and memory

| Component | Commit or SHA-256 |
| --- | --- |
| Aurora CPU and board evidence | 5ec35882a6eb4040631c1cf9d322a754bd435907 and c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad |
| GPU and board wrapper | f2a3ce064e4149b14a4acef1e662685f5d8bc17e |
| Driver, CLI and packaging | f27299ce9489877c7dec09ecae1c0438c684f99f |
| LA32R Linux, plus local patches | fbd87836058ae5e676bf97463076916a18c94ad7 |
| Aurora.v | 82c673147ce734b2662b3dc5e740377913c594f039102a1c83033a2440d409f1 |
| MorganiteBoard.sv | 09ca005e7fa98c0ff462e797cf494f5f0d56460c1eed2942020056c3636615dc |
| Programmed BIT | 0acedd3bc9b6f4aaa0ee56d1426dd6614723d7e5bf6a76dbea12148bfa3d9fc0 |
| Matching LTX | 7fc80573b73bdeb17e362bdc449c5d55cbdcaa54844c146a92ed1753e7320d88 |
| Loaded ram.dat | dbe0e2ae8dc25eb33835f53de85cd5ce981954d26746816763c776bf4f07fa7c |

The frozen CPU project contains 32 synthesizable Scala files. Separate Aurora and Morganite builds reproduced the archived RTL byte for byte. The programming request identifies the BIT actually loaded. The rollback BIT in the build manifest belongs to the CPU baseline. [CPU](../cpu/aurora) · [Hardware](../reports/bringup/f2a3ce0/board-test-20/build.json) · [Software](../reports/bringup/f2a3ce0/board-test-20/software.json)

Each of two SMs has four warp slots, 32 registers per thread and 8 KiB scratchpad. CPU and GPU share a 32-bit AXI4 HBM bridge. DMA is non-snooping. Linux polls sequence completion at the AXI4-Lite page starting at 0x1fe20000. Probe programs the device window from dmam_alloc_coherent. LA32R clears the cached allocation, cleans and invalidates D-cache and returns the uncached mapping. This run allocated [0x01c00000,0x01e00000), CPU alias 0x81c00000. The host queries the driver each time. [DMA patch](../linux/la32r-dma.patch) · [Mapping](../reports/bringup/f2a3ce0/board-test-20/dma-mapping.json)

## Board results

- JTAG verified two patterns of 128 words on each of 16 HBM pseudo-channels and checked 15,503,424 image bytes plus 508,616 zeroed bytes before CPU release. Linux entered a loongarch32r shell. CLI, programs and scenes matched their hashes; GPU reported ABI 1.0 and two SMs.
- Sequences 1–130 completed without hardware faults. After loopback, 50 integer and 50 FP32 AXPY jobs each checked 256 results. SM0 produced eight groups on job 102, SM1 consumed them on job 103. CPU activity and independent JTAG reads/writes at 0x20101000 overlapped job 105.
- For 8,388,608 FMAs, one SM took 2,532,830 cycles and two took 1,267,754 cycles (1.323381 GFLOPS, 1.997888×). Aggregate copy traffic was 5.460 MB/s. Median host/device times were 517.835/18.310 µs for integers and 4,013.605/562.305 µs for AXPY.
- All 614,400 pixels across eight frames matched coverage and depth winner. Maximum channel error was 1, maximum absolute depth error 1.78814×10⁻⁷. Shared edges, depth ties and degenerate cases matched exactly. Two 12-triangle cube views took 600.524/610.229 ms on the GPU and 2.390/2.395 s end to end. Raw color, depth, winner and CPU reference planes accompany the PNGs. [Frames and comparisons](../reports/bringup/f2a3ce0/board-test-20/frames) · [Counters](../reports/bringup/f2a3ce0/board-test-20/jobs.json)

Boot 20 verified the dynamic allocation image once. Boots 09, 15 and 18 independently programmed the same BIT with the earlier fixed-pool image. A prior TLB refill mask error was traced to Linux. Intermittent decompression corruption also appeared on a CPU-only BIT; its root cause remains unresolved. [Diagnosis](../reports/bringup/f2a3ce0/diagnosis.md)

| Post-route scope | LUT | FF | BRAM tiles | DSP |
| --- | ---: | ---: | ---: | ---: |
| CPU | 104,716 | 40,037 | 61 | 8 |
| GPU | 40,305 | 15,287 | 16 | 40 |
| SoC | 153,478 | 63,792 | 81.5 | 48 |
| CPU baseline SoC | 112,771 | 48,279 | 65.5 | 8 |

These resources are in SLR0 with zero URAM. On xcu50-fsvh2104-2-e worst setup/hold slack was +1.158/+0.010 ns. The 100 MHz domain achieved +2.090/+0.010 ns and 225 MHz +1.158/+0.019 ns. TNS, THS, pulse-width failures, route errors and DRC errors were zero. Reports cover 151 DRC warnings, CDC and the HBM_CATTRIP external output exception. [Physical reports](../reports/physical/README.md)

## Replay the release

From a checkout of tag `u50c-boot-20`, verify and unpack the release. The hardware package includes both RTL files, board Tcl/XDC and board definitions. The software package includes Linux, programs, scenes and manager scripts.

```bash
rel=releases/u50c-boot-20
out=build/replay-boot-20
mkdir -p "$out/soc" "$out/sw"
(cd "$rel" && sha256sum -c SHA256SUMS)
tar -xzf "$rel/soc-inputs.tar.gz" -C "$out/soc"
tar -xzf "$rel/linux-board.tar.gz" -C "$out/sw"
(cd "$out/soc" && sha256sum --quiet -c files.sha256)
(cd "$out/sw" && sha256sum --quiet -c files.sha256)
gzip -dc "$rel/u50c_top.bit.gz" > "$out/u50c_top.bit"
sha256sum "$out/u50c_top.bit" "$rel/u50c_top.ltx" "$out/sw/ram.dat"
```

Reproduce RTL from that tag using CPU sbt 1.9.7 / Scala 2.13.12 / Chisel 3.6.1 and the GPU toolchain. The two cmp commands matched in WSL.

```bash
cp -a cpu/aurora "$out/aurora-src"
(cd "$out/aurora-src" && sbt 'runMain aurora.AuroraMain ../cpu-rtl')
cmp "$out/cpu-rtl/Aurora.v" "$out/soc/inputs/generated/Aurora.v"
source scripts/toolchain.sh
sbt "runMain morganite.Generate MorganiteBoard $out/gpu-rtl 2 8 4"
cmp "$out/gpu-rtl/MorganiteBoard.sv" "$out/soc/fpga/u50c/rtl/MorganiteBoard.sv"
```

The LA32R shell exposes /dev/morganite and the mgpu CLI.

```sh
/bin/mgpu info
/bin/mgpu loopback
/bin/mgpu selftest 100
/bin/mgpu render /gpu/cube_a.scene /tmp/frame
```

Rebuilding Linux requires the cross toolchain, pinned Linux commit, Aurora firmware and base initramfs. Set --source and --prefix to override local defaults. Boot 20 uses uncompressed cpio; use archived ram.dat for exact replay.

```bash
git -C /home/midway/Aurora_AXI worktree add --detach "$PWD/.worktrees/aurora" c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad
bash linux/build_userspace.sh
python3 linux/build_kernel.py --initramfs-compression none
python3 scripts/stage_software.py --verify-load build/sw-rebuilt
```

On the server unpack the SoC inputs to a new directory and run its bash run_soc.sh with Vivado 2023.2. From the software package use start_test.py with build, software and new result directories, then uart_jobs.py with the result directory and --full. These check BIT/LTX/image, program, test HBM/peripherals, load Linux, submit jobs and retrieve frames. launch.json identifies the actual manager directory. [Board entry point](../board/start_test.py) · [UART harness](../board/uart_jobs.py)
