# Morganite

Morganite is a Chisel SIMT GPU for the Aurora LA32R Linux SoC on the Alveo U50C.
The board configuration uses two SMs with four physical lanes per SM and an
eight-thread warp. The CPU/GPU fabric targets 100 MHz and the HBM AXI domain
targets 225 MHz. Development and test evidence are under `reports/`.

## WSL build

```bash
git -C /home/midway/Aurora_AXI worktree add --detach \
    "$PWD/.worktrees/aurora" c0dd8317e1fbded3f76d4d49fdb86fe2ce28c6ad
python3 scripts/prepare_baseline.py
bash scripts/check_tools.sh
bash scripts/smoke.sh
```

Use JDK 17, sbt 1.9.8, Scala 2.13.12, Chisel 6.6.0 with its matching full
compiler plugin, CIRCT firtool 1.62.1, and Verilator 5.050. On another WSL
installation set `CHISEL_FIRTOOL_PATH` to the directory containing that
firtool binary and `VERILATOR` to the Verilator executable. The Linux x86-64
firtool binary is also available in the Maven artifact
`org.chipsalliance:llvm-firtool:1.62.1:linux-x64`. The simulator uses the
headers and runtime installed with the selected Verilator. The frozen Aurora
RTL has its own Chisel 3.6.1 provenance.

## U50C inputs

`baseline/platform` preserves the local board worktree snapshot.
`baseline/server` contains the build Tcl, board RTL and XDC copied from the
Vivado 2023.2 project named in the archived Vivado log. Use the server files for
integration. They include the separate reset controller. The local snapshot
contains older build instructions and validation benches. The vendor board
files and XDC archives are in `baseline/vendor` with their supplied notices.

`baseline/files.sha256` locks the copies. `prepare_baseline.py` verifies the
archive, the input files, the CPU source difference between the two frozen
commits, the UART transcript, and the archived board request. It extracts the
exact processor, BIT, LTX, board files and checked Linux image to
`build/baseline`. The manifest records the image-specific zero ranges.

For a baseline replay allocate a fresh directory in the FPGA VM. Upload
`build/baseline/` as `inputs/`, `baseline/runtime/` as `runtime/`,
`scripts/start_baseline.py`, and `baseline/rollback/test_uart.py`. Invoke
`python3 start_baseline.py "$PWD"` there. The existing managed U50C session
serializes programming, peripheral/HBM tests and image load. The exact
hardware run is in `launch.json`. When the bridge is running, invoke
`python3 test_uart.py 23251 "$PWD/linux-uart.txt"` on the VM and retain the
transcript, result JSON, request and hardware logs. The server uses Vivado
2023.2. The baseline image in the request is specific to the archived CPU.

The smoke bench exhausts all 256 masks at each of 8/2, 8/4 and 8/8 thread/lane
ratios. It compares beat indices, lane masks and final-beat flags, including
empty masks, stalled transfers and adjacent instructions.
