# Derived from the supplied alveo-u50-xdc_20210505.zip and AU50 1.3 board file.
# Target board part: xilinx.com:au50:part0:1.3 / xcu50-fsvh2104-2-e.

set_operating_conditions -design_power_budget 63

set_property PACKAGE_PIN BB18 [get_ports SYSCLK3_P]
set_property IOSTANDARD LVDS [get_ports SYSCLK3_P]
set_property DQS_BIAS TRUE [get_ports SYSCLK3_P]
set_property PACKAGE_PIN BC18 [get_ports SYSCLK3_N]
set_property IOSTANDARD LVDS [get_ports SYSCLK3_N]
create_clock -period 10.000 -name sysclk3 [get_ports SYSCLK3_P]

set_property PACKAGE_PIN J18 [get_ports HBM_CATTRIP]
set_property IOSTANDARD LVCMOS18 [get_ports HBM_CATTRIP]
set_property PULLDOWN TRUE [get_ports HBM_CATTRIP]

set_property CONFIG_VOLTAGE 1.8 [current_design]
set_property CFGBVS GND [current_design]
set_property BITSTREAM.CONFIG.CONFIGFALLBACK Enable [current_design]
set_property CONFIG_MODE SPIx4 [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4 [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 63.8 [current_design]
set_property BITSTREAM.CONFIG.EXTMASTERCCLK_EN disable [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE YES [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN Pullup [current_design]
set_property BITSTREAM.CONFIG.SPI_32BIT_ADDR Yes [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
