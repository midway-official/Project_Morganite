# Programmable workloads on 16 SMs

[简体中文](gpu-stress.md) · **English**

Linux uploads the machine code, parameters and descriptor into the DMA allocation, then starts each job with `mgpu submit`. The three programs share one bitstream. Each upload is read back for a binary comparison. The harness records sequence number, SM mask, groups per SM, FMA counts, device cycles and output data.

| Workload | November 24 board configuration | Reference |
| --- | --- | --- |
| Mandelbrot and Julia | 1024×768, up to 192 iterations, 2×2 samples. The GPU computes FP32 iteration, palette lookup and blending. | Byte-for-byte RGBA8 against independent FP32 reference |
| Sphere ray tracer | 512×384, orthographic projection, four overlapping spheres, closest hit, directional diffuse lighting and hard shadows | CPU intersections use double-precision square root. Tolerance is one level per channel. |
| Softened N-body gravity | 512 bodies, four steps, 262,144 interactions per step. Groups of 16 threads share position and mass in scratchpad and integrate in FP32. | Direct double-precision summation, absolute tolerance 0.000002. Final 1-SM and 16-SM buffers must match byte for byte. |

The 2 MiB allocation accommodates 16 image rows per submission. Run the same central tile with masks 0x0001 and 0xffff, require identical pixels, then run the full frame on 16 SMs. For N-body restart both configurations from the same state and alternate two buffers at completed job boundaries. Check total group count and that each enabled SM finishes work.

```bash
python3 scripts/stage_stress.py build/stress-suite
python3 scripts/test_stress_rtl.py build/stress-rtl/standalone/VGpu build/stress-rtl/scenes
```

The first command stages binaries, references and SHA-256 hashes and runs the assembly model on small inputs. Install NumPy and Pillow and build `build/fp/reference/libreference.so`. The second uses the 16-SM Verilator AXI bench with I/D L1 and the BRAM RF option.

Stage the suite plus `board/uart_stress.py` and `board/uart_jobs.py` in the board workflow. Once the harness owns the fresh Linux UART, run:

```bash
python3 uart_jobs.py "$board_run" --stress-suite "$suite" --full
```

The harness saves HBM readbacks under `$board_run/stress` as raytrace, mandelbrot and julia RGBA/PNG files, plus N-body state and comparisons. Frame cycles sum the device cycles of all 16-SM tiles. Wall time includes UART, uploads, polling and comparison. WSL reference PNGs end in `-reference.png`.
