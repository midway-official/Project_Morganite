# 16 SM 可编程 GPU 负载

**简体中文** · [English](gpu-stress.en.md)

Linux 通过现有驱动向 DMA 缓冲区上传机器码、参数和描述符，用 `mgpu submit` 启动作业。三个程序共用一份位流。每次装入程序都回读核对机器码，板测保存完成序号、SM 掩码、每 SM 完成组数、FMA 计数、周期和原始输出。

| 负载 | 板测配置 | 正确性检查 |
| --- | --- | --- |
| 曼德博集合与朱利亚集合 | 每幅 1024×768，192 次上限，2×2 子像素，GPU 执行 FP32 迭代、调色和混色 | 两幅 RGBA8 逐字节对照独立 FP32 参考 |
| 球体光线追踪 | 512×384，正交投影，4 个有遮挡的球体，最近交点、方向光、漫反射和硬阴影 | 对照使用双精度平方根的 CPU 射线求交，每通道允许 1 级误差 |
| 软化引力 N 体 | 512 个粒子、4 步，每步 262,144 对交互，16 线程分块共享位置和质量，FP32 计算力和积分 | 对照双精度直接求和，各状态量绝对误差上限 0.000002，同时要求两种 SM 配置的最终输出逐字节一致 |

图像每次提交 16 行，以适配 2 MiB DMA 窗口。帧中央相同的 16 行分别用掩码 `0x0001` 和 `0xffff` 运行；全帧使用 16 SM。两种掩码的图块必须完全一致。N 体用两种掩码从相同初始状态重跑全部四步，以完成边界同步两块交替使用的缓冲区。每笔作业都检查总工作组数和每个启用 SM 的完成计数。

```bash
python3 scripts/stage_stress.py build/stress-suite
python3 scripts/test_stress_rtl.py build/stress-rtl/standalone/VGpu build/stress-rtl/scenes
```

第一条命令生成 GPU 二进制、参考文件和 SHA-256 清单，并运行小规模汇编指令模型对拍。依赖 NumPy、Pillow 和原工程的 `build/fp/reference/libreference.so`。第二条命令复用 `sim/gpu.cpp` 的 Verilator AXI 测试台，输入为开启指令与只读数据 L1、BRAM 寄存器堆的独立 16 SM GPU 模型。

将 `build/stress-suite`、`board/uart_stress.py` 与 `board/uart_jobs.py` 交给现有服务器端板测流程。新启动的 Linux 由该流程持有 UART，调用方式如下。

```bash
python3 uart_jobs.py "$board_run" --stress-suite "$suite" --full
```

板测用 GPU/HBM 回读生成 `$board_run/stress/{raytrace,mandelbrot,julia}.rgba` 和同名 PNG，保存 N 体状态、分块周期和比较结果于同一目录。每帧的 `frame_cycles` 是 16 SM 各块设备周期之和。`elapsed_with_uart_s` 包含每块的提交、轮询、UART 下载和参考比较。WSL 生成的参考图文件名带 `-reference.png`。
