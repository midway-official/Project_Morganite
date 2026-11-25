# 每 SM 的 L1、矩阵乘加与 FMA 利用率

**简体中文** · [English](l1-tensor.en.md)

本页描述开发分支 `gpu/l1-tensor`，综合输入由提交 `4517279` 生成。可独立配置 SM 数、每 SM 的 I/D 容量、共用行长、INT8 矩阵内积长度，以及调度和寄存器堆选项。首个全功能配置为每 SM 各 2 KiB I/D、32 字节行、64 个直接映射集合、4 个物理 lane、8 线程 warp、4 个 warp 槽和 K=4。

## 缓存及可见性

指令 L1 按需读取 HBM。每 SM 有一个在途 I-cache 请求和一个最近使用的指令行缓冲。就绪 warp 可同时从缓冲内的不同 PC 取指，多个缺失的 warp 用轮询选择下一行。PC 以程序起始位置为原点，允许非行对齐程序和不完整尾行；DMA 仅发出描述符允许的字，跨 4 KiB 时拆 burst。数据和 tag 采用同步 RAM，复位与提交时清有效位。行缓冲服务的取指不计入后端 L1 hit 计数。

数据 L1 缓存主机声明在本次作业期间不可变的物理字节区间 `[roBase,roEnd)`。整行必须位于该区间。当前支持 lane 0 有效的同址广播（含自然对齐的 byte/half/word）和 lane 对应字偏移的连续读。跨行、混合区域、离散读与其他形状通过原 DMA；正常写也通过 DMA。FPGA 内无需写回、脏行或嗅探目录。首次合法访问缺失时整行填充，收到成功回复后置有效；填充出错时向作业报告出错地址。

作业内，所有 SM 可以各持有该区间的副本。公用 DMA 在任何写 burst 发出前检查每个活动 lane 的整个字节范围。只要一条向量写触及程序或声明的只读数据，就拒绝整条向量写。这个检查也覆盖只开指令缓存时的程序。scratchpad 是各 SM 的工作组私有存储。普通可写全局缓冲旁路，跨工作组依赖仍使用作业边界。

作业成功需要所有 SM 退出且 DMA 读写事务全部排空。取消会停止接纳新组，已接纳的请求继续排空。下一次 doorbell 同时清除所有 SM 的 I/D 有效位和指令行缓冲，重新从 HBM 取程序和数据。Linux 使用独占打开、busy 期间禁止用户缓冲读写、coherent DMA、提交前 `dma_wmb()` 和完成后的 `dma_rmb()`。JTAG 与外部主设备必须遵守区域所有权，GPU 无法拦截其他 AXI 主设备的写。从 RTL 仿真迁移到板上仍需验证实际 HBM 转换链与主机视图。[共享 DMA](../src/main/scala/morganite/Dma.scala) · [L1](../src/main/scala/morganite/ReadOnlyL1.scala) · [驱动](../linux/morganite.c)

## 编程接口

| 选项或地址 | 行为 |
| --- | --- |
| `ic=N dc=N` | 每 SM 的字节容量。0 关闭；启用时为二次幂，至少 2 行、至多 64 KiB |
| `line=N` | 每行 32 位字数，二次幂，启用缓存时不超过 warp 宽度 |
| `tensor=0/1/2/4` | 0 关闭，非零值为矩阵指令 K，物理 lane 数必须为 4 的倍数 |
| `earlyBranch=1` | 直接跳转在发射时更新 PC 并跳过 lane 执行；条件跳转仍等待谓词寄存器就绪，独立算术可继续写回 |
| `bramRf=1` | 三个读副本、掩码写与同拍写转发，改用数据端寄存的同步读模板。Vivado 对两种模板都能推断 BRAM |
| `0x50` | 位 0/1 表示 I/D L1，位 10:8 给出矩阵 K |
| `0x54,0x58` | 只读数据区间首地址、排他末地址；仅空闲时可写。相等表示关闭数据分配 |
| `0x600 + 16*SM` | 连续四个 32 位计数为 I hit、I miss、D hit、D miss；每次提交清零 |

`mma.s8 rd,ra,rb,rc` 使用 R4 编码 0x47，funct3 与位 26:25 均为零。在每 4 个相邻线程中，lane 0/2 提供 A 的第 0/1 行，lane 0/1 提供 B 的第 0/1 列。低位字节对应 k=0。每个活动线程计算自己的 `C[row,col] + sum(A[row,k]*B[k,col])`，有符号 INT8 乘法，INT32 结果取低 32 位。每个活动四线程 tile 必须完整，否则整条指令产生 illegal fault。独立组可用掩码全部关闭。目的寄存器可与源重叠。乘积、部分和和公共六拍写回分级，并沿用 warp scoreboard。Tensor 运算与 FP32 FMA 分开计数，现有 FP32 FMA 计数只含标量 FP32。

Linux 用 `MG_SUBMIT_RO` 随作业传入 DMA 池内的 offset 与 length，内核核对范围并换算设备地址。旧 `MG_SUBMIT` 清除只读区。CLI 用 `mgpu submit desc_offset sm_mask ro_offset ro_length`。如需第 5 个及之后的 SM 或缓存计数，使用 `MG_SM_COUNTERS`。带特性寄存器的 BIT 用 `python3 linux/build_kernel.py --feature-register` 生成对应设备树。旧 BIT 的设备树保留原配置，避免探测未实现的 MMIO。交叉编译前先运行 `bash linux/build_userspace.sh`。

## 验证与重建

在仓库根目录执行。每次综合从新生成的 RTL 开始，历史 CPU/GPU/整机布线后报告见 boot-20。[boot-20](u50c-boot-20.md)

```bash
source scripts/toolchain.sh
d=build/l1-check-2
sbt "runMain morganite.Generate Gpu $d 2 8 4 ic=2048 dc=2048 line=8 tensor=4 bramRf=1 earlyBranch=1"
"$VERILATOR" --cc --exe --build -j 4 --top-module Gpu --Mdir "$d/obj" "$d/Gpu.sv" "$PWD/sim/gpu.cpp"
python3 scripts/test_gpu.py "$d/obj/VGpu" 2 "$d/jobs"
python3 scripts/test_accelerators.py "$d/obj/VGpu" 2 "$d/features"
python3 scripts/measure_fma.py "$d/obj/VGpu" "$d/performance" --unroll 8
vivado -mode batch -source scripts/synth_gpu.tcl -tclargs Gpu "$d/Gpu.sv" "$d/synth"
python3 scripts/stage_soc.py build/soc-l1 --accelerators
# 在 Vivado 2023.2 服务器上进入已上传的 soc-l1 目录
MORGANITE_SYNTH_ONLY=1 bash run_soc.sh
```

需已有仓库标准 SoftFloat 参考库，初次运行用 `bash scripts/test_fp.sh` 生成。基础回归沿用 AXI-Lite 提交、随机背压、全 DMA 镜像与独立模型对拍。专项覆盖同址程序和数据替换、两个 SM 顺序生产/消费、AXI 补行取消与错误、直接映射 A/B/A/A 冲突、只读区和代码写保护，以及 INT8 tile/掩码/源目的重叠。测试中 2/8/16 SM 对同一数据行各独立读取一次，第二次访问各私有命中。取消时测试主动扣住 R 响应，确认 busy 持续且第二个 doorbell 返回错误，然后释放 R 并成功运行下一作业。

## 32 SM 的 U50C 整机复现

硬件输入来自 `626b31b`，服务器软件包来自 `d2514ae`。复用 boot-20 的 Aurora RTL，GPU 配置为每 SM 四条 lane、八线程 warp、四个 warp 槽、4 KiB 指令缓存和 8 KiB 只读数据缓存，行长 32 字节。控制寄存器 `0x08` 的预期值为 `0x04080420`，`0x50` 为 `0x403`，其中 K=4。[整机输入清单](../reports/l1-tensor/soc32-Vmx1k2nM/manifest.json)

在准备好冻结输入和 LA32R 交叉工具链的 WSL 工作区中运行以下命令。两个输出目录均使用新路径。

```bash
python3 scripts/stage_soc.py build/rebuild32/soc --sms 32 --accelerators
bash linux/build_userspace.sh
python3 linux/build_kernel.py --feature-register
python3 scripts/stage_software.py build/rebuild32/software-board --sms 32 --gpu-features 0x403 --verify-load
(cd build/rebuild32/soc && sha256sum --quiet -c files.sha256)
(cd build/rebuild32/software-board && sha256sum --quiet -c files.sha256)
```

将 `soc/` 内的文件和同级的 `software-board/` 上传到 Vivado 2023.2 服务器的一次性运行目录。以下命令在包含 `run_soc.sh` 和 `software-board/` 的目录内执行。物理验收核对布线状态、各时钟域 setup/hold、脉宽、DRC、CDC 和约束，随后使用同次 BIT/LTX 与镜像提交编程请求。

```bash
MORGANITE_SYNTH_ONLY=0 bash run_soc.sh
sha256sum --quiet -c implementation/program.sha256
python3 software-board/start_test.py . software-board board-01
python3 software-board/uart_jobs.py board-01 --full
```

第二轮启动可运行 `python3 software-board/uart_jobs.py board-02 --cache-test`。测试使用 `copy`，每个 SM 处理一个 warp。先让参数读旁路，再将两个指针所在的 32 字节行声明为本次作业只读，最后在空闲时修改同地址的源指针并重新提交。每轮比较整段 DMA 输出的 SHA-256，并在 GPU 空闲时读取每个 SM 的 D-cache hit 和 miss。预期旁路为 0/0，两次只读提交各为 1/1。实测结果保存于该轮的 `cache-test.json`。专用 `mgpu kernel_ro copy groups threads sm_mask ro_offset ro_length` 命令会通过 `MG_SUBMIT_RO` 为新作业设置只读范围。

首个上传候选的 `ram.dat` SHA-256 是 `ab96ec949de15d62018e33f245e5c68f0f7b077b02f481038f42c3573f9dc470`。加入 `mgpu kernel_ro` 后更新的测试镜像 SHA-256 是 `39bec2d0c3d54317749419938b42df6f9041f1f63be196164275a7a3130deb2f`，它用于后续冷启动和 L1 测试。板测入口还会从 Linux 核对实际运行的 CLI、程序和场景。将 `board-01/frames` 回传至本地后，使用 `python3 graphics/test_render.py board board-01/frames` 做逐像素比较，再用 `python3 graphics/export_png.py board-01/frames board-01/frames/comparisons.json board-01/frames` 导出颜色与深度 PNG。每个场景同时保留原始 FP32 深度和图元编号。[已取得的整机综合数据](../reports/l1-tensor/README.md)

## 资料与设计取舍

| 查阅的资料 | 本实现采用或验证的结论 |
| --- | --- |
| AMD UG901 v2022.2，RAM HDL Coding Techniques 与 USE_DSP | 同步读、读写碰撞和输出寄存方式影响推断。保留显式写转发、复查层次利用率。乘积注册后根据综合结果统计 DSP。文档归 AMD。[UG901](https://www.xilinx.com/support/documents/sw_manuals/xilinx2022_2/ug901-vivado-synthesis.pdf) |
| Vortex `279e65b`，Apache-2.0 | 参考容量、行宽、bank/way 参数检查；小型 FPGA 配置选直接映射和阻塞式填充。对照 scoreboard 的写回释放/占用时序，保留本机每 warp 的 RAW/WAW 检查。[缓存 RTL](https://github.com/vortexgpgpu/vortex/blob/279e65b47e63f6b87d043729d9afbbcb12fc198c/hw/rtl/cache/VX_cache.sv) · [scoreboard](https://github.com/vortexgpgpu/vortex/blob/279e65b47e63f6b87d043729d9afbbcb12fc198c/hw/rtl/core/VX_scoreboard.sv) |
| NVIDIA Turing Tuning Guide v13.5，Instruction Scheduling | 针对不同 warp 和同一 warp 内的独立累加器分别考虑延迟隐藏。Morganite 用相同工作量做硬件与循环展开的交叉对照。[Turing](https://docs.nvidia.com/cuda/turing-tuning-guide/index.html) |
| NVIDIA PTX ISA 9.5、CUTLASS `f8acf1a`（BSD-3-Clause） | 参考协作式矩阵片段与活动线程约束。Morganite 自行定义 2×2 输出位置和完整四线程 tile 检查，指令与 PTX 二进制不兼容。[PTX MMA](https://docs.nvidia.com/cuda/parallel-thread-execution/index.html#warp-level-matrix-instructions-mma) · [CUTLASS](https://github.com/NVIDIA/cutlass/blob/f8acf1aa78f96ff2f1ba7662fe495e5535c461bf/media/docs/cpp/implicit_gemm_convolution.md) |
| Linux DMA API HOWTO，Types of DMA mappings | coherent 映射仍要求正确的内存排序。沿用 LA32R 的 coherent 分配、明确状态所有权、作业边界以及设备端排空。[DMA API](https://docs.kernel.org/core-api/dma-api-howto.html) |

参考源码用于设计对照，新增 RTL 和模型由本项目实现。实验配置和逐项原始结果见[仿真与综合记录](../reports/l1-tensor)。
