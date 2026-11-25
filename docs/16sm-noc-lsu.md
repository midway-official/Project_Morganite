# Morganite 16 SM 访存互联与 LSU 实施方案

**简体中文** · [English](16sm-noc-lsu.en.md)

第一版采用四个 4 SM 簇、两级树形访存 NoC 和一个 GPU 专用 256 位 AXI4 master。后续保留同一套 LSU、链路和端点，把树根扩为两个可并行工作的 HBM 出口。SM、NoC 与 AXI4 master 工作在 100 MHz，HBM AXI 工作在 225 MHz。Aurora 通过 32 位 AXI4-Lite 提交作业。ISA、32 位设备地址、每 warp 8 线程、4 条物理 lane 和现有 GPU 程序作为兼容边界。以下队列深度和性能目标均为候选设计值。

## 为什么先选树

16 个 SM 的全局数据、指令 refill 和描述符读主要流向内存，scratchpad 属于各自 SM。当前一个 SM 的所有 warp 共用一套 `gather/send/awaitReply/writeRegs` 状态，数据和 scratchpad 共用这套状态，load 写回期间还阻挡其他 warp 发射。当前 DMA 最多仲裁 33 个来源（16 个数据端、16 个指令端、1 个作业端），全机只保留一个读 owner 和一个写 owner。只替换连接线，LSU 和端点仍会串行。[SM](../src/main/scala/morganite/Sm.scala) · [DMA](../src/main/scala/morganite/Dma.scala)

| 拓扑 | 对当前 16 SM 的影响 | 何时采用 |
| --- | --- | --- |
| 4 簇两级树 | 数据先在本地选择，跨簇边界传已合并的 32 B 行。源到单一内存端点经过两级仲裁。读返回按源标签定向分发。 | 第一版。按实际 SLR 位置放置簇和寄存切片，能独立定位拥塞。 |
| 簇级 ring | 邻节点连线规则，但指向同一个内存出口的包仍集中到出口附近的链路。跨多跳会增加小随机读延迟，共享环缓冲还会引入循环等待。 | 留作拓扑对照。采用 4 个簇节点加内存端点、独立请求和响应环、逐跳流控；每个环证明保留空槽或使用可证明无环依赖的逃逸通道。比较时固定端口数、链路宽度、队列总容量和工作负载。 |

当前位流的最差 setup 为 GPU 全局 fault 到 SM10 错误地址，延迟 9.534 ns，其中 8.553 ns 为布线，途中三次跨 SLR。100 MHz 域 setup/hold 余量仅 +0.062/+0.007 ns，最差 hold 属于 CPU LSU。宽仲裁器宜局部化，分组寄存 stop 信号并本地锁存首错，DMA 接纳点阻止故障后的新请求，已接纳事务继续排空。布线后检查每个簇的路径、拥塞及 CPU hold；先用布局报告决定 SLR 分组。[关键路径](u50c-16sm-20261124/physical/timing_impl.rpt) · [分区资源](u50c-16sm-20261124/physical/resources.json)

## 数据与流控

```text
每个 SM：4 个 warp → LSU 收集地址和掩码 → 32 B 行请求 ─┐
                     scratchpad → 本地回复            │
每簇：4 个 SM 的数据请求和 I-cache refill → 寄存仲裁/FIFO ─┤ ×4 簇
作业控制器：描述符请求 ─────────────────────────────┤
                                                  ↓
                                 按地址选出口，按来源轮询
                                                  ↓
                         AXI ID 表 → 256 位 AXI4 master
                                                  ↓
                         时钟转换 → AXI4/AXI3 → HBM
返回：R/B → ID 查表 → 簇和源端点 → LSU 槽 → lane 写回/写完成
```

- **簇内链路。** 读请求只传地址、操作、长度和标签；写请求另有 256 位数据和 32 位字节使能。返回数据和写完成有独立缓冲。每个仲裁边界先放两项可背压 FIFO 并寄存反压路径。请求头、写数据和响应保持关联。描述符和 I-cache refill 获得有界仲裁机会，连续长写应按有限长度切换。接纳读之前预留返回容量，接纳写之前预留写数据和完成容量，防止等待 RF 写回的 warp 堵死整个网络。
- **标签和复用。** 内部标签标识作业代、簇、SM、请求类别、LSU 槽、子请求；每个 AXI 端口单独分配外部 ID。端点表保存源标签、剩余 beat 和错误状态，读至最后一拍、写至 B 后释放外部 ID。LSU 槽直到全部子请求回收、写回或丢弃完成才复用。首版每端口 8 笔读、4 笔写，另做 1、4、8、16 笔读对照。窗口大小由实测往返延迟与平均 burst 长度决定，近似所需在途数为目标字节每拍 × 往返拍数 ÷ 每 burst 有效字节。
- **返回顺序。** 不同 ID 的读可交错返回，同 ID 按 AXI 顺序；AXI4 的 W 没有 WID，端点以已排队的 AW 顺序提供整笔 W，独立保持 AWVALID/WVALID，支持 W 先握手。为最慢的 R/B 返回保留容量并施加独立背压。现有宽侧转换链是 ID-less；新端点应生成带 ID 的 CDC 和协议转换配置，核对生成 IP 的实际并发能力、AXI3 ID/WID、最大 16 拍拆包及拆包 B 汇总。若实际桥只能顺序服务，先测同 ID 多笔顺序流水，再评估该桥能否作为多 ID 实现。[现有 SoC 构建输入](u50c-16sm-20261124/artifacts/soc-inputs.tar.gz) · [AMBA AXI 规范](https://documentation-service.arm.com/static/5f915b62f86e16515cdc3b1c)

## LSU 和数据合并

1. **允许不同 warp 并发。** 每 SM 先实现两个、再对照四个全局指令槽，每个 warp 最多一条待完成访存。一个槽保存 warp、目的寄存器、有效掩码、宽度/符号、子请求完成位、异常和返回数据。地址仍可用一套 4-lane 收集器分两拍生成，收集完即可处理另一个 warp。保持发起访存的 warp 锁定直到完成；其他就绪 warp 可发射。全局槽占满时，本地 scratchpad 仍可接收其能服务的请求。工作组 barrier 等待参与 warp 的先行 scratchpad 操作，fence、退出、取消及新作业边界按原语义排空。[向量接口和 scratchpad](../src/main/scala/morganite/VectorMemory.scala) · [warp 发射与写回](../src/main/scala/morganite/Sm.scala)
2. **在 SM 一侧合并。** 用首个有效 lane 选取 32 B 对齐行，对 8 个有效 lane 生成同行掩码；每个不同的行重复一次，保存 byte offset、粒度及目的 lane。对齐的 8×FP32 读或写成为一个 beat；同址 load 读取一次并广播；稀疏写用精确 WSTRB。首版每个 warp 独立合并，重叠字节写按现有 lane 顺序串行，部分重叠子字写定向验证。单 beat 表示一条行，连续多行 burst 作为下一项独立优化，可先用于显式连续搬运；普通 warp 单次最多提供 32 B 连续有效数据。
3. **先检查整条指令，再放行任何子写。** 地址与末字节保留扩展位，按活跃 lane 检查 DMA、代码、只读窗口和自然对齐。仅在完整 32 B 均可读时执行扩展行读或缓存填充。沿用当前页对齐 DMA 分配；对于只有部分行被授权的读，走经验证的合法窄访问或在无法安全转换时返回明确定义的 fault。禁止简单把窄读地址向下取整送 HBM。多拍 burst 在 4 KiB、权限窗口、HBM 出口边界处分段。
4. **维持 BRAM RF 的现有写端口。** 每 SM 的完成槽缓冲 load 数据，把 8 lane 拆成两个 4-lane 写回拍。当前 ALU/FMA 写回延迟固定，不能靠随意阻挡末级来让位。用发射端写回预约或在 load 等待达到阈值时暂停新算术发射，让已发算术排空并为 load 留出写回拍。最后一个有效分拍提交后解锁 warp。验证连续 FMA 与 load 并行时无漏写、无饥饿，记录因写回争用损失的发射拍。[BRAM RF](../src/main/scala/morganite/LaneRegisters.scala)
5. **保留只读 L1 协议。** 先让原有每 SM 的 I/D cache 和旁路都通过新端点，随后把 D-L1 扩展为按实际活跃 lane 分行；现实现要求 lane 0 有效并仅识别部分广播/连续模式。给每个 miss 带标签，第一版可让 miss 串行、旁路独立排队；性能仍受限时再做两个 miss 槽和同一不可变行的 miss 合并。跨作业先排空 refill 再失效。scratchpad 同 bank 同字读可单独加入广播，按每 lane 的字节偏移解包，用 N-body 测其收益。[只读缓存](../src/main/scala/morganite/ReadOnlyL1.scala)

## 实施与验收

| 顺序 | 主要修改和交付 | 本阶段验收 |
| --- | --- | --- |
| 1. 基线和接口 | 在 GpuParams、Generate、Board、stage_soc 中独立描述主机口、GPU 口、HBM 出口数和在途深度。清单从有效配置生成。扩展 sim/gpu.cpp 的 AXI 模型，使其支持 256 位、独立 R/W 背压与多 ID。 | 原 32 位配置逐项回归，1/2/4/16 SM 参数合法性检查。固定同一 GPU 二进制、只读窗口及参考图，保存基线指标。 |
| 2. 原生宽端点 | 改 Dma、VectorMemory、板级 SV 与生成 Tcl。先跑单端口、每向一笔在途、一个 LSU 槽。保留 CPU/JTAG 原路径至 SAXI_00，GPU 用独立端口。 | 满掩码对齐的 8×FP32 在 AXI4 线上为一个 32 B beat。穷举 8 位 lane mask，检查读分发、窄写 WSTRB、越界零副作用及错误回收。 |
| 3. 树与事务表 | 四簇仲裁、独立返回路由、端点 8R/4W。端点先用合成流量源和多个 SM 的旧 LSU 压测。 | 随机 ID 乱序、长背压、AW/W 独立握手、SLVERR/DECERR、取消后排空。每个已接纳请求恰好一个终结响应，记录每簇最大等待和峰值在途。 |
| 4. 并发 LSU | 改 Sm、全局向量接口、DataL1 包装、load 写回。以相同树和端点比较每 SM 1/2/4 个槽。 | RAW/WAW、先写后读、部件间争用、barrier、scratchpad、尾 warp、作业切换、满 FMA 写回通过。对可同时发请求的 warp 验证同一 SM 实际出现两笔在途。 |
| 5. 单口完整 SoC | 全频布线、Linux 驱动复用原 DMA 交换窗口。验 CPU 写 GPU 读、GPU 写 CPU 读、JTAG 并发、不相交地址与连续作业。 | 100/225 MHz 所有受约束域 setup/hold 通过，路由及 DRC 错误为零；列每 SLR 的 CPU/GPU/整 SoC LUT/FF/BRAM/URAM/DSP、CDC、时序和首错。输出原始图像、作业计数与实际 GPU 周期。 |
| 6. 双出口和分 PC | 在簇出口按设备地址选端口，用每出口独立队列避免队首阻塞；保留任一 SM 访问任一合法缓冲的能力。扩展驱动的分配/上传/下载 ABI，保留原交换缓冲，用显式授权的 HBM 区承载大数据。 | 用同一端口映射测同 PC 冲突和不同 PC 并行，覆盖跨端口同址可见、写排空、CPU/JTAG 对照。双口性能单报。 |

现有 Linux coherent DMA 分配为 2 MiB，位于 PC0。板测诊断按地址高四位选择 16 个 256 MiB 伪通道。第二个 HBM 入口要实测能访问哪些地址，并让缓冲分布到不同 PC。高位 HBM 缓冲可由 GPU 在已授权窗口和主机交换缓冲间搬运；若供 CPU 直访，另验证 LA32R 可达性、uncached 映射、缓存别名以及 Linux 分配器的隔离。首版单口仍可用现有窗口验证正确性。[实际 DMA 范围](u50c-16sm-20261124/results/board-01/dma-mapping.json) · [HBM 诊断](../baseline/runtime/hbm_diag.tcl) · [AMD PG276](https://docs.amd.com/r/en-US/pg276-axi-hbm)

## 怎样决定下一步

每端口分别计数 AR/AW、R/W 数据握手、WSTRB 有效字节、峰值和平均在途、首数据延迟、背压、每簇等待。LSU 记录槽满周期、等返回周期、写回等待、每 warp 不同行数、合并前后有效字节和 scratchpad bank 冲突。在 GPU 及 HBM 时钟域各自采样，用同步快照读取计数。用 AXI 模型验证计数语义；板上按方向报告接口传输和应用有效带宽。

- **单口带宽。** 256 位 × 100 MHz 的单方向上限是 3.2 GB/s，HBM 侧 256 位 × 225 MHz 的单接口上限是 7.2 GB/s。先做生成器的连续对齐读、写、读写混合，再跑真实 SM 的同类内核。建议把连续单向 ≥1.6 GB/s 作为首轮工程目标，使用至少 1 MiB 数据、核对回读或校验和，单列预热和 GPU 周期。短 gather 使用每秒完成请求数及延迟衡量。
- **应用收益。** 新程序配只读窗口的历史实板 GPU 整帧时间为 512×384 光追 42.02 ms、1024×768 曼德博 928.51 ms。固定程序、图像、SM mask 和提交模式做成对比较；记录 1 SM 和 16 SM、关键计数及像素误差。优化版 Julia、N-body 的完整实板基线需补齐；只读提交曾遇到用户态 SIGSEGV，比较用同一可重复完成的提交方式。每次仅改变宽度、在途数、LSU 槽或出口数中的一个，拓扑变化另设对照。[历史逐作业数据](u50c-16sm-20261124/results/stress.json) · [优化程序的实板结果](u50c-16sm-opt-20261125/readonly/stress/results.json)
- **双口取舍。** 单口长期数据拍占用高、簇持续排队且有独立 PC 流量时，再比较双出口。若总线多数拍空闲且 LSU 槽满，则先按延迟分布调在途数、miss 处理与写回。若写满 256 位而读多为少量有效字节，优先优化重复读和行利用率。环网只有在同工作负载、同端口下获得更佳的完整 SoC 布线余量或应用耗时，才进入板级候选。
