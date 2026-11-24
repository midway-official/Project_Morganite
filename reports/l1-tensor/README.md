# L1、矩阵乘加与资源实验

综合输入对应 `4517279`，远端 Vivado 2023.2 build 4029153，器件 `xcu50-fsvh2104-2-e`。运行目录为 `/home/fpga/morganite-l1-OOC-W7uF9r43`。基线 GPU 从 `u50c-boot-20` 重新生成并与综合输入逐字节比对。冻结 CPU 的输入哈希和新板级顶层见清单。[RTL 哈希](rtl.sha256) · [整机清单](soc-inputs.json) · [架构与复现](../../docs/l1-tensor.md)

## GPU 独立综合

| 配置 | LUT | FF | BRAM tile | DSP |
| --- | ---: | ---: | ---: | ---: |
| boot-20 双 SM | 40,772 | 13,842 | 16 | 40 |
| 双 SM，寄存器堆输出寄存、提前跳转 | 41,341 | 13,623 | 16 | 40 |
| 双 SM，上述选项和 2 KiB I-cache | 37,274 | 14,666 | 24 | 40 |
| 双 SM，全功能，I/D 各 2 KiB，K=4 | 40,872 | 17,095 | 32 | 72 |
| 8 SM，全功能 | 149,618 | 61,150 | 128 | 288 |
| 16 SM，全功能 | 294,934 | 119,899 | 256 | 576 |
| 32 SM，4 KiB I-cache、8 KiB 只读 D-cache、K=4 | 594,965 | 245,921 | 528 | 1,152 |

32 SM 这一行对应独立综合的 GPU RTL，SHA-256 为 `ed8386d450eae14d9072f71f6bed2628a00c75b51caa443fe4ff4ebe20988ba2`。使用全片 LUT 的 68.25%，BRAM tile 的 39.29%。后续完整 SoC 的硬件提交 `626b31b` 另含每 SM 的 INT8 MAC 计数器。该 SoC 复用 boot-20 冻结的 Aurora CPU，在 `/home/fpga/morganite-soc32-Vmx1k2nM` 运行 Vivado 2023.2。生成同配置输入可执行 `python3 scripts/stage_soc.py build/soc32 --sms 32 --accelerators`，在服务器的输入目录运行 `bash run_soc.sh`。[GPU 利用率](proposal32/synth/utilization.rpt) · [GPU 层次报告](proposal32/synth/hierarchy.rpt)

全部配置的 URAM 为 0。双 SM 的 LUTRAM 从 5,120 降至 96。从仅启用指令 L1 到全功能配置，GPU 增加 3,598 个 LUT、8 个 BRAM tile 和 32 个 DSP。与 boot-20 相比，全功能双 SM 增加 100 个 LUT。资源统计使用 10 ns 输入时钟约束和相同的独立综合脚本，目录中的 timing 是综合估计。Vivado 对部分缓存 RAM 提示读地址寄存可能限制流水化。100 MHz 时序收敛仍需布线报告。[基线](baseline/synth/utilization.rpt) · [寄存器与调度](core2/synth/utilization.rpt) · [指令 L1](finalic/synth/utilization.rpt) · [全功能双 SM](final2/synth/hierarchy.rpt)

## 完整双 SM SoC 综合

| 范围 | boot-20 LUT | 新 LUT | 新 FF | 新 BRAM tile | 新 DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU 层次 `u_cpu` | 111,430 | 112,371 | 40,088 | 61 | 8 |
| GPU 层次 `u_gpu_soc/gpu` | 44,142 | 45,679 | 19,094 | 32 | 72 |
| 整个 `u50c_top` | 163,944 | 166,816 | 67,091 | 97.5 | 80 |

两列 LUT 都取自综合阶段。CPU RTL 具有相同 SHA-256；完整工程重新综合后 CPU 层次计数有变化。新 SoC 的 LUT 占全片 19.14%，三行 URAM 均为 0。新设计的物理签核与板上性能仍待验证。[原版综合](../resources/f2a3ce0-synth/summary.json) · [新层次报告](soc/hierarchy_synth.rpt) · [全片资源](soc/utilization_synth.rpt)

本次完整 SoC 的综合检查点 `u50c_top.dcp` 的 SHA-256 为 `10538f0a019fe17c7dba658e1ed4ddfbe20c78a396213744bb2a9ff95c0a8bf8`。8 和 16 SM 的独立综合可分别在原始报告核对。[8 SM 利用率](final8/synth/utilization.rpt) · [16 SM 利用率](final16/synth/utilization.rpt)

## 双 SM 计算吞吐

16 个组，每组 32 线程、16 个独立累加器，512 个输出全部校验。FP32 每个作业含 8,388,608 次融合乘加，每 FMA 算 2 FLOP。分母是从提交到 GPU 排空的周期数，包含装载、循环和结果写回。表内速率由 RTL 仿真周期按 100 MHz 换算。

| FP32 循环展开 | 基线周期 | 新周期 | 基线利用率 | 新利用率 | 新 GFLOPS |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 1,266,577 | 1,242,080 | 82.79% | 84.42% | 1.3507 |
| 8 | 1,080,786 | 1,077,569 | 97.02% | 97.31% | 1.5570 |
| 16 | 1,068,163 | 1,066,246 | 98.17% | 98.34% | 1.5735 |

主要增益来自循环展开。K=4 的 INT8 寄存器基准执行相同数量的输出元素乘加指令，对应 33,554,432 次标量 MAC，所有 INT32 结果均为 65,536。按一次 MAC 含两次整数运算折算为 6.2939 GOPS。矩阵 MAC 数按已校验的程序推导，FP32 使用硬件计数器。[各次性能记录](performance)

## 缓存回归

全功能的 2、8、16 SM 各通过 11 个原有作业和 10 个专项用例。另覆盖 I/D 各自关闭、全部关闭、512/1024 字节缓存配 16 字节行以及 K=1、2。各结果文件存有 fault 地址、参与 SM、AXI 背压和计数。

- 两条指令行交替执行时，双 SM 配置的工作组产生 2 次 I-cache 缺失和 14 次命中，首行只观测到 1 次 AXI 读取。
- 所有 SM 首次读同一数据行时各发生 1 次填充，再读各命中。随后保持 DUT 不复位，同时修改同地址程序和数据，第二个作业的值从 15 变为 24。
- 同索引 A/B/A/A 访问产生 3 次缺失、1 次命中。SM0 在前一作业写入 42，下一作业只启用 SM1，将该行置为只读后连续读取并算出 84。
- 对只读区的半字写得到 fault 3，未产生 AXI 写。阻塞在途补行 R 响应并取消时，busy 保持，新的 doorbell 被拒；释放响应后可成功运行恢复作业。
- 64×48 的八个渲染场景通过独立 CPU 参考的覆盖、颜色、深度胜者和深度误差检查。该图形回归使用 I-cache，常规图形提交未声明 D-cache 只读区。[图像比较](graphics-64x48.json) · [仿真立方体](cube-rtl-64x48.png)

[双 SM](final2/features.json) · [8 SM](final8/features.json) · [16 SM](final16/features.json) · [小容量、K=2](finalalt/features.json) · [仅 D-cache、K=1](finaldc/features.json) · [仅 I-cache](finalic/features.json) · [全部关闭](off/features.json) · [交叉编译的 Linux 镜像清单](linux-image.json)
