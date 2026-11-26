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

## 布局结果

北京时间 20:49:22 完成布局，20:52:31 进入布线。布局结束时 Vivado 估计 setup WNS 为 +0.550 ns。CPU 和 GPU 的分层数字见上方综合结果。下表来自同一构建的整机布局报告。[Vivado 布局后原始报告](utilization-placed.rpt)

| 区域 | LUT | LUT 占比 | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| SLR0 | 380,942 | 86.64% | 180,240 | 148.5 | 0 | 1,044 |
| SLR1 | 388,126 | 89.84% | 163,715 | 173 | 0 | 820 |
| 整 SoC | 769,068 | 88.23% | 343,955 | 321.5 | 0 | 1,864 |

## 最终位流与物理报告

Vivado 于北京时间 2026-11-26 23:13:41 完成位流生成。23:31 构建脚本退出，返回码为 0。下表取自 `hierarchy_impl.rpt` 的布线后层次统计。GPU 核属于 GPU 板级模块，两行的资源存在包含关系。

| 布线后范围 | LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU（`u_cpu`） | 105,316 | 40,075 | 61 | 0 | 8 |
| Morganite GPU 核（`u_gpu_soc/gpu`） | 651,988 | 290,910 | 254 | 0 | 1,856 |
| 含板级接口的 GPU（`u_gpu_soc`） | 653,485 | 291,019 | 254 | 0 | 1,856 |
| 整 SoC | 769,068 | 343,955 | 321.5 | 0 | 1,864 |
| 冻结 CPU 基线 SoC（`5ec35882`） | 112,771 | 48,279 | 65.5 | 0 | 8 |

1,068,825 条可布网络全部完成布线，布线错误为 0。全设计 setup/hold 为 +0.100/+0.010 ns，TNS、THS 及脉宽失败端点均为 0。下表为各时钟域内最差裕量，完整报告还覆盖跨时钟路径。[冻结基线报告](../../baseline/rollback/remote-evidence.tar.gz) · [本次实现归档](implementation.tar.gz)

| 时钟域 | 基线 setup / hold（ns） | 本次 setup / hold（ns） |
| --- | ---: | ---: |
| CPU/GPU 100 MHz | +1.838 / +0.010 | +0.100 / +0.010 |
| HBM AXI 225 MHz | +1.213 / +0.019 | +0.648 / +0.010 |
| 板级参考时钟 sysclk3 | +6.486 / +0.010 | +4.376 / +0.013 |
| 调试 TCK | +11.673 / +0.019 | +11.247 / +0.010 |

DRC 有 5,281 条普通警告，其中 5,272 条涉及 DSP 流水寄存器，8 条涉及 LUT 方程，1 条涉及无可布负载。错误和关键警告均为 0。CDC 汇总的 166 个端点均为 safe。约束检查中未约束内部端点为 0，板级 `HBM_CATTRIP` 缺少输出延迟。方法学报告另列 TIMING-9 等检查项，具体时钟例外和受影响单元见同一归档的 `exceptions_impl.rpt` 与 `methodology_impl.rpt`。

Vivado 布线功耗报告估计芯片总功耗 **26.755 W**，其中 FPGA 为 16.847 W、HBM 为 9.908 W，置信度为 Medium。报告中 Vccint_io 为 7.445 A（预算 7.380 A），VCC_IO_HBM 为 4.584 A（3.840 A），VCC_HBM 为 4.919 A（4.160 A）。这些是工具估算值，该位流的实板电流、Linux 启动、HBM 读回与图形测试均待测。

运行 `sha256sum -c SHA256SUMS` 可校验本地归档。解压后的新 BIT 的 SHA-256 为 `fe12b419b028d031f5a61e4f3c7f8c7b688399a73a744d613905cdb5c1e192bd`，对应 LTX 为 `2cc13d4e7a8ff29d4df5feb449363702a3971c4a03c4fe0facfb57c8d87025bc`。Chisel 在 WSL 生成 CPU/GPU RTL，FPGA 构建服务器接收生成的 Verilog/SystemVerilog、板级封装、Tcl/XDC、板卡定义和构建清单。[位流](artifacts/u50c_top.bit.gz) · [配对探针](artifacts/u50c_top.ltx) · [本次实际上传包](artifacts/soc-inputs.tar.gz)

## Linux 驱动与程序对照

MG_STATE 每次轮询读取一个状态寄存器。CLI 在作业结束时再取完成序号、fault 和计数器，旧驱动可用原轮询方式。顶点阶段将参数和顶点设为只读，设置阶段缓存裁剪后顶点与索引，分箱阶段缓存边界，像素阶段缓存三角形记录与 tile 掩码。各作业的输出均在其只读窗口之外。像素程序展开读取同一工作组四个 tile 的掩码，跳过对整组无贡献的三角形，省去对应的 128 B 读取和两次 barrier。

在同一个 16 SM Verilator 模型上运行了带 24 个三角形的重叠双立方体。320×240 两组都开只读数据 L1。两组颜色、深度和图元编号完全相同，13,907 个像素被覆盖，逐像素深度最大绝对误差为 1.19×10⁻⁷。时间由 GPU 周期按 100 MHz 换算。RTL 的两个 AXI 端口使用带背压的存储模型。[逐阶段计数与对照](rtl/raster.json) · [颜色图](rtl/twin-cubes.png) · [深度预览](rtl/twin-cubes-depth.png)

| 场景与程序 | GPU 周期 | 100 MHz 等效帧时 | 两个主口合计读取的 32 B 拍数 |
| --- | ---: | ---: | ---: |
| 320×240 双立方体，纯软件图形管线 | 2,947,199 | 29.472 ms | 215,971 |
| 320×240 双立方体，图形指令与工作组剔除 | 551,029 | 5.510 ms | 78,088 |

双立方体的帧周期比为 **5.35×**，读拍减少 **63.8%**。另做了 64×32、数据 L1 旁路的消融对照。纯软件用了 103,327 周期，启用硬件图形指令但仍逐个遍历全部三角形用了 113,675 周期，增加工作组剔除后为 66,572 周期。

128×96 光追发出 12,288 条主光线，对 20 个球做最近命中选择，并为受光交点计算阴影。保持球表和像素布局相同，软件平方根为 220,616 周期（2.206 ms），SFU 加一次 Newton 修正为 219,395 周期（2.194 ms），加速 **1.006×**。两者相对 CPU 双精度参考的最大 RGBA8 通道误差均为 1。[光追图](rtl/ray.png) · [光追计数](rtl/ray.json)

实板包中另含 256×192 的 20 球光追和相应参考。Linux 镜像 SHA-256 为 `37843c6c4f759ec99d05045363a449771b282b65f07fdc099fedf768ff0f72b0`，BSS 清零区间为 `[0x01200000, 0x0127c2c8)`。已在远端核对完整软件包，并用 JTAG 扫描识别 U50C。此处的帧时来自 RTL，尚待该镜像的 UART 启动和实板测量。[Linux 镜像、驱动与程序包](artifacts/linux-board.tar.gz)

```bash
bash linux/build_userspace.sh --units setup bins raster rop sfu --software-assets
python3 linux/build_kernel.py --feature-register --graphics-registers --initramfs-compression none
python3 scripts/stage_software.py build/linux-gfx --sms 16 --gpu-features 0x403
```

远端按实际路径运行 `python3 software/start_test.py build software output` 和 `python3 software/uart_jobs.py output --paired-graphics --full`。

布线后的最差 setup 路径从 GPU 描述符寄存器跨 SLR0→SLR1 到 DMA 段请求状态机，12 级逻辑，数据路径延迟 9.395 ns。最差 hold 路径在 HBM 桥 AXI4→AXI3 写地址队列内。每 SLR 的 CPU、GPU 及整机利用率保存在同一 DCP 的统计中。[路径与约束](implementation.json) · [各 SLR 资源](resources.json)

寄存器驻留基准使用 16 组 × 32 线程、16 个累加器及 16 倍循环展开。在 RTL 上，单 SM 的 FP32 为 0.788 GFLOPS，16 SM 为 **12.513 GFLOPS**（按 100 MHz 换算，为峰值的 97.76%）。相同工作量加速 15.87×。INT8 K=4 的 16 SM 等效吞吐为 **50.051 GOPS**，一个标量 MAC 计两次运算。512 体单步 N-body 为 247,957 周期，最大绝对误差 1.12×10⁻⁸。旧对照程序用 16 线程启动本次 32 线程 tile 的 N-body 内核时结果出错，按内核 ABI 改为每组 32 线程后通过。[计算计数与逐线程结果校验](rtl/compute.json)

远端旧项目和本次实现共清除 9,139 个文件，释放 12.63 GiB。371 份保留文件前后哈希一致。包括新 Linux 软件包在内，复扫 12 个项目目录及保留归档、分片。BIT、LTX 和内核沿用记录的路径。[清理清单和保留文件哈希](remote-cleanup.json)
