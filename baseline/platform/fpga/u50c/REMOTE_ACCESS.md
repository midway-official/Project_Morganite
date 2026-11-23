# U50C 远程连接与资料索引

2026-10-26（Asia/Taipei）实测连接成功。公网 SSH 网关、U50C 后端和
FT4232H 下载器均可用；Vivado 枚举到 `xcu50_u55n_0`。本次验证止于 JTAG
枚举，没有下载 bitstream、写 QSPI、访问 HBM 或启动 CPU。

## 下次直接使用

在 Aurora_AXI 仓库运行：

```bash
# 自动建立或复用后台隧道，再用 Vivado 枚举 U50C。
./fpga/u50c/scripts/remote_jtag.sh probe

# 只建立后台隧道，随后可以在图形界面使用 Hardware Manager。
./fpga/u50c/scripts/remote_jtag.sh start

# 查看或关闭本脚本管理的隧道。
./fpga/u50c/scripts/remote_jtag.sh status
./fpga/u50c/scripts/remote_jtag.sh stop
```

`probe` 也可以省略，是脚本的默认操作。它调用现有 `probe_target.tcl`，只打开
序列号包含 `50241A20256MA` 的目标，完成扫描后关闭 Hardware Target。
SSH 隧道继续保留，便于随后打开 Hardware Manager。脚本没有编程或烧写命令。

`start` 在后台运行 SSH，不必保持终端窗口开启。`status` 只检查 SSH 控制连接，
不能证明远端 `3122` 或 FPGA 可用；完整的连通性判断使用 `probe`。
`stop` 只关闭本脚本的 SSH 控制连接。

Vivado 图形界面的远端服务器地址是 **`127.0.0.1:13121`**。也可以在 Tcl 中运行：

```tcl
open_hw_manager
connect_hw_server -url 127.0.0.1:13121
get_hw_targets *50241A20256MA*
open_hw_target [get_hw_targets *50241A20256MA*]
get_hw_devices
# 使用结束后释放目标。
close_hw_target
disconnect_hw_server
close_hw_manager
```

日常日志保存在仓库外：

```text
~/.local/state/aurora-u50c/tunnel.log
~/.local/state/aurora-u50c/probe.log
~/.local/state/aurora-u50c/probe.jou
```

脚本适用于 Linux/WSL，需要 OpenSSH、Python 3（首次导入）、GNU coreutils，以及
执行 `probe` 所需的 Vivado。仅使用 `start` 不需要本地 Vivado。

## 首次导入或换机器

本机已导入，可以直接使用。新机器运行：

```bash
./fpga/u50c/scripts/remote_jtag.sh setup /path/to/FPGA_JTAG_CLIENT.zip
./fpga/u50c/scripts/remote_jtag.sh probe
```

如果资料包就在仓库根目录且凭据尚未导入，`start`/`probe` 会自动导入。
`setup` 只读取 ZIP 中指定的私钥、公钥和 `known_hosts`，不执行压缩包里的脚本。
已有凭据与 ZIP 不一致时会停止，不覆盖原文件。

凭据存放于 `~/.ssh/fpga-jtag-client/`，目录权限 `700`、文件权限 `600`；私钥和
管理员密码不写入项目文档。日常 JTAG 使用资料包专用密钥，无需宿主机或虚拟机密码。
脚本强制校验资料包提供的网关主机公钥，指纹为：

```text
ED25519 SHA256:MSUeQBuXEyzTBT+8o7xoZw3GtHa/SX+TtmK3Ox6QlRM
```

可使用绝对路径覆盖凭据或日志目录：`JTAG_CREDENTIAL_DIR`、`JTAG_STATE_DIR`。
本机默认使用 `/opt/xilinx/Vivado/2019.2/bin/vivado`；换安装位置可以指定：

```bash
VIVADO_BIN=/path/to/Vivado/2023.2/bin/vivado ./fpga/u50c/scripts/remote_jtag.sh probe
# 或先 source 对应版本的 settings64.sh，让 vivado 出现在 PATH 中。
```

资料包建议客户端使用 **Vivado/Vivado Lab 2023.2**。本次本地 **2019.2** 对远端
**2023.2** 的 JTAG 枚举成功，这项结果不证明跨版本下载或 VIO/ILA/JTAG-to-AXI
调试兼容。后续编程和调试优先按资料包使用 2023.2 客户端。

Windows 原生环境可以继续使用资料包提供的 PowerShell 脚本，明确选择 U50C：

```powershell
.\fpga-jtag.ps1 -Device u50c
```

## 连接拓扑与本次实测

```text
本机 Vivado
  -> 127.0.0.1:13121
  -> SSH fpga@mc.mbot6183.fun:43121（专用密钥、限制端口转发）
  -> Ubuntu VM 127.0.0.1:3122 / hw_server 2023.2
  -> FT4232H / Xilinx 50241A20256MA
  -> xcu50_u55n_0
```

| 项目 | 本次实测或资料包定义 |
| --- | --- |
| JTAG 公网入口 | `mc.mbot6183.fun:43121`，专用账号 `fpga` |
| U50C 后端 | 虚拟机 `127.0.0.1:3122` |
| 其他设备 | `3121` 对应 xc7a200t；本脚本固定选择 U50C 的 `3122` |
| 本机 Vivado 地址 | `127.0.0.1:13121`，仅监听本机回环 |
| 下载器序列号 | `50241A20256MA` |
| 实际扫描名称 | `xcu50_u55n_0`，`HW_PART=xcu50_u55n` |
| 宿主机入口 | `ssh mbot1863@mc.mbot6183.fun -p 50022` |
| 宿主机系统 | Red Hat Enterprise Linux 10.1 |
| 虚拟机 | libvirt 名称 `ubuntu-vivado-2019`，运行中 |
| 虚拟机系统/账号 | Ubuntu 18.04.6 LTS，账号 `fpga` |
| 虚拟机 IP | `192.168.31.44`；另有 NAT 地址 `192.168.122.194`，地址可能变化 |
| USB | `0403:6011` FT4232H 已直通至目标 VM |
| U50C 服务 | `/home/fpga/Xilinx/Vivado/2023.2/.../hw_server`，本次 PID `1484` |
| 服务过滤器 | `-e set jtag-port-filter 50241A20256MA` |
| Cockpit | 对话提供的公网 `9900` 端口 TCP 可达；未验证网页登录 |

宿主机和 VM 的账号密码以管理员私下提供的凭据为准。已验证二者可登录；
不在此文档重复记录密码。宿主机账号已配置 `sudo -n virsh` 权限，本次仅用它
查看目标 VM 和 USB 直通配置，未改变虚拟机、服务或权限。

本次没有复现此前的 `3122: Connection refused` 或下载器被其他 hw_server 锁定。
原始成功日志与主机/VM 状态保存在
[`reports/2026-10-26-remote-fpga/`](../../reports/2026-10-26-remote-fpga/README.md)。

## 板卡文件、XDC 与 HBM 资料

仓库中已有管理员对话提到的两份官方资料，ZIP 完整性检查通过：

| 文件 | SHA-256 |
| --- | --- |
| `au50_boardfiles_v1_3_20211104.zip` | `8f449d6b719bd97360e6db9c966f04f603cb2a09cff82f330d236c01d2accc5b` |
| `alveo-u50-xdc_20210505.zip` | `84bb3d625974f8453a6bd19dcd515b7a5510798022a56c7a1e6401520ff72828` |

AMD 官方下载页当前列出的文件名与上述文件一致：
[Alveo U50 支持与下载](https://www.amd.com/en/support/downloads/alveo-downloads.html/accelerators/alveo/u50.html)。
本次检查的是本地文件及其哈希，没有重新下载并做字节比对。

板卡文件中的 `board.xml` 指定 `xcu50-fsvh2104-2-e`，board part 为
`xilinx.com:au50:part0:1.3`。JTAG 的 `xcu50_u55n` 是扫描识别名，不能直接替代
工程的完整 part，也不能单凭扫描名确认封装、速度等级或具体 PCB 版本。

管理员建议同时准备 board files 和 XDC 是合理的，但“只有加入 board files 才能
选器件”并不完整：AMD 官方也支持 XDC + Tcl 流程，直接使用
`create_project ... -part xcu50-fsvh2104-2-e`。Board files 提供板级接口和 IP 预设；
器件安装支持仍需匹配。参见
[官方建工程说明](https://docs.amd.com/r/en-US/ug1371-u50-reconfig-accel/Creating-a-Vivado-RTL-Project)。

官方全局安装路径随版本变化：2019.2 使用 `data/boards/board_files/`，2021.1+
使用 `data/xhub/boards/XilinxBoardStore/boards/Xilinx/`。现有构建脚本通过
`set_param board.repoPaths` 使用项目自己的 board repository，无需改全局目录。
参见[官方安装说明](https://docs.amd.com/r/en-US/ug1371-u50-reconfig-accel/Vivado-Board-Support-and-XDC-Files-Installation)。

上板前尤其需要核对以下几点：

- **HBM_CATTRIP / J18 / LVCMOS18**：AMD 指定其为高有效过温指示。使用 HBM IP 时，
  官方要求将两堆栈 CATTRIP 输出按要求合并接到该管脚；不用 HBM 时应驱动低。
  XDC 的 `PULLDOWN TRUE` 不能代替对 RTL 接线和有效驱动的核验。
  [官方 HBM 说明](https://docs.amd.com/r/en-US/ug1371-u50-reconfig-accel/HBM-Memory)。
- **SYSCLK3**：`P=BB18`、`N=BC18`、LVDS、100 MHz；本地完整 XDC、board files 和
  最小 XDC 中的这些定义一致。
  [官方 I/O 表](https://docs.amd.com/r/en-US/ug1371-u50-reconfig-accel/Miscellaneous-I/O)。
- 完整 XDC 含电压、时钟、管脚、配置和设计功耗预算等定义，不能只摘取时钟管脚后
  就认为完成板级约束审查。现有最小 SoC 源码和 XDC 的存在也不证明当前 bitstream
  已通过实现、HBM 初始化或实机验收。

官方 U50 有 8 GiB HBM（两个 4 GiB 堆栈）；管理员说曾用 BRAM 跑 UART，只能证明
他提到的测试使用 BRAM，不能作为 HBM 已验证的证据。本仓库现有最小 SoC 文档
描述使用一个 4 GiB 堆栈，属于后续上板验证范围。

## 遇到故障时按层定位

| 现象 | 判断与下一步 |
| --- | --- |
| SSH 主机公钥不匹配 | 对照管理员确认的公钥和资料包；脚本不会自动忽略校验 |
| `Address already in use` | 本机 `13121` 已被占用，先检查现有隧道；不直接杀其他进程 |
| `start` 成功但 JTAG 失败 | SSH 和本地监听成功仍不能证明远端后端可用，运行 `probe` 看完整错误 |
| SSH 日志出现 `3122: Connection refused` | 远端网关无法连接其回环端口 `3122`，在 VM 检查监听与服务进程 |
| `No active target` / `may be locked` | 检查正确序列号的目标以及其他 hw_server/Hardware Manager 的占用，不强制抢锁 |
| 找不到 XCU50 工程 part | 检查 Vivado 器件支持、完整 part 和 board repository；扫描名不能直接当完整 part |
| 连接之后 AXI/VIO 不可用 | JTAG 枚举不证明 bitstream 中包含所需调试核，需另做设计与版本验证 |

需要查看 VM 时，先登录宿主机，用已有权限查目标地址：

```bash
sudo -n virsh -c qemu:///system domifaddr ubuntu-vivado-2019 --source lease
```

再通过宿主机跳转到观察到的 VM 地址，例如：

```bash
ssh -J mbot1863@mc.mbot6183.fun:50022 fpga@192.168.122.194
# 进入 VM 后只读检查：
ss -ltnp | grep ':3122'
ps -ef | grep '[h]w_server'
lsusb -d 0403:6011
```

宿主机 SSH 是系统管理入口；专用 `43121` 网关只用于资料包允许的端口转发，
不能用来登录 shell。当前服务正常，无需重启 hw_server 或 VM。
