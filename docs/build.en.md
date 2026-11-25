# WSL toolchain, frozen inputs and replay

[简体中文](build.md) · [English](build.en.md)

Start at the repository root in WSL Ubuntu with JDK 17, sbt 1.9.8, Scala 2.13.12, Chisel 6.6.0 and its matching compiler plugin, CIRCT firtool 1.62.1 and Verilator 5.050. Aurora has a separate Chisel 3.6.1 build.

```bash
git -C /home/midway/Aurora_AXI worktree add --detach \
    "$PWD/.worktrees/aurora" c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad
python3 scripts/prepare_baseline.py
bash scripts/check_tools.sh
bash scripts/smoke.sh
```

These commands assume an Aurora clone with the board evidence at /home/midway/Aurora_AXI. Reuse the worktree if it already exists. Set CHISEL_FIRTOOL_PATH to the firtool directory and VERILATOR to the simulator executable. Use the matching headers and runtime. Linux x86-64 firtool is also distributed as org.chipsalliance:llvm-firtool:1.62.1:linux-x64. The smoke test exhausts 256 masks at 8/2, 8/4 and 8/8 thread/lane ratios and checks empty masks, stalls and consecutive instructions.

baseline/platform holds the local board snapshot. Integration uses baseline/server for the Vivado 2023.2 project Tcl, board RTL, XDC and separate reset controller. Vendor files and notices live under baseline/vendor. prepare_baseline.py verifies baseline/files.sha256, CPU source differences, UART and the archived request. It extracts the frozen CPU RTL, BIT, probes, board files and Linux image to build/baseline. Zero ranges belong to the selected image.

To replay the original CPU baseline, use a fresh FPGA VM directory. Upload build/baseline/ as inputs/, baseline/runtime/ as runtime/, plus scripts/start_baseline.py and baseline/rollback/test_uart.py. Run the second command after the UART bridge is ready. Retain launch.json, the programming request, UART result and manager logs.

```bash
python3 start_baseline.py "$PWD"
python3 test_uart.py 23251 "$PWD/linux-uart.txt"
```

[Two-SM release and RTL reproduction](u50c-boot-20.en.md) · [16-SM board archive](u50c-16sm-20261124/README.en.md)
