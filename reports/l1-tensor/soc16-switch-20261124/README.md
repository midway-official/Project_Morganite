# 16 SM Morganite 与 Aurora 整机综合

Vivado 2023.2 在 `/home/fpga/morganite-soc16-switch-20261124/soc` 综合完整 U50C 设计，源提交为 `0141576`。目标 CPU/GPU 时钟为 100 MHz，HBM AXI 为 225 MHz。每个 SM 有 4 条 FP32 lane、8 线程 warp、4 个 warp 槽、8 KiB scratchpad、2 KiB 指令 L1 和 2 KiB 只读数据 L1。启用 BRAM 寄存器堆、直接跳转提前发射和 INT8 K=4。[构建清单](manifest.json)

| 综合层次 | LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU `u_cpu` | 112,373 | 40,085 | 61 | 0 | 8 |
| Morganite GPU `u_gpu_soc/gpu` | 337,983 | 136,819 | 256 | 0 | 576 |
| GPU 含板级接口 `u_gpu_soc` | 340,985 | 137,070 | 256 | 0 | 576 |
| 完整 SoC `u50c_top` | 461,569 | 184,818 | 321.5 | 0 | 584 |

以上数据取自同一次链接后的综合。GPU 两行具有包含关系。BRAM tile 按 RAMB36 + RAMB18 / 2 计。综合级 WNS 为 +2.250 ns、WHS 为 −0.473 ns。布线时序和实板功能分别验收。[层次资源](hierarchy_synth.rpt) · [器件利用率](utilization_synth.rpt) · [综合时序](timing_synth.rpt)

## 布线前的路径归属

布局后物理优化检查点 `u50c_top_physopt.dcp` 的最紧 setup 路径位于 100 MHz 域、SLR0 内，从 GPU 的 SM5 请求地址寄存器到 DMA 读通路的首错地址寄存器。裕量 +0.501 ns，32 级逻辑，数据路径 9.216 ns，其中逻辑 2.504 ns，预估布线 6.712 ns。16 SM 启用指令缓存时，每个方向的仲裁器连接 33 个请求源。若最终仍由该路径限制，先将仲裁结果、owner、掩码和地址锁存，再逐 lane 检查边界和选取首个错误。需要复测读写独立背压、越界请求不产生 AXI 事务及首错地址。[setup 路径](placement/max_examples.rpt) · [DMA RTL](../../../src/main/scala/morganite/Dma.scala)

该检查点有 1,659 条负 hold 路径，每个端点最多取一条。833 条起终点均在 GPU 核，437 条在 CPU，288 条在 HBM 桥，余下 101 条在其他层次或跨层次。最差一条从 HBM APB 到板级 `hbm_ready_pipe[0]`，裕量 −2.826 ns，数据路径 0.595 ns，时钟偏斜 3.198 ns。CPU 内最差路径是 LSU 的 CDB 副本到 store queue，跨 SLR0→SLR1，裕量 −0.176 ns。若最终保留短路径违例，先核查发射与捕获时钟、同步器及专用跨 SLR 资源，再定向调整布局或使用 hold 修复，并重新检查 setup。布线可以显著改变这些预估。[hold 路径](placement/min_examples.rpt) · [逐端点清单](placement/paths.tsv) · [检查点哈希](placement/checkpoint.txt)

布局时 SLR0、SLR1 分别占用 82.85%、56.36% 的 CLB，跨 SLR 使用 6,586 条连接。最紧 setup 路径的两端都在 SLR0。是否需要调整 SM 与 DMA 的布局，应检查最终路径中各段实际走线和局部拥塞。[布局资源](placement/u50c_top_utilization_placed.rpt)

在包含目标 DCP 的服务器上可复现同样的路径提取，输出记录 Vivado 版本、DCP 哈希和按 slack 排序的起终点。

```bash
vivado -mode batch -source board/timing_paths.tcl -tclargs "$checkpoint" "$out_dir"
```

## 在 WSL 重建输入

```bash
python3 scripts/stage_soc.py build/rebuild16/soc --sms 16 --accelerators
bash linux/build_userspace.sh
python3 linux/build_kernel.py --feature-register --initramfs-compression none
python3 scripts/stage_software.py build/rebuild16/software-board --sms 16 --gpu-features 0x403 --verify-load
```

Linux 使用与 boot-20 相同格式的未压缩 initramfs。本次封装的 `ram.dat` SHA-256 为 `e485601b9f774ecf65c04b61fdef5e6902dafba11dad04b75d240a65c36aca59`。已从镜像中提取并核对 CLI 和 21 份 GPU 程序、场景文件。板级装载按本镜像清单清零 BSS。重新编译时应使用新建镜像的哈希和装载范围。[软件与载荷清单](software.json) · [镜像与装载范围](image.json)
