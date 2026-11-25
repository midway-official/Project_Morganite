# 16 SM · GPU 程序优化板测

[简体中文](README.md) · [English](README.en.md)

2026-11-25 复用上一轮 U50C 的 16 SM 位流、LTX 和 Linux 镜像。运行号 `20261125T040325.343631Z`. [编程请求及三项哈希](readonly/request.json) · [程序及场景哈希](readonly/stress/suite.json)

| 负载 | 16 SM 整帧周期 | GPU 时间 | 中央 16 行，1→16 SM |
| --- | ---: | ---: | ---: |
| Mandelbrot · 1024×768 | 92,851,096 | 928.51 ms | 15.402× |
| 球体光追 · 512×384 | 4,201,911 | 42.02 ms | 3.922× |

分形将跨子样本常量保留在寄存器，光追复用球体数量。这次提交还声明了不可变参数区，因此测量反映两项变化的共同效果。图块经 UART 回读并与参考逐像素比较。整帧时间为 100 MHz 作业周期之和，不含传输。 [逐作业计数](readonly/stress/results.json) · [曼德博原图](../u50c-16sm-20261124/images/mandelbrot.png) · [原版基线](../u50c-16sm-20261124/README.md)

朱利亚在完成单 SM 中央图块和 11 个 16 SM 图块后中断。Linux 在只读提交期间报告 mgpu 用户态 SIGSEGV，退出码 139，根因未确定。普通提交续测只完成 DMA 回环就按要求停止。此轮没有朱利亚整帧或 N 体性能结果。 [故障日志](readonly/console.log) · [续测日志](plain/opt-plain/console.log)
