# Morganite 可编程三维 GPU

Morganite 是用 Chisel 编写的多 SM GPU。在 Alveo U50C 上，Aurora LA32R CPU 启动 Linux，将 GPU 程序、描述符和数据写入 HBM，通过 AXI4-Lite 提交作业。GPU 从 AXI4 主口访问 HBM，支持计算内核、可替换的顶点程序和片元程序。

**16 SM 实板记录（2026-11-24）**。FP32 FMA 实测 11.583 GFLOPS。GPU 渲染 1024×768 曼德博和朱利亚集合分别耗时 20.714 和 20.718 秒，512×384 球体光追耗时 3.374 秒。首轮完成计算和图形校验，第二轮启动后在缓存专项中停止。[中文报告、位流、镜像和实板图](docs/u50c-16sm-20261124/README.md)

**实板版本 `u50c-boot-20`** 使用 2 个 SM、每 SM 4 条 FP32 lane、8 线程 warp。CPU 和 GPU 运行在 100 MHz，HBM AXI 运行在 225 MHz。Linux 从普通 RAM 动态分配 DMA 缓冲区，完成 130 次 GPU 提交，故障数为零。其中 100 次为连续的整数和 FP32 AXPY 作业。八个 320×240 场景的覆盖、深度胜者和颜色、深度误差均通过独立 CPU 光栅器检查。[板测记录、哈希与复现步骤](docs/u50c-boot-20.md) · [发布文件](releases/u50c-boot-20)

![第 20 次 U50C 实板从 HBM 回读的立方体彩色图](reports/bringup/f2a3ce0/board-test-20/frames/cube_a/color.png)

[同帧实板深度图](reports/bringup/f2a3ce0/board-test-20/frames/cube_a/depth.png) · [另一视角](reports/bringup/f2a3ce0/board-test-20/frames/cube_b/color.png) · [同场景 RTL 仿真图](reports/graphics/rtl-320x240/cube_a/color.png) · [逐像素比较](reports/bringup/f2a3ce0/board-test-20/frames/comparisons.json)

## 源码与接口

开发分支增加了每 SM 的可选 I/D L1、INT8 矩阵乘加和调度选项。双 SM 的 16 倍展开 FP32 基准在 RTL 仿真中达到理论峰值的 98.34%。[架构、资料与一致性](docs/l1-tensor.md) · [综合、吞吐与回归证据](reports/l1-tensor/README.md)

| 内容 | 入口 |
| --- | --- |
| GPU 顶层、SM、FP32、DMA、板级接口和参数 | [Chisel 源码](src/main/scala/morganite) · [FP32 语义](docs/fp32.md) |
| 本版 Aurora CPU 的可综合源码和独立 sbt 工程 | [Aurora 工程](cpu/aurora) · [出板 CPU 配置](releases/u50c-boot-20/aurora-config.json) |
| LA32R Linux 驱动、设备树、DMA 缓存维护和 CLI | [驱动与用户态源码](linux) · [提交接口](linux/morganite.h) |
| GPU ISA、汇编、独立指令模型和计算示例 | [ISA](docs/isa.md) · [汇编器与模型](sw) · [示例](examples) |
| 顶点与片元程序、光栅化规则和 CPU 参考实现 | [着色程序与参考光栅器](graphics) · [图形 ABI](docs/graphics.md) |
| CPU、GPU、完整 SoC 的资源与布线时序 | [分层资源](reports/resources/f2a3ce0-route/summary.json) · [物理报告](reports/physical/README.md) |

## 在 WSL 复核实板图

在仓库根目录运行。Python 3 需安装 NumPy 和 Pillow。命令读取已保存的 HBM 数据，重新生成参考图并输出八个场景各自的误差统计。

```bash
frames=reports/bringup/f2a3ce0/board-test-20/frames
python3 graphics/test_render.py board "$frames"
python3 graphics/export_png.py "$frames" "$frames/comparisons.json" "$frames"
```

GPU 使用 JDK 17、sbt 1.9.8、Scala 2.13.12、Chisel 6.6.0、firtool 1.62.1 和 Verilator 5.050。CPU 使用独立的 Chisel 3.6.1 工程。完整 SoC 的正式布线使用 Vivado 2023.2。[硬件生成与驱动构建](docs/u50c-boot-20.md) · [原 Aurora 基线与回滚流程](README.en.md)
