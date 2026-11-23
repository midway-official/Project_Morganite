#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sha256sum --quiet -c files.sha256
mkdir -p implementation
test ! -e implementation/vivado
cp -a inputs/generated inputs/board_repo implementation/
vivado=/home/fpga/Xilinx/Vivado/2023.2/bin/vivado
"$vivado" -version > implementation/version.txt
grep -q 'Vivado v2023.2' implementation/version.txt
"$vivado" -mode batch -notrace -log implementation/vivado.log -journal implementation/vivado.jou \
    -source fpga/u50c/scripts/create_project.tcl \
    -tclargs xcu50-fsvh2104-2-e 4 1 "$PWD/implementation"
sha256sum implementation/vivado/aurora_u50c.runs/impl_1/u50c_top.bit \
    implementation/u50c_top.ltx > implementation/program.sha256
