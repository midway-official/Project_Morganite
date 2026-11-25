# 16 SM GPU kernel optimization run

[简体中文](README.md) · [English](README.en.md)

The November 25 run reused the previous 16-SM U50C bitstream, probes and Linux image. Run ID `20261125T040325.343631Z`. [Programming request and artifact hashes](readonly/request.json) · [Program and scene hashes](readonly/stress/suite.json)

| Kernel | 16 SM frame cycles | GPU time | Central 16 rows, 1→16 SM |
| --- | ---: | ---: | ---: |
| Mandelbrot · 1024×768 | 92,851,096 | 928.51 ms | 15.402× |
| Ray tracing · 512×384 | 4,201,911 | 42.02 ms | 3.922× |

The fractal kernel keeps constants in registers across samples. The ray tracer reuses its sphere count. Both submissions also declare immutable parameters, so the measurements include both changes. Tiles were read back over UART and checked against reference pixels. Frame time sums device cycles at 100 MHz and excludes transfers. [Job counters](readonly/stress/results.json) · [Mandelbrot frame](../u50c-16sm-20261124/images/mandelbrot.png) · [Earlier baseline](../u50c-16sm-20261124/README.en.md)

Julia completed its single-SM central tile and 11 tiles on 16 SMs. Linux then reported a userspace SIGSEGV in mgpu during read-only submission (exit 139). The cause is unresolved. The ordinary-submission retry was stopped after DMA loopback. This run has no complete Julia or N-body measurement. [Failure log](readonly/console.log) · [Retry log](plain/opt-plain/console.log)
