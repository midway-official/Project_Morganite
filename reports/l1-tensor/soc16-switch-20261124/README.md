# 16 SM Morganite 与 Aurora 整机综合

Vivado 2023.2 在 `/home/fpga/morganite-soc16-switch-20261124/soc` 综合完整 U50C 设计，源提交为 `0141576`。目标 CPU/GPU 时钟为 100 MHz，HBM AXI 为 225 MHz。每个 SM 有 4 条 FP32 lane、8 线程 warp、4 个 warp 槽、8 KiB scratchpad、2 KiB 指令 L1 和 2 KiB 只读数据 L1。启用 BRAM 寄存器堆、直接跳转提前发射和 INT8 K=4。[构建清单](manifest.json)

| 综合层次 | LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU `u_cpu` | 112,373 | 40,085 | 61 | 0 | 8 |
| Morganite GPU `u_gpu_soc/gpu` | 337,983 | 136,819 | 256 | 0 | 576 |
| GPU 含板级接口 `u_gpu_soc` | 340,985 | 137,070 | 256 | 0 | 576 |
| 完整 SoC `u50c_top` | 461,569 | 184,818 | 321.5 | 0 | 584 |

以上数据取自同一次链接后的综合。GPU 两行具有包含关系。BRAM tile 按 RAMB36 + RAMB18 / 2 计。综合级 WNS 为 +2.250 ns、WHS 为 −0.473 ns。布线时序和实板功能分别验收。[层次资源](hierarchy_synth.rpt) · [器件利用率](utilization_synth.rpt) · [综合时序](timing_synth.rpt)

## 在 WSL 重建输入

```bash
python3 scripts/stage_soc.py build/rebuild16/soc --sms 16 --accelerators
bash linux/build_userspace.sh
python3 linux/build_kernel.py --feature-register --initramfs-compression none
python3 scripts/stage_software.py build/rebuild16/software-board --sms 16 --gpu-features 0x403 --verify-load
```

Linux 使用与 boot-20 相同格式的未压缩 initramfs。本次封装的 `ram.dat` SHA-256 为 `e485601b9f774ecf65c04b61fdef5e6902dafba11dad04b75d240a65c36aca59`。已从镜像中提取并核对 CLI 和 21 份 GPU 程序、场景文件。板级装载按本镜像清单清零 BSS。重新编译时应使用新建镜像的哈希和装载范围。[软件与载荷清单](software.json) · [镜像与装载范围](image.json)
