# U50C 第 20 次实板测试与复现

标签 `u50c-boot-20` 对应一次完整的 FPGA 重新编程和 Linux 启动。板卡管理器的实际运行目录是 `/home/fpga/aurora-u50c-debug/runs/20261123T203810.557293Z`。UART、作业计数器和图形原始数据均取自这一次运行。[编程请求](../reports/bringup/f2a3ce0/board-test-20/request.json) · [管理器日志](../reports/bringup/f2a3ce0/board-test-20/manager) · [Linux 测试结果](../reports/bringup/f2a3ce0/board-test-20/uart-result.json)

## 硬件与驱动版本

| 组件 | 本次使用的提交或 SHA-256 |
| --- | --- |
| Aurora CPU Chisel 源码 | `5ec35882a6eb4040631c1cf9d322a754bd435907`，板测证据提交 `c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad` |
| Morganite GPU 和板级接口 Chisel 源码 | `f2a3ce064e4149b14a4acef1e662685f5d8bc17e` |
| 驱动、CLI、GPU 程序打包脚本和本次软件清单 | `f27299ce9489877c7dec09ecae1c0438c684f99f` |
| LA32R Linux 基础提交 | `fbd87836058ae5e676bf97463076916a18c94ad7` 加仓库中的内核补丁 |
| 合成输入 `Aurora.v` | `82c673147ce734b2662b3dc5e740377913c594f039102a1c83033a2440d409f1` |
| 合成输入 `MorganiteBoard.sv` | `09ca005e7fa98c0ff462e797cf494f5f0d56460c1eed2942020056c3636615dc` |
| 实际编程的 `u50c_top.bit` | `0acedd3bc9b6f4aaa0ee56d1426dd6614723d7e5bf6a76dbea12148bfa3d9fc0` |
| 对应 `u50c_top.ltx` | `7fc80573b73bdeb17e362bdc449c5d55cbdcaa54844c146a92ed1753e7320d88` |
| 实际装载的 Linux `ram.dat` | `dbe0e2ae8dc25eb33835f53de85cd5ce981954d26746816763c776bf4f07fa7c` |

源码目录 `cpu/aurora` 包含冻结 CPU 的全部 32 个可综合 Scala 文件及其构建定义。GPU 源码位于 `src/main/scala/morganite`，FP32 数据通路引用 `third_party/hardfloat`。发布时使用这两套 Chisel 源码分别生成 RTL，与上表两个合成输入逐字节一致。Linux 的设备树节点、内建驱动、UAPI、CLI 和架构补丁位于 `linux/`。编程请求中的哈希标识本次位流；构建清单内的回滚位流属于原 Aurora 基线。[硬件清单](../reports/bringup/f2a3ce0/board-test-20/build.json) · [软件清单](../reports/bringup/f2a3ce0/board-test-20/software.json) · [内核配置](../releases/u50c-boot-20/linux.config)

每个 SM 有 4 个驻留 warp、每线程 32 个 32 位寄存器和 8 KiB 工作组 scratchpad。CPU 和 GPU 共享 32 位 AXI4 至 HBM 桥，GPU 数据访问采用非嗅探 DMA。Linux 通过 `0x1fe20000` 的 AXI4-Lite 寄存器提交作业，按序号轮询完成。驱动在 probe 时调用 `dmam_alloc_coherent`，把返回的地址和长度写入 GPU 的 DMA 窗口。LA32R 的分配路径先清零 cached 映射，再执行 D-cache 写回和失效，随后把 uncached 映射交给驱动。本次实际分配区间为 `[0x01c00000, 0x01e00000)`，CPU 映射起点为 `0x81c00000`；程序每次从驱动读取地址。[驱动](../linux/morganite.c) · [D-cache 补丁](../linux/la32r-dma.patch) · [实际映射](../reports/bringup/f2a3ce0/board-test-20/dma-mapping.json) · [Linux 资源表](../reports/bringup/f2a3ce0/board-test-20/linux-resources.txt)

## 实板结果

| 验证项 | 第 20 次的观测 |
| --- | --- |
| 编程与启动 | 外设读写通过。16 个 HBM 伪通道各验证 2 种模式、每种 128 个字。CPU 保持复位时 JTAG 比对 15,503,424 字节镜像和 508,616 字节清零区；释放后进入 `loongarch32r` shell |
| 软件身份 | 板上 CLI、全部 GPU 程序和场景哈希匹配，GPU 报告 ABI 1.0 和 2 个 SM |
| 作业 | 序号 1 至 130 连续，全部完成，硬件 fault 数为 0。第 1 次为 DMA 回环，第 2 至 101 次交替执行整数和 AXPY，各 50 次，每次校验 256 个结果 |
| 可见性与并发 | 第 102 次由 SM0 完成 8 个组，第 103 次由 SM1 完成 8 个组并消费前一作业写入的数据。第 105 次执行期间记录了 CPU 活动和 JTAG 在独立地址 `0x20101000` 的读写 |
| 算力与共享通路 | 每轮执行 8,388,608 个 FMA。同一 BIT 限用 1 个 SM 时为 2,532,830 周期，启用 2 个时为 1,267,754 周期，即 **1.323381 GFLOPS、1.997888 倍**。拷贝内核的有效读写载荷合计 5.460 MB/s |
| 提交与轮询 | 50 个整数作业的主机提交至完成中位数为 517.835 µs，GPU 计数中位数为 18.310 µs。50 个 AXPY 对应 4,013.605 µs 和 562.305 µs |
| 图像 | 八个 320×240 场景共 614,400 像素。覆盖和深度胜者逐像素匹配。RGBA8 单通道最大差 1，FP32 深度最大绝对误差 1.78814×10⁻⁷。共享边、等深和退化用例的深度均精确匹配 |
| 帧时 | 两个 12 三角形立方体视角的 GPU 三阶段累计用时各为 600.524 ms、610.229 ms。Linux 渲染命令含上传和读回各为 2.390 s、2.395 s |

实板测试程序先校验下载文件的 SHA-256，再由 WSL 独立光栅器比较颜色、FP32 深度及图元编号。颜色与深度 PNG 供查看，`color.rgba`、`depth.f32`、`winner.u32` 为原始回读。每个场景的 `reference/` 下是 CPU 参考。[UART](../reports/bringup/f2a3ce0/board-test-20/linux-uart.log) · [作业及各 SM 计数](../reports/bringup/f2a3ce0/board-test-20/jobs.json) · [吞吐](../reports/bringup/f2a3ce0/board-test-20/performance.json) · [并发观测](../reports/bringup/f2a3ce0/board-test-20/concurrent.json) · [八个场景的误差统计](../reports/bringup/f2a3ce0/board-test-20/frames/comparisons.json)

这份动态 DMA 镜像有一次完整实板记录。相同位流上，boot-09、15、18 使用同一固定 DMA 池镜像，均单独重新编程并通过测试。历史上的 TLB refill 地址掩码错误已定位到 Linux。另一次间歇性解压损坏在纯 CPU 位流上复现，CPU/缓存与 Linux 执行上下文之间的具体根因仍未定位。[历次对照试验](../reports/bringup/f2a3ce0/diagnosis.md)

## 布线后资源与时序

| Vivado 统计范围 | CLB LUT | FF | BRAM tile | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Aurora CPU，`u_cpu` | 104,716 | 40,037 | 61 | 0 | 8 |
| 双 SM GPU，`u_gpu_soc/gpu` | 40,305 | 15,287 | 16 | 0 | 40 |
| 完整 SoC，含互连、HBM 和调试逻辑 | **153,478** | **63,792** | **81.5** | **0** | **48** |
| 原 CPU 基线 SoC | 112,771 | 48,279 | 65.5 | 0 | 8 |

器件为 `xcu50-fsvh2104-2-e`。上表五类资源均位于 SLR0。全设计 WNS +1.158 ns、WHS +0.010 ns；100 MHz CPU/GPU 域为 +2.090/+0.010 ns，225 MHz HBM AXI 域为 +1.158/+0.019 ns。TNS、THS、脉宽违规、布线错误及 DRC 错误均为零。151 条 DRC warning、CDC 检查、HBM_CATTRIP 的外部输出延时例外以及基线对照见原始报告。[分 SLR 资源](../reports/resources/f2a3ce0-route/summary.json) · [物理报告及已知告警](../reports/physical/README.md)

## 校验和恢复发布包

在仓库根目录运行。软件包内含测试时装载的 Linux 镜像、GPU 指令和场景、板卡管理脚本及各文件的 SHA-256。硬件输入包内含两份 RTL、实际使用的板级顶层、Tcl、XDC 和板卡描述文件。[归档与校验和](../releases/u50c-boot-20)

```bash
rel=releases/u50c-boot-20
out=build/replay-boot-20
mkdir -p "$out/soc" "$out/sw"
(cd "$rel" && sha256sum -c SHA256SUMS)
tar -xzf "$rel/soc-inputs.tar.gz" -C "$out/soc"
tar -xzf "$rel/linux-board.tar.gz" -C "$out/sw"
(cd "$out/soc" && sha256sum --quiet -c files.sha256)
(cd "$out/sw" && sha256sum --quiet -c files.sha256)
gzip -dc "$rel/u50c_top.bit.gz" > "$out/u50c_top.bit"
sha256sum "$out/u50c_top.bit" "$rel/u50c_top.ltx" "$out/sw/ram.dat"
```

### 从 Chisel 生成相同的 RTL

以下命令沿用上面的 `out`。CPU 工程固定 sbt 1.9.7、Scala 2.13.12 和 Chisel 3.6.1；GPU 工程的工具路径由 `scripts/toolchain.sh` 设置。也可以通过 `CHISEL_FIRTOOL_PATH` 和 `VERILATOR` 指定对应版本。两个 `cmp` 均在 WSL 复核通过。

```bash
cp -a cpu/aurora "$out/aurora-src"
(cd "$out/aurora-src" && sbt 'runMain aurora.AuroraMain ../cpu-rtl')
cmp "$out/cpu-rtl/Aurora.v" "$out/soc/inputs/generated/Aurora.v"
source scripts/toolchain.sh
sbt "runMain morganite.Generate MorganiteBoard $out/gpu-rtl 2 8 4"
cmp "$out/gpu-rtl/MorganiteBoard.sv" "$out/soc/fpga/u50c/rtl/MorganiteBoard.sv"
```

### 修改 GPU 程序或驱动

GPU 程序使用独立汇编器。驱动的设备节点是 `/dev/morganite`。CLI 支持 `info`、`loopback`、`selftest`、`bench` 和 `render`。以下命令在已启动的 LA32R Linux shell 中运行。

```sh
/bin/mgpu info
/bin/mgpu loopback
/bin/mgpu selftest 100
/bin/mgpu render /gpu/cube_a.scene /tmp/frame
```

从源码重建镜像需要 LA32R 交叉工具链、Linux 基础提交和 Aurora 固件。构建脚本使用本地 Aurora 工作树中的冻结固件与基础 initramfs；`--source` 和 `--prefix` 可指定 Linux 仓库及交叉工具链。内核配置中使用未压缩的 cpio。新编译镜像具有自己的构建时间和清单，重放本次镜像直接使用归档的 `ram.dat`。

```bash
git -C /home/midway/Aurora_AXI worktree add --detach "$PWD/.worktrees/aurora" c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad
bash linux/build_userspace.sh
python3 linux/build_kernel.py --initramfs-compression none
python3 scripts/stage_software.py --verify-load build/sw-rebuilt
```

服务器重新实现时，在独立目录解开 `soc-inputs.tar.gz` 后运行其中的 `bash run_soc.sh`，脚本使用 Vivado 2023.2。板测入口是软件包内的 `start_test.py <构建目录> <软件目录> <新的结果目录>` 和 `uart_jobs.py <结果目录> --full`。前者核对本次 BIT、LTX 和镜像，依次编程、检查外设与 HBM、装载镜像及其 BSS；后者在 UART shell 中提交 GPU 作业并取回图像。编程请求保存在逻辑结果目录，实际管理器目录由 `launch.json` 标明。[板测入口](../board/start_test.py) · [UART 测试](../board/uart_jobs.py)
