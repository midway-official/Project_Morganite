# WSL 工具链、冻结输入与回滚

[简体中文](build.md) · [English](build.en.md)

在仓库根目录执行。需要 WSL Ubuntu、JDK 17、sbt 1.9.8、Scala 2.13.12、Chisel 6.6.0 和匹配的编译器插件、CIRCT firtool 1.62.1 与 Verilator 5.050。Aurora 独立使用 Chisel 3.6.1。

```bash
git -C /home/midway/Aurora_AXI worktree add --detach \
    "$PWD/.worktrees/aurora" c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad
python3 scripts/prepare_baseline.py
bash scripts/check_tools.sh
bash scripts/smoke.sh
```

命令假定 Aurora 仓库位于 /home/midway/Aurora_AXI 且已保留板测证据；已有对应 worktree 可直接复用。CHISEL_FIRTOOL_PATH 指向 firtool 所在目录，VERILATOR 指向可执行文件，头文件和运行库应匹配版本。Linux x86-64 的 firtool 也随 Maven 工件 org.chipsalliance:llvm-firtool:1.62.1:linux-x64 分发。 smoke 用例在 8/2、8/4、8/8 三种线程/lane 比例下穷举全部 256 个掩码，检查空掩码、背压及相邻指令。

baseline/platform 保存本地工作区快照；集成采用 baseline/server 中实际 Vivado 2023.2 工程的 Tcl、板级 RTL、XDC 与独立复位控制器。厂商资料及声明在 baseline/vendor。prepare_baseline.py 使用 baseline/files.sha256、源码差异、UART 和历史请求校验输入，把对应的 CPU RTL、BIT、LTX、板卡文件和 Linux 镜像解包到 build/baseline；清零区间对应所选镜像。

重放原 CPU 基线时，在 FPGA VM 使用新目录。上传 build/baseline/ 为 inputs/、baseline/runtime/ 为 runtime/，另上传 scripts/start_baseline.py 与 baseline/rollback/test_uart.py。在该目录运行下列命令，第二条在 UART 桥就绪后执行。保存 launch.json、编程请求、UART 结果和管理日志。

```bash
python3 start_baseline.py "$PWD"
python3 test_uart.py 23251 "$PWD/linux-uart.txt"
```

[双 SM 发布与 RTL 复现](u50c-boot-20.md) · [16 SM 实板复核](u50c-16sm-20261124/README.md)
