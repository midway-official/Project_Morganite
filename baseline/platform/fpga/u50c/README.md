# Frozen Tick2h / U50C / HBM SoC (100 MHz CPU target)

This bring-up target uses the supplied AU50 1.3 board files and XDC with a
Linux Vivado 2019.2 installation. The configured part is
`xcu50-fsvh2104-2-e`; the scripts select cable serial `50241A20256MA` and
recognize the XCU50 scan-chain family (`xcu50_u55n_0` in the 2026-10-26 probe).
The CPU is the accepted frozen Tick2h hardware RTL, SHA-256
`0834d66dffcf2a403b56dde16f6e959dbf5f9924103c0b15c37447d12cdb7d52`.
The board target is under validation. Current evidence and remaining gates
belong in `reports/2026-10-26-u50c-tick2h/`; the historical CPU-only acceptance
does not qualify this HBM SoC. Local simulation must pass before FPGA testing.

## Hardware

- `SYSCLK3_P/N` at 100 MHz (`BB18/BC18`) feeds one `IBUFDS` and one `BUFG`.
- Clocking Wizard outputs the 100 MHz CPU/JTAG clock and 225 MHz HBM AXI clock.
- Aurora and JTAG-to-AXI share a serialized two-master AXI4 fabric.
- Memory uses one 4 GiB HBM stack through a 32-to-256-bit Always Packing width
  converter, an asynchronous clock converter, and an AXI4-to-AXI3 protocol
  converter. The latter splits long bursts; the HBM endpoint gets aligned
  32-byte accesses. The U50 has 8 GiB total, but the minimal target enables
  only the left 4 GiB stack to reduce first-bring-up scope.
- A virtual 16550 at `0x1fe001e0` captures TX and injects RX over JTAG.  UART
  interrupt output is connected to Aurora external interrupt line 1.
- The CPU remains in reset until the HBM initialization signal is synchronized
  and the host writes the run control bit.
- Stopping the CPU first prevents new AXI addresses, drains accepted transfers,
  and then asserts CPU reset. Status bit 4 reports that the CPU is held reset.
- The HBM CATTRIP signal reaches J18 directly, without masking it with HBM-ready.
- Reset remains asserted for 4096 cycles of the 100 MHz reference clock after
  the Clocking Wizard locks, then deasserts synchronously in each clock domain.
- VIO cores expose clock/reset/HBM status and sticky HBM AXI channel handshakes
  so a failed JTAG transaction can be localized after the timeout.  Their safe
  outputs can hold the CPU in reset and clear the sticky handshake history.

The HBM IP and Clocking Wizard input buffers are disabled because the top level
already owns the differential input and global clock buffers.

## Build

```bash
BUILD_BITSTREAM=0 JOBS=2 ./fpga/u50c/build.sh  # Create IP models first
python3 fpga/u50c/tests/validate_local.py   # Four simulations + board-top RTL elaboration
# After local validation, build the complete board implementation:
BUILD_BITSTREAM=1 JOBS=2 ./fpga/u50c/build.sh
```

Set `VIVADO_ROOT` when Vivado is not installed at `/opt/xilinx/Vivado/2019.2`.
The build needs Python 3 and unzip, and reads these repository-root archives:

| Input | SHA-256 |
| --- | --- |
| `au50_boardfiles_v1_3_20211104.zip` | `8f449d6b719bd97360e6db9c966f04f603cb2a09cff82f330d236c01d2accc5b` |
| `alveo-u50-xdc_20210505.zip` | `84bb3d625974f8453a6bd19dcd515b7a5510798022a56c7a1e6401520ff72828` |

`prepare_tick2h.py` verifies the accepted source/RTL identity and board/Linux
input hashes, and decompresses the frozen hardware RTL into this target's build
directory. It writes `input-manifest.json` with the actual SoC source hashes.
The board build does not regenerate the processor from a changing workspace.
Synthesis uses `Flow_PerfOptimized_high`; implementation uses
`Performance_ExplorePostRoutePhysOpt`. Timing and DRC still have to pass for the
resulting board design; a strategy name is not evidence of timing closure.
Full implementation requires a current passing `LOCAL_VALIDATION.json`.
`validate_build.py` then checks final setup/hold/pulse-width timing, route/DRC,
the actual HBM/IP configuration, and input/bitstream identities. Its passing
result is `VALIDATED_BUILD.json`; this still precedes physical FPGA validation.

The interface simulation uses the actual generated AMD conversion IP models
and a strict behavioral HBM AXI3 endpoint. The short boot simulation adds the
unchanged frozen CPU and loads the 308-byte Linux reset stub through the host
AXI port. It is separate from full Linux boot and physical HBM training.
Chisel's existing simulation initialization macros can initialize unreset state
with zeros or random values using `--initialize zero` or `--initialize random`.
The cached smoke program also exercises byte/halfword stores, LL/SC, a real
UART RX interrupt and ERTN, cache writeback, and two CPU stop/restart cycles.
The debug AXI master is not cache coherent: software must clean dirty data
before the host checks backing HBM while retaining CPU-written results.

The bitstream is written to
`fpga/u50c/build/vivado/aurora_u50c.runs/impl_1/u50c_top.bit`.  The build also
writes synthesis/implementation utilization, timing, DRC, and clock-interaction
reports under `fpga/u50c/build/`.

## Remote JTAG

For repeatable Linux/WSL access, run `./fpga/u50c/scripts/remote_jtag.sh probe`.
It imports the operator's local ZIP if needed, starts the restricted SSH
tunnel, and enumerates U50C without programming it. See
[REMOTE_ACCESS.md](REMOTE_ACCESS.md) for setup, status, shutdown, troubleshooting,
and the verified 2026-10-26 connection details.

Obtain the hardware server account, SSH key and port from the board operator.
The local access archive `FPGA_JTAG_CLIENT.zip` is ignored and must remain
outside commits. Forward the operator's remote loopback hardware-server port
to the local Linux client before using the scripts:

```bash
ssh -N -T -i <ssh-key> -p <ssh-port> \
  -L 127.0.0.1:13121:127.0.0.1:<hw-server-port> \
  <user>@<host>
```

The 2026-10-26 access check found a Vivado **2023.2** hardware server already
running in the remote Ubuntu VM at loopback port **3122**. The supplied access
package recommends a 2023.2 client. Local Vivado 2019.2 also passed JTAG
enumeration, which does not establish compatibility for programming or debug
cores. The following is a reference command for the operator when the service
is absent; do not start a second server over an active cable:

```bash
<vivado-install>/bin/hw_server -d \
  -L<log-directory>/hw_server-u50c.log \
  -s TCP:127.0.0.1:<hw-server-port>
```

Probe, program, load the NEMU-validated image, and open the JTAG console:

```bash
source /opt/xilinx/Vivado/2019.2/settings64.sh
vivado -mode batch -source fpga/u50c/scripts/probe_target.tcl \
  -tclargs 127.0.0.1:13121
vivado -mode batch -source fpga/u50c/scripts/program.tcl
vivado -mode batch -source fpga/u50c/scripts/load_image.tcl
vivado -mode batch -source fpga/u50c/scripts/console.tcl
```

`load_image.tcl` holds the CPU in reset, waits for HBM initialization, runs a
maximum-length 128-word HBM burst/readback test, loads
`sim/linux-boot-runs/v0.3/image/ram.dat`, samples readback throughout the image,
and only then releases the CPU.

## Acceptance gates

The bitstream is not considered hardware-verified until all of these pass:

1. JTAG scans `xcu50` and the build/board part is
   `xcu50-fsvh2104-2-e`.
2. Implementation has no DRC errors and no negative setup/hold slack; the
   implemented pins remain `BB18/BC18/J18` with `LVDS/LVCMOS18` standards.
3. Both VIO cores are readable and show locked clocks and released resets.
4. JTAG-to-AXI can read/write the virtual peripheral block repeatedly.
5. The 128-word HBM write/readback test passes with exact data.
6. The NEMU-validated image loads, the CPU runs with the implemented 100 MHz
   clock, Linux enters a usable shell, and injected RX commands receive their
   expected responses. Save the console transcript and bitstream/input hashes.

Passing synthesis or generating a bitstream alone does not prove the board,
clock, HBM data path, processor, or console are working together.

## JTAG console registers

| Address | Access | Function |
| --- | --- | --- |
| `0x1fe10000` | R/W | status and CPU/FIFO control |
| `0x1fe10008` | W | inject one RX byte |
| `0x1fe11000` | R | pop up to four packed TX bytes per word |
| `0x1fe10010` | R | Tick2h SoC ABI identifier `0x54324801` |
| `0x1fe10014` | R | configured HBM AXI/CPU MHz, `0x00e10064` |

Press `Ctrl+]` to leave the interactive console.
