#!/usr/bin/env bash
set -euo pipefail

u50_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(cd "${u50_dir}/../.." && pwd)
build_dir=${U50_BUILD_DIR:-"${u50_dir}/build"}
vivado_root=${VIVADO_ROOT:-/opt/xilinx/Vivado/2019.2}
part=${FPGA_PART:-xcu50-fsvh2104-2-e}
jobs=${JOBS:-2}
build_bitstream=${BUILD_BITSTREAM:-1}

test -r "${vivado_root}/settings64.sh"
test -r "${repo_root}/au50_boardfiles_v1_3_20211104.zip"
test -r "${repo_root}/alveo-u50-xdc_20210505.zip"

if [[ "${REGENERATE_RTL:-0}" != 0 ]]; then
    echo 'This target uses frozen Tick2h RTL. REGENERATE_RTL must be 0.' >&2
    exit 1
fi
python3 "${u50_dir}/scripts/prepare_tick2h.py" "${build_dir}"
if [[ "${build_bitstream}" == 1 ]]; then
    python3 "${u50_dir}/scripts/validate_build.py" "${build_dir}" --local-only
fi
mkdir -p "${build_dir}/board_repo" "${build_dir}/vendor_xdc"
unzip -q -o "${repo_root}/au50_boardfiles_v1_3_20211104.zip" -d "${build_dir}/board_repo"
unzip -q -o "${repo_root}/alveo-u50-xdc_20210505.zip" -d "${build_dir}/vendor_xdc"

set +u
source "${vivado_root}/settings64.sh"
set -u
cd "${build_dir}"
vivado -mode batch -notrace -source "${u50_dir}/scripts/create_project.tcl" \
    -tclargs "${part}" "${jobs}" "${build_bitstream}" "${build_dir}"
if [[ "${build_bitstream}" == 1 ]]; then
    python3 "${u50_dir}/scripts/validate_build.py" "${build_dir}"
fi
