# Morganite ISA 1.0

**简体中文** · [English](isa.md)

主机上传小端 32 位指令字。PC 按字寻址，相对跳转偏移以当前指令为原点，单位也是字。每 SM 最多容纳 1024 个指令字。寄存器可保存 32 位整数或 IEEE binary32 位模式，r0 恒为零，其余寄存器须先初始化。整数运算按模 2³² 回绕。每组从 PC 0 开始，仅实际存在的线程置位，TID 为组内编号。全部活动分拍完成后指令退休。四个 warp 槽允许独立算术重叠；访存和控制等待本 warp 先前算术操作；本 warp 的访存等待前序响应。

| opcode | 字段 | 语义 |
| --- | --- | --- |
| 0x33、0x13、0x37 | RV32 R、I、U | ADD/SUB、MUL 低位、移位、有/无符号 SLT、XOR/OR/AND、对应立即数形式、LUI |
| 0x03、0x23 | RV32 I、S | 自然对齐的全局 LB/LH/LW/LBU/LHU 和 SB/SH/SW |
| 0x0b、0x2b | 同 load/store | 相对工作组 scratchpad 起点的字节地址 |
| 0x43 | RV32 R4，fmt、rm 为零 | 融合 rD = rA × rB + rC |
| 0x53 | funct7 为操作 1–12，funct3 为零 | 一元运算的 rs2 为零。[浮点操作](fp32.zh.md) |
| 0x5b | rd 与 funct3，其余为零 | funct3 0–5 依次为 TID、GID、NTID、ARG、SMID、FFLAGS |
| 0x7b | funct3、有符号 imm12、需要时用 rs1 谓词 | funct3 0–7 依次为 EXIT、BAR、FENCE、JMP、BZ、SPLIT、NEXT、JOIN |

ARG 返回描述符提供的设备地址，FFLAGS 返回本工作组内该 warp 活动 lane 异常的累计按位或。比较产生 0/1，移位量取低 5 位。地址加有符号偏移时保留进位，再检查范围与对齐。全局和本地写支持按字节使能。scratchpad 初值未定义；冲突访问需同步，跨 warp/组的写须避免无同步重叠。FENCE 等待当前 warp 的先前访存，独立工作组通过作业边界同步。

SPLIT 保存父掩码和假分支线程，先执行真分支；全假直接进入 else。NEXT 切到假分支，假掩码为空则跳到 join。JOIN 恢复父掩码并弹栈，最多嵌套四层。BZ 遇全零跳转，全非零顺序执行，混合谓词报错。BAR 要求分歧栈为空，全部驻留 warp 到达同一静态屏障，先前 scratchpad 操作已完成。EXIT 也要求空栈。尾线程不访问内存、不更新标志。

fault 码 1–7 依次为非法作业、非法指令/目标、地址/对齐、重汇合/屏障、AXI 从设备/协议错误、地址译码响应、取消。代码错误给出指令设备地址，地址错误给出问题地址低 32 位。汇编伪指令 LI 展开为 LUI 和 ADDI。

```bash
python3 sw/asm.py asm examples/divergence.s build/divergence.bin
python3 sw/asm.py dis build/divergence.bin build/divergence.dis
bash scripts/test_sm.sh
```

整数沿用 RV32 字段，三源浮点保留单字编码；解码器、工作组与分歧 ABI、模型由 Morganite 实现。设计参考 [Ventus](https://github.com/THU-DSP-LAB/ventus-gpgpu)、[Radiance](https://github.com/ucb-bar/radiance) 和 [Vortex](https://github.com/vortexgpgpu/vortex)。
