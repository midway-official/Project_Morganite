# U50C Linux boot verification: 5ec35882

## Result

**PASS** — the exact candidate `5ec35882a6eb4040631c1cf9d322a754bd435907` booted Linux on the physical U50C and reached an interactive shell. The UART transcript contains the kernel and Minimal LoongArch32 initramfs messages, the `/ #` prompt, the candidate-specific echo marker, `uname -m` = `loongarch32r`, `id` = `uid=0 gid=0`, and `pwd` = `/`.

## Build

The RTL was generated in a clean detached worktree at the exact commit and built remotely with Vivado 2023.2 for `xcu50-fsvh2104-2-e`, using the existing U50C/HBM integration. The CPU clock is 100 MHz and HBM AXI is 225 MHz. Post-route timing passed with WNS +0.514 ns, TNS 0 ns, WHS +0.010 ns, and THS 0 ns. All 135,443 routable nets completed with zero routing errors. DRC reported 0 errors and 31 warnings; synthesis reported 0 errors and 0 critical warnings.

- Generated `Aurora.v` SHA-256: `82c673147ce734b2662b3dc5e740377913c594f039102a1c83033a2440d409f1`
- BIT SHA-256: `8bc4c4fffeb9c4d59b557a994e1a7784f48daff0d990d78240f185b37e3c246e`
- LTX SHA-256: `7fc80573b73bdeb17e362bdc449c5d55cbdcaa54844c146a92ed1753e7320d88`

## Board checks

Vivado identified `xcu50_u55n_0`. Programming and peripheral read/write passed. HBM diagnostics passed on all 16 pseudo-channels with two patterns and 128 words per pattern per channel. The loader read back the zeroed range `0x00900000–0x01000000`, loaded 6,631,738 bytes, and released the CPU when the UART client connected. The board remains running this Linux image; the local SSH UART tunnel was closed after the test.

## Evidence

- `linux-uart.txt`: raw UART transcript
- `linux-uart.txt.result.json`: machine-readable shell checks
- `test_uart.py`: candidate-specific UART validation runner
- `remote-evidence.tar.gz`: remote BIT/LTX, generated RTL, implementation reports, build logs, hardware logs, and run request
- `manifest.json`: source, artifact hashes, timing, DRC, and physical test result

Remote build directory: `/home/fpga/aurora-tick2h-u50c-100mhz-20261026/fpga/u50c/build/5ec35882-20261123`

Remote hardware run: `/home/fpga/aurora-u50c-debug/runs/20261123T134334.883268Z`