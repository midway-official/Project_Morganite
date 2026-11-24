"""Write a Chinese setup/hold attribution report from board/timing_paths.tcl output."""

import argparse
import csv
import re
from collections import defaultdict
from pathlib import Path


def delay_details(report):
    paths = {}
    for block in re.split(r"(?m)^Slack \(", report)[1:]:
        src = re.search(r"(?m)^  Source:\s+(\S+)", block)[1]
        dst = re.search(r"(?m)^  Destination:\s+(\S+)", block)[1]
        delay = re.search(r"Data Path Delay:.*?logic ([.\d]+)ns .*?route ([.\d]+)ns", block)
        skew = re.search(r"Clock Path Skew:\s+([-\d.]+)ns", block)
        paths[src, dst] = (delay.groups() if delay else ("—", "—")) + (
            skew[1] if skew else "—",
        )
    return paths


def recommendation(row):
    ends = row["start_pin"], row["end_pin"]
    if row["analysis"] == "min":
        if row["launch_clock"] != row["capture_clock"]:
            return "核对两个时钟的相位关系、时钟偏斜及跨域电路，在真实时钟约束下复查最短数据路径。"
        if row["from_slr"] != row["to_slr"]:
            return "核对跨 SLR 的数据和时钟走线，尝试将相关寄存器放在同一 SLR，并复查 setup。"
        return "检查最短数据路径与时钟偏斜，试行定向 hold 修复，并复查该域的 setup 裕量。"
    if "/dma/plans" in ends[1] and "faultAddress" in ends[1]:
        return ("在请求仲裁与边界检查之间锁存请求及 owner，使用寄存的非法 lane 掩码选出首错地址。"
                "覆盖长背压、读写并发、地址溢出及非法请求产生零 AXI 事务。")
    if row["from_slr"] != row["to_slr"]:
        return "检查最长布线段及跨 SLR 次数，评估模块共置或在协议边界增加可背压的寄存级。"
    return "先定位报告中最长的网和组合级，分别评估局部寄存器复制、相邻布局或流水化对这条路径的影响。"


def summarize(folder):
    meta = dict(line.split("=", 1) for line in (folder / "checkpoint.txt").read_text().splitlines())
    with (folder / "paths.tsv").open() as stream:
        rows = list(csv.DictReader(stream, delimiter="\t"))
    print(f"# {Path(meta['checkpoint']).name} 时序归属\n")
    print(f"Vivado {meta['vivado']}，DCP SHA-256 为 `{meta['sha256']}`。")
    for analysis, label in (("max", "Setup"), ("min", "Hold")):
        print(f"\n## {label}\n")
        all_paths = [r for r in rows if r["analysis"] == analysis]
        violations = [r for r in all_paths if float(r["slack_ns"]) < 0]
        count = int(meta[f"{analysis}.negative_paths_returned"])
        if len(violations) != count or not all_paths:
            raise ValueError(f"incomplete {analysis} path export")
        if count:
            limit = int(meta[f"{analysis}.query_limit"])
            suffix = "，达到查询上限，仅统计已返回路径" if count >= limit else ""
            print(f"返回 {count} 条负裕量路径，每端点最多一条{suffix}。")
        else:
            print("查询得到零条负裕量路径，表中列出全设计最紧的一条。")
        groups = defaultdict(list)
        for row in violations or all_paths:
            key = row["from_owner"], row["to_owner"], row["launch_clock"], row["capture_clock"]
            groups[key].append(row)
        details = delay_details((folder / f"{analysis}_examples.rpt").read_text())
        print("\n| 起点 → 终点 | 时钟 | 违例数 | 最差裕量 ns | 逻辑 / 布线 ns | 时钟偏斜 ns | SLR |")
        print("| --- | --- | ---: | ---: | ---: | ---: | --- |")
        worst_paths = []
        for group in groups.values():
            worst_paths.append((min(group, key=lambda r: float(r["slack_ns"])), len(group)))
        worst_paths.sort(key=lambda item: float(item[0]["slack_ns"]))
        for r, size in worst_paths:
            logic, route, skew = details[r["start_pin"], r["end_pin"]]
            print(f"| {r['from_owner']} → {r['to_owner']} | {r['launch_clock']} → {r['capture_clock']} "
                  f"| {size if count else 0} | {r['slack_ns']} | {logic} / {route} | {skew} "
                  f"| {r['from_slr']} → {r['to_slr']} |")
        worst = worst_paths[0][0]
        print(f"\n最差路径从 `{worst['start_pin']}` 到 `{worst['end_pin']}`，"
              f"经过 {worst['logic_levels']} 级逻辑。")
        if count:
            print(f"\n{recommendation(worst)}")
        print(f"\n[代表路径]({analysis}_examples.rpt) · [端点清单](paths.tsv)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    summarize(parser.parse_args().folder)
