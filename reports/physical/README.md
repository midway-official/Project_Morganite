# U50C routed implementation

Vivado 2023.2 targeted xcu50-fsvh2104-2-e. Both resource snapshots use the
final post-route physical optimization checkpoints. The signoff build is
Morganite f2a3ce0 with frozen Aurora RTL 5ec35882. The GPU uses two SMs
with four physical lanes each. The build input hashes and device checkpoint
hashes are in the run manifests and resource provenance files.

| Scope | CLB LUT | FF | BRAM tiles | URAM | DSP |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline SoC | 112771 | 48279 | 65.5 | 0 | 8 |
| Baseline CPU | 104709 | 40039 | 61 | 0 | 8 |
| Morganite SoC | 153478 | 63792 | 81.5 | 0 | 48 |
| Morganite CPU | 104716 | 40037 | 61 | 0 | 8 |
| Morganite GPU | 40305 | 15287 | 16 | 0 | 40 |
| Remaining SoC by subtraction | 8457 | 8468 | 4.5 | 0 | 0 |
| Whole-design increase | 40707 | 15513 | 16 | 0 | 40 |

All five resource classes are on SLR0. SLR1 has one placed peripheral
primitive and zero of these resources. Total LUT occupancy is 17.61% of
the device and 34.91% of SLR0. The baseline used 12.94% and 25.65%.
Scoped LUT reports account for combining inside each selected cell set.
Their SLR0 sum exceeds the official physical total by 11 LUTs on Morganite
and five LUTs on the baseline. The whole-device totals come directly from
the complete-design utilization reports.
[Scoped resources](../resources/f2a3ce0-route/summary.json) · [Baseline](../resources/baseline-route/summary.json)

| Clock or path population | Baseline WNS / WHS (ns) | Morganite WNS / WHS (ns) |
| --- | ---: | ---: |
| Entire design including clock crossings | +0.514 / +0.010 | +1.158 / +0.010 |
| CPU/GPU 100 MHz | +1.838 / +0.010 | +2.090 / +0.010 |
| HBM AXI 225 MHz | +1.213 / +0.019 | +1.158 / +0.019 |
| sysclk3 100 MHz | +6.486 / +0.010 | +6.234 / +0.011 |
| JTAG internal TCK 20 MHz | +11.673 / +0.019 | +11.800 / +0.019 |

Morganite routed all 189872 routable nets with zero routing errors. TNS,
THS and TPWS are zero, with zero failing endpoints. Minimum pulse-width
slack is 0.000 ns. The internal unconstrained endpoint count is zero.
The single output with missing delay is the board's asynchronous HBM_CATTRIP
pin, driven directly by the original HBM subsystem. The CDC clock-pair
rows match the baseline and list 126 endpoints as safe. The detailed
methodology run also flags the original false-path/CDC logic under TIMING-9.
The shared board IP supplies the FIFO and debug-hub constraints. Both the
GPU and the board attachment operate in the CPU clock domain.
[Timing](f2a3ce0-route/timing_impl.rpt) · [Clock crossings](f2a3ce0-route/cdc_impl.rpt) · [Exceptions](f2a3ce0-route/exceptions_impl.rpt)

DRC found zero errors and zero critical warnings. Of 151 warnings,
144 concern multiplier register placement, six concern LUT equation
terms, and one concerns nets with no routable loads. The separate
methodology report lists 9 reset warnings, 24 wide multiplier warnings,
40 dual-port RAM collision warnings, one CDC and one CATTRIP timing
warning, plus one MMCM location advisory. The exact cells and paths are
retained in the raw reports. Register file forwarding and the scratchpad
access protocol are covered by the SM regression. The baseline DRC had
31 warnings.
[DRC](f2a3ce0-route/drc_impl.rpt) · [Methodology](f2a3ce0-route/methodology_impl.rpt)
