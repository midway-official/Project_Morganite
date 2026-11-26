# Morganite 宽口 GPU 与可选图形单元

[English](README.en.md) · [320×240 RTL 颜色图](images/cube_a/color.png) · [深度图](images/cube_a/depth.png)

## SFU 函数选择

| 首版指令 | 直接使用者 | RTL 数值测试 |
| --- | --- | --- |
| `rcp.approx.f32`，计算 1/x | 顶点透视除法、三角面积倒数、透视校正插值 | 165,040 个输入，最大观测相对误差 0.389099% |
| `rsqrt.approx.f32`，计算 1/√x | 法线与光线方向归一化、N 体的逆距离。现有光追、N 体软件有相应的 Newton 迭代 | 165,040 个输入，最大观测相对误差 0.194998% |

每个物理 lane 的 SFU 由 `sfu=1` 生成。指数逻辑结合 128 段尾数中点查表，倒平方根另区分无偏指数奇偶。每个表项保存 15 个尾数字段高位。单元可每拍接收一个有效 lane 运算，沿用现有算术指令的六拍写回。应用吞吐还包括 warp 发射、数据依赖和寄存器写回。测试扫描每个正规指数下所有查表段的两个端点，并加入每函数 100,000 个带种子的随机输入。相对误差统计针对正规结果。 [实现](../../src/main/scala/morganite/GraphicsLane.scala) · [测试](../../sim/graphics_lane.cpp) · [原始数据](sfu.json)

两条指令均采用输入和结果 FTZ，保留零的符号；正负零输入得到同号无穷。倒数对正负无穷分别返回同号零。倒平方根对正无穷返回正零，对负正规数和负无穷返回规范 quiet NaN。NaN 输入返回 `0x7fc00000`。近似指令不更新 `fflags`。渲染库对倒数做两次残差精化，每次用两条融合 FMA。有限正值的平方根可组合为 `x * rsqrt(x)`；通用库应显式处理零、无穷和负值。 [渲染库](../../graphics/shaders.py)

后续函数按负载排序。体积雾、曝光、幂函数和基于距离的分形着色可复用 `exp2`、`log2`。三角函数用于程序纹理、频繁旋转或含角坐标的三维 Mandelbulb；首选共享参数约简的 `sincos`。普通 Mandelbrot 和 Julia 的逃逸循环主要用乘加和比较。首版面积预算优先给倒数和倒平方根。 [PTX 特殊函数语义](https://docs.nvidia.com/cuda/parallel-thread-execution/) · [AMD GPU ISA 参考](https://gpuopen.com/amd-isa-documentation/)

### SFU 对实际程序的贡献

两组都运行在同一套 1×2 SM RTL 上，每 SM 有 4 条物理 lane，每 warp 有 8 个线程，存储端使用两个 256 位接口。表内为 GPU 作业计数器周期，立方体一行是顶点、设置、分箱和像素四次作业之和。两组输入、存储模型和图形硬件一致，仅改变 GPU 程序的函数求值方式。软件基线内核固定为 `d9295b4`。 [计数器和输出哈希](sfu-workloads.json) · [内核差异](rsqrt-kernels.patch)

| 负载 | 软件周期 | SFU 周期 | 加速比 | 与独立参考的比较 |
| --- | ---: | ---: | ---: | --- |
| 256 个天体，软化引力一步 | 700,339 | 582,889 | 1.2015× | 两组状态最大绝对误差均为 1.12×10⁻⁸ |
| 4 球光追，主射线及阴影，64×48 | 146,920 | 139,446 | 1.0536× | RGBA8 最大通道差从 1 降到 0 |
| 12 个三角形的立方体，64×32 | 404,716 | 402,832 | 1.0047× | 两组颜色、覆盖和深度胜者均完全匹配 |

N 体一步求 65,536 次成对逆距离。SFU 程序把整数初值和两轮 Newton 迭代替换为硬件初值和一轮带 FMA 的精化。光追只对判别式为正的候选交点求平方根。立方体仅覆盖 483 个像素，软件版的 404,716 周期中，有 386,308 周期用于像素作业。加速倒数后，三角形遍历、覆盖、属性插值和写回仍占用像素作业周期。 [参考负载](../../scripts/stage_stress.py) · [复现脚本](../../scripts/bench_sfu.py)

在目标 4×4 RTL 上用相同输入做配对对照，N 体从 116,319 降至 **103,517** 周期（1.124×），光追从 44,023 变为 **44,060** 周期（0.999×），立方体从 69,392 降至 **68,429** 周期（1.014×）。16 个 SM 均完成了 N 体工作组，两种程序均通过同一精度检查。小光追场景在 16 SM 上没有获得吞吐收益。完整作业还受调度、取指和访存竞争影响，现有计数器不足以将这 37 周期差额归因到其中一项。 [16 SM 计数与输出哈希](sfu-workloads-16.json)

### 下一批函数怎样选

| 顺序 | 函数与接口 | 适用负载和验证方法 |
| --- | --- | --- |
| 现有指令组合 | 除法基于 `rcp`，正正规数开方基于 `rsqrt`，向量归一化和整数幂复用乘法 | 通用库单独处理有符号零、无穷、NaN 和定义域。由程序按精度选 FMA 精化次数。严格 IEEE 舍入需要单独验证。 |
| 下一组候选 | 独立可查询的 `exp2`、`log2` 近似 | 用平滑逃逸着色、雾效或材质程序测收益。覆盖 1 附近、溢出和 FTZ 边界，用高精度参考校验，结果接近零时用绝对误差。正底数的 `pow` 可以组合实现。 |
| 有角度密集负载时 | 共享象限约简的 `sin`、`cos` | 用 Mandelbulb 或程序纹理实际测量同时用两个结果的着色器。独立单目的指令可复用现有 RF 写口，明确小区间输入与大角度软件约简。Mandelbulb 所需的 `atan2`、`acos` 先在库中评估，覆盖坐标轴和极点。 |

PTX 暴露倒数、倒平方根、以 2 为底的对数及指数和三角函数近似指令。RISC-V V 也规定了可供精化使用的倒数、倒平方根估计指令。Morganite 的指令编码、精度及 FTZ 规则见上文。扩展前用相同着色器比较每 SM 一条共享流水线与四条 lane 各自查表的配置，记录发射和寄存器写回停顿、作业周期、LUT/BRAM/DSP 增量及布线后 slack。 [PTX 指令规范](https://docs.nvidia.com/cuda/parallel-thread-execution/#floating-point-instructions-rcp) · [RISC-V V 1.0 估计指令](https://github.com/riscvarchive/riscv-v-spec/blob/master/v-spec.adoc#vector-floating-point-reciprocal-square-root-estimate-instruction)

## 网格和图形流水线

`--grid MxN` 生成 M 行、N 列的 SM 和分层存储树，总数受 32 位提交掩码限制。SM 编号为 `row*N+col`。行内每四个 SM 一簇，末簇可以不足四个；一行有多簇时使用行仲裁和寄存 FIFO。各行通向两个独立的存储端点。MMIO `0x5c` 高、低 16 位分别为行、列，`0x60` 的位 0 至 4 对应下表从上到下五个选项。32 位主机 AXI4-Lite 配置口保留原作业 ABI。

| 开关 | 硬件与程序分工 |
| --- | --- |
| `setup` | 从打包的 16 位屏幕坐标计算边函数系数、常数项与 top-left 偏置。设定程序组织三角记录，生成裁剪体内的三角形。 |
| `bins` | 计算三顶点覆盖的 tile 包围范围，并为相交 tile 发出图元位。独立 GPU 作业按 8×8 tile 生成有序图元掩码到 HBM，此 ABI 每次 draw 支持 1–32 个三角形。 |
| `raster` | 计算有符号边函数并判定三个带偏置边值。像素中心采用 4 位小数的子像素坐标。 |
| `rop` | FP32 严格小于深度比较，以及 RGBA8 source-over 混合。RGB 用源 alpha 加权，输出 alpha 用源覆盖率合成，舍入到最近字节。像素所属线程维护胜者，通过 LSU 写 HBM。 |
| `sfu` | FP32 近似倒数与倒平方根。 |

这些是可编程 SM 的执行单元。分箱作业、同步、颜色与深度写回仍走 GPU 程序。冻结板级配置默认关闭五个选项。1×2 全功能和 2×3 仅 SFU 配置已逐条验证编码、独立模型、关闭单元的非法指令和错误后重新提交。4×4 全功能 SoC 已生成 RTL。

1×2 的 RTL 回归覆盖 8 个 64×32 场景。320×240 的 12 三角形立方体覆盖 17,980 个像素，颜色和胜者均完全匹配，最大 FP32 深度绝对误差为 1.79×10⁻⁷。 [小场景逐项结果](render-64x32.json) · [320×240 结果](render-320x240.json)

## 存储与时序

每笔向量请求先检查所有活动地址，再按 32 B 段生成 AXI 事务。11,532 个端点用例涵盖全部 256 种掩码、地址置换、广播、越界和相邻字节。两个全局槽与独立 scratchpad 槽共享一个 RF 写口。D-L1 的两个 MSHR 允许同一行合并并序列化同索引冲突。I/D-L1 各 2 KiB。8R/4W 逆序返回、取消、84 组单 SM 对拍和四种 SM 数共 48 项整 GPU 作业已通过。 [单 SM](sm.json) · [16 SM](gpu-16.json) · [测试日志](rtl-checks.txt)

16 SM 页拷贝程序使用 64 个工作组，每组重复复制一个 4 KiB 页四次，共读写各 1 MiB。单、双端口使用同一程序、相同背压模型，全缓冲区按字节对照。100 MHz 周期换算下，时序修改前，单、双口分别用 148,765、83,614 周期。加入寄存级后分别用 149,098、85,431 周期，双口/单口加速 1.745×，双口单方向有效负载 1.227 GB/s。物理带宽尚待板测。 [程序](../../examples/hbm_copy.s) · [改前](copy.json) · [改后](copy-pipelined.json)

原板测 16 SM 的最紧 setup 为 GPU 作业 fault 至 SM10 故障地址，数据路径 9.534 ns，其中布线 8.553 ns，slack +0.062 ns。最紧 hold 是 CPU LSU 的 +0.007 ns。时序候选将故障停发控制分别寄存在簇与 SM，就地判断工作组占用，并拆开段选择与字节排列。远端时序对照构建的源码为 `14d5252`，包含宽口与双槽，不含新增图形单元。 [冻结路径](../u50c-16sm-20261124/physical/paths.tsv) · [输入清单](timing-inputs.json)

Vivado 2019.2 对单条仅启用 SFU 的 lane 单独布线，使用 190 LUT、81 FF、0 BRAM、0 DSP。约束仅指定 10 ns 时钟，37 个输入及 24 个输出缺少外部延迟约束。集成后的面积和时序需要用完整设计的对照构建测量。 [利用率](sfu-ooc/utilization.rpt) · [时序](sfu-ooc/timing.rpt) · [OOC 脚本](../../scripts/synth_sfu.tcl)

Vivado 2023.2 对 16 SM 时序对照版的综合结果见下表。BRAM tile 按一个 RAMB36 或两个 RAMB18 计。 [综合原始报告](synthesis-reports.tar.gz)

| Hierarchy | LUT | FF | BRAM tile | DSP |
| --- | ---: | ---: | ---: | ---: |
| Aurora CPU (`u_cpu`) | 112,360 | 40,083 | 61 | 8 |
| Morganite GPU (`u_gpu_soc/gpu`) | 653,225 | 225,927 | 256 | 576 |
| SoC (`u50c_top`) | 779,006 | 278,633 | 323.5 | 584 |

### 双栈 HBM 厂商模型

Vivado 2023.2 的 CDC 和 AXI4→AXI3 转换 IP 通过 8R/4W 及逆序响应测试。共享字节存储模型通过 CPU/GPU 双向读写。完整 HBM 模型中，CPU 写奇数页后，GPU 从 `0x01c01000` 读回 `xxxxxxxx`，预期 `55667788`。CPU/JTAG 使用栈 1 本地入口 SAXI16 后，同一测试仍失败。第三次诊断先从 GPU 两端口各写满一行，再从写入端口读回。第一个偶数页 `0x01c00000` 期望 `aabbccdd`，读到 `xxxxxxxx`，此时 CPU 还没有访问该页。故障可以在单一 GPU 端口复现，桥接信号和 HBM 仿真模型仍需定位。远端目录分别为 `morganite-wide-hbm-RcGYZ01b` 和 `morganite-hbm-localports-jB8uByvO`。时序对照版仍在布局，完整 SoC 的布线结果与双栈实板验证待补。 [第一次对照](first-hbm-simulation.tar.gz) · [本地入口对照](local-host-hbm.tar.gz) · [同端口整行写读](initialized-hbm.tar.gz)

## 复现

```bash
scripts/test_wide_dma.sh
scripts/test_wide_tree.sh
scripts/test_wide_memory.sh
scripts/test_wide_gpu.sh
scripts/bench_wide_hbm.sh
scripts/test_graphics_units.sh
source scripts/toolchain.sh
python3 scripts/bench_sfu.py build/graphics-units/gpu/obj/VGpu build/sfu-workloads.json
sbt 'runMain morganite.Generate Gpu build/sfu16 4x4 8 4 dmaBits=256 ports=2 reads=8 writes=4 ic=2048 dc=2048 line=8 earlyBranch=1 bramRf=1 tensor=4 setup=1 bins=1 raster=1 rop=1 sfu=1'
"$VERILATOR" --cc --exe --build --assert -j 4 --top-module Gpu --Mdir build/sfu16/obj build/sfu16/Gpu.sv "$PWD/sim/wide_gpu.cpp"
python3 scripts/bench_sfu.py build/sfu16/obj/VGpu build/sfu-workloads-16.json --grid 4x4
python3 scripts/stage_soc.py build/gfx-soc --grid 4x4 --wide-ports 2 --accelerators --units setup bins raster rop sfu
bash linux/build_userspace.sh --units setup bins raster rop sfu
python3 linux/build_kernel.py --feature-register --graphics-registers
```

Linux CLI 以着色器包的能力掩码校验硬件，分箱作业只在对应软件包启用时提交。`MG_GRAPHICS` 返回网格与五个硬件位；旧镜像继续使用原命令。LA32R CLI 和驱动对象已编译。实板启动、物理报告和该版本的实测帧时单独验收。
