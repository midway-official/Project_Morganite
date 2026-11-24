# Aurora + Morganite 16 SM 实板测试报告

2026-11-24 在 Alveo U50C 上验证 Aurora Linux 加载可替换 GPU 程序。CPU 和 GPU 工作在 100 MHz，HBM AXI 工作在 225 MHz。Vivado 2023.2 生成位流，SHA-256 为 `b7a470eef97dc1b5b7d352d84475ac159244439ea7a44915ffbf06ccb3312609`。首轮板卡管理器运行号为 `20261124T140435.335713Z`。[硬件清单](manifest.json) · [Linux 镜像](image.json) · [GPU 测试程序](programs/suite.json)

## 物理实现

| 布线后范围 | LUT | FF | BRAM tile | DSP | URAM |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU | 105660 | 40087 | 61 | 8 | 0 |
| Morganite GPU 核 | 300491 | 136926 | 256 | 576 | 0 |
| 完整 SoC | 417161 | 185487 | 321.5 | 584 | 0 |

Setup WNS 为 +0.062 ns，hold WHS 为 +0.007 ns，TNS、THS 和脉宽违例均为零。587792 条可布线网络全部完成布线，路由错误为零。最紧 setup 从 GPU 顶层故障寄存器跨 SLR 到 SM10 的结果地址寄存器；最紧 hold 从 CPU LSU 的 CDB 副本跨 SLR 到 store queue。各 SLR 的资源统计随附。[物理资源](physical/resources.json) · [时序](physical/timing_impl.rpt) · [路径归属](physical/paths.tsv) · [路由](physical/route_impl.rpt)

布线后 DRC 有 1759 条普通警告，错误与关键警告均为零。CDC 汇总中 unsafe 和 unknown 端点均为零。约束检查列出一个无输出延迟的板级端口 `HBM_CATTRIP`，无未约束的内部端点。方法学报告单列一项 TIMING-9；本次未对该项作例外豁免。[DRC](physical/drc_impl.rpt.gz) · [CDC](physical/cdc_impl.rpt) · [约束检查](physical/check_timing_impl.rpt) · [方法学报告](physical/methodology_impl.rpt.gz)

## Linux 和板卡

首轮编程成功，HBM 的 16 个伪通道通过两种模式的读写。JTAG 回读核对了 15506016 字节镜像和 508616 字节清零区。Linux 启动后完成 GPU DMA 回环并执行光追与分形机器码。[编程日志](logs/program.log) · [HBM 日志](logs/hbm.log) · [镜像装载日志](logs/load.log)

| 冷启动 | 板卡管理器运行号 | 已取得的证据 |
| --- | --- | --- |
| 1 | `20261124T140435.335713Z` | 实板运行通过。100 个连续整数和 FP32 作业、跨 SM 可见性、CPU/JTAG 与 GPU 并行访问、四项压力负载及八个三角形场景通过。[UART](logs/board-01/linux-uart.log.gz) · [运行结果](results/board-01/uart-result.json) · [并发记录](results/concurrent.json) |
| 2 | `20261124T144409.648312Z` | Linux 启动、GPU 识别和 DMA 回环通过。缓存测试初次读计数时缺少设备节点，按 sysfs 中的设备号建立节点后在同一次启动重试。按用户要求在重试期间停止，未完成同地址更新验证。[原始 UART](logs/board-02/linux-uart.log.gz) · [重试 UART](logs/board-02/linux-uart-cache-retry.log.gz) · [管理器 UART](logs/board-02/uart.log.gz) · [已完成作业](results/board-02/jobs.json) |
| 3 | 未发起 | 工作已停止。 |

两次启动之间，管理器的 UART 所有权或心跳检查曾拒绝一次请求；复核当前运行后发起了表中第二轮。此处计入两次实际编程。停止时管理器状态为 `stopped`，板测 worker 和 UART bridge 均已退出。[执行记录](logs/after-build.log.gz) · [首轮编程请求](results/board-01/request.json) · [第二轮编程请求](results/board-02/request.json)

## GPU 实测性能

16 SM 满掩码 FP32 FMA 基准实测 **11.583 GFLOPS**。以 16 SM × 4 lane × 100 MHz × 2 FLOP/FMA 计算，理论峰值为 12.8 GFLOPS，实测利用率为 **90.49%**。64 个工作组共完成 33554432 次 FMA；同一份位流只启用 SM0 时为 8521842 周期、0.787 GFLOPS，启用全部 SM 时为 579391 周期，实测加速 **14.708×**。计数包含完整作业，同一初始状态下两次输出一致。[基准计数器](results/performance.json)

INT8 矩阵基准共完成 134217728 次 MAC，单 SM 为 8521757 周期，16 SM 为 580480 周期，达到 **23.122 GMAC/s** 和 **14.681×** 加速。DMA 拷贝基准读、写各 8192 字节，共 290148 周期，有效复制速率为 **2.823 MB/s**，读写载荷合计为 **5.647 MB/s**。这些数值含该小作业的调度和访存开销。[基准实现](../../linux/cli.c) · [计数器](results/performance.json)

| 实板输出 | 配置 | 16 SM 完整负载耗时 | 单 SM 耗时 ÷ 16 SM 耗时（图像取中央 16 行） | 对照结果 |
| --- | --- | ---: | ---: | --- |
| [光线追踪](images/raytrace.png) | 512×384，4 球，主射线与阴影射线 | 337406690 周期，3.374 s | 1.035× | 最大通道误差 1，与整帧 RTL 输出逐字节相同 |
| [曼德博集合](images/mandelbrot.png) | 1024×768，每像素 4 个子样本，上限 192 次 | 2071390806 周期，20.714 s | 1.859× | 786432 个像素与 FP32 参考逐字节相同 |
| [朱利亚集合](images/julia.png) | 1024×768，每像素 4 个子样本，上限 192 次 | 2071820082 周期，20.718 s | 1.795× | 786432 个像素与 FP32 参考逐字节相同 |
| N 体引力 | 512 粒子，四个时间步，1048576 对交互 | 19155902 周期，0.192 s | 2.139× | 最大绝对误差 1.19×10⁻⁷，1 SM 与 16 SM 输出相同 |

图形 GPU 时间为全帧所有 16 行图块的设备周期之和。中央图块 1 SM / 16 SM 周期分别为光追 18745746 / 18104820、曼德博 79085693 / 42530699、朱利亚 76055775 / 42370112。三帧在 Linux 内的提交到完成轮询累计耗时分别为 3.394、20.756、20.760 秒。服务器含 UART 上传下载和参考校验的全帧耗时分别为 224.94、497.78、556.10 秒。N 体加速比取完整四步，单 SM 为 40969598 周期，16 SM 达到每秒 547 万对交互。寄存器 FMA 的 14.708× 扩展性与这些应用的实测加速比分别报告。[逐作业与每 SM 计数器](results/stress.json)

### 渲染一幅图要多久

| 单幅实板图像 | GPU 整帧计算 | Linux 提交至完成累计 | 含 UART 传输与结果比较 |
| --- | ---: | ---: | ---: |
| 1024×768 曼德博集合 | **20.714 s** | 20.756 s | 8 分 17.78 秒 |
| 1024×768 朱利亚集合 | **20.718 s** | 20.760 s | 9 分 16.10 秒 |
| 512×384 球体光追 | **3.374 s** | 3.394 s | 3 分 44.94 秒 |

每幅分形共 786432 个像素，计算 3145728 个子样本。GPU 表列时间按 100 MHz 的设备周期换算。最后一列从服务器开始 16 SM 全帧循环计至最后一块比较完成，包含分块参数上传、设备提交、UART 下载和逐像素校验，不含最后的 PNG 编码与服务器至 WSL 的文件同步。

### 为什么分形和光追的多 SM 加速较小

以下配对使用同一位流、程序、图块及像素输出，仅更改 SM 掩码。单 SM 只计中央 16 行，整帧加速比尚无对应测量。GPU 的 elapsed 计数器在作业控制器 busy 时每拍加一，包括计算、调度与访存等待。Linux 一列使用单调时钟度量提交到完成的经过时间，Aurora CPU 进程消耗的 CPU 时间未单独采集。[GPU 计数器](../../src/main/scala/morganite/Gpu.scala) · [Linux 计时](../../linux/cli.c)

| 中央 16 行 | 1 SM | 16 SM | 加速 | 1 SM / 16 SM 访存状态周期占比 |
| --- | ---: | ---: | ---: | ---: |
| 曼德博 | 790.857 ms | 425.307 ms | 1.859× | 54.92% / 99.30% |
| 朱利亚 | 760.558 ms | 423.701 ms | 1.795× | 56.66% / 99.19% |
| 球体光追 | 187.457 ms | 181.048 ms | 1.035× | 98.69% / 99.90% |

曼德博中央块有 512 个工作组，16 个 SM 各完成 32 组。两种掩码都执行了 80098045 次 FP lane 操作。全开后，654290 笔读 burst 共返回 2627808 字节，平均 4.02 字节/笔；整块有效读速率为 6.18 MB/s。光追同样只读 1124288 字节，却发出 278905 笔读 burst。占比取各 SM 的访存状态周期之和除以活跃周期之和。访存状态包括发送请求和等待响应，同一 SM 的其他就绪 warp 仍可执行。[原始计数](results/stress.json) · [SM 计数语义](../../src/main/scala/morganite/Sm.scala)

当前 16 个 SM 共用 32 位 DMA 读通路，同一时刻只允许一笔外部读 burst 在途。合并器把连续地址合成增长突发，多个 lane 重复读同一个参数地址时会产生短事务。分形反复读坐标参数和调色表，光追反复读球体参数；这次普通提交给出的只读窗口为空。这些数据支持共享读请求的串行处理和等待是主要瓶颈。长期平均速率远低于接口每拍一字的 400 MB/s 上限，缺少 HBM 端逐拍占用或延迟分解，无法从该值推断 HBM 芯片饱和。只读窗口开关的同图块配对未执行，缓存可能带来的加速仍未测量。[DMA 状态机](../../src/main/scala/morganite/Dma.scala) · [分形程序](../../examples/fractal.s) · [光追程序](../../examples/raytrace.s) · [板测提交](../../board/uart_stress.py)

### 三角形场景帧时

| 320×240 场景 | 三角形数 | GPU 帧时 | Linux 端到端 |
| --- | ---: | ---: | ---: |
| 着色三角形 | 1 | 0.436 s | 2.136 s |
| 更换顶点程序 | 1 | 0.437 s | 2.250 s |
| 更换片元程序 | 1 | 0.431 s | 2.243 s |
| 共享边 | 2 | 0.429 s | 2.244 s |
| 前后交叠 | 3 | 0.397 s | 2.208 s |
| 退化三角形 | 2 | 0.264 s | 2.062 s |
| 立方体视角 A | 12 | 0.530 s | 2.352 s |
| 立方体视角 B | 12 | 0.544 s | 2.352 s |

每幅含 76800 个像素。Linux 端到端取 `mgpu render` 的板端计时，涵盖场景读入、程序提交、结果下载和写入板端文件。八个场景的覆盖、胜者、颜色和深度超差像素数均为零。[作业记录](results/jobs.json) · [逐场景比较](results/comparisons.json)

## 归档与复核

本次 16 SM 编程使用下列位流和镜像。顶层硬件清单的 `sha256.u50c_top.bit` 记录原 Aurora 回滚基线；本次实际编程的 BIT、LTX 和镜像哈希以两轮请求为准。归档的 SoC 输入含冻结的 Aurora RTL、Morganite RTL、板级 Tcl 和约束，Linux 包含本次镜像、板卡运行脚本和 initramfs 内 GPU 载荷的清单。[位流](artifacts/u50c_top.bit.gz) · [LTX](artifacts/u50c_top.ltx) · [SoC 输入](artifacts/soc-inputs.tar.gz) · [Linux 包](artifacts/linux-board.tar.gz) · [文件校验清单](SHA256SUMS)

图像保留了原始 GPU 回读和三角形参考缓冲。八组小场景各有彩色图和深度图；RTL 整帧光追与板卡回读可直接对照。[实板图集](images/) · [RTL 光追](images/rtl/raytrace-512x384.png) · [原始缓冲及参考](results/frame-buffers.tar.gz)

从本目录运行以下命令可复核归档并重新生成八个场景的逐像素比较。Python 依赖为 NumPy 和 Pillow。

```bash
sha256sum --quiet -c SHA256SUMS
scratch=$(mktemp -d)
tar -xzf results/frame-buffers.tar.gz -C "$scratch"
python3 ../../graphics/test_render.py board "$scratch/frames"
```
