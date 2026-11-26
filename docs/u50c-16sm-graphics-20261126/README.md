# U50C 16 SM 全图形配置的综合与布线

[English](README.en.md)

GPU 源码为 `e46aeee8333676544123c8a3f5f5155b6f2af58f`。独立工作树生成了 4×4 SM 的完整 SoC。每 SM 有 4 条物理 lane，共实例化 64 个 GraphicsLane。三角形设置、分箱、光栅、ROP 和 SFU 的能力位掩码为 `0x1f`。SFU 支持 FP32 近似倒数和倒平方根。CPU/GPU 目标频率为 100 MHz，两个原生 256 位 GPU AXI4 主口连接 225 MHz HBM 接口。[配置和哈希](inputs.json)

| 子系统 | 本次实现 |
| --- | --- |
| 计算 | 每 SM 有 4 个驻留 warp，每 warp 8 线程，满掩码指令分两拍送入 4 条物理 lane。全 GPU 最多驻留 512 个线程。支持 FP32 FMA 和有符号 INT8 矩阵乘累加，后者每组 4 条 lane 计算 2×2 输出块，K=4，使用 32 位累加器。 |
| 寄存器与片上存储 | 每线程 32 个 32 位寄存器，开启 BRAM 寄存器堆选项及提前分支。每 SM 有 8 KiB scratchpad、2 KiB 指令 L1、2 KiB 只读数据 L1，缓存行 32 B。数据 L1 只缓存指定的不可变区域，可写数据走旁路。 |
| 访存 | 每 SM 有两个全局 LSU 槽和两个数据 L1 缺失条目。四簇各聚合 4 个 SM，通过树形 NoC 访问双端口，每口最多 8 笔读和 4 笔写在途。DMA 窗口按 4 KiB 页交错。GPU 走 HBM SAXI01 和 SAXI17，CPU/JTAG 共用 SAXI00，控制走 32 位 AXI4-Lite。 |
| 图形 | GPU 程序按需发出三角形边系数和 top-left 偏置、8×8 像素分箱、边函数与覆盖、FP32 深度比较、RGBA8 alpha 混合，以及近似倒数和倒平方根指令。图元遍历及最终写回由程序控制。 |

100 MHz 时，64 条 lane 全部每拍接受一条 FP32 FMA 的算术上限为 **12.8 GFLOPS**。两个 256 位主口同时满载的单向数据上限为 **6.4 GB/s**。该版本的实板吞吐和渲染时间待测。双栈通路在厂商 HBM 仿真中仍有同端口写后读返回全 X 的记录。[引脚握手记录](../16sm-wide-hbm-rtl-20261126/pin-handshakes.txt)

远端目录为 `/home/fpga/morganite-16sm-graphics-e46aeee-qQitZVZs`。Vivado 2023.2 于北京时间 2026-11-26 19:08:59 启动，器件为 `xcu50-fsvh2104-2-e`。CPU 与 GPU RTL 均通过远端 SHA-256 核对。旧时序对照构建在拆线重布期间按要求终止，留下 opt、placed 和 physopt 三个 DCP。[运行状态](status.json) · [旧构建停止记录](previous-build-stopped.json)

在该提交的干净工作树中生成上传包，进入包目录后，在装有 Vivado 2023.2 的构建服务器上执行第二条命令。

```bash
python3 scripts/stage_soc.py build/soc-gfx --grid 4x4 --wide-ports 2 --accelerators --units setup bins raster rop sfu
bash run_soc.sh
```

## 综合结果

| Hierarchy | LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU | 112,056 | 40,080 | 61 | 0 | 8 |
| Morganite GPU | 712,789 | 290,794 | 254 | 0 | 1,856 |
| SoC | 836,769 | 343,496 | 321.5 | 0 | 1,864 |

64 个 GraphicsLane 实例合计使用 43,152 个 LUT、64,640 个 FF 和 1,280 个 DSP，已计入 GPU 行。U50C 综合 LUT 利用率为 95.99%。[综合报告原件](synthesis.tar.gz) · [分层资源数](synthesis.json)

按 GPU 核心的直属实例统计，16 个 SM 共 388,364 个 LUT（54.5%），宽口 DMA 与仲裁为 206,503 个（29.0%），16 份数据 L1 及其控制逻辑共 116,194 个（16.3%）。其余 1,728 个用于 GPU 顶层及控制口队列。图形 lane 包含在 SM 内。
