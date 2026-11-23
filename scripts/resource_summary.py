#!/usr/bin/env python3
"""Extract scoped and official device/SLR counts from one Vivado checkpoint."""
import argparse
import json
from pathlib import Path

fields = {"CLB LUTs": "LUT", "Register as Flip Flop": "FF", "Block RAM Tile": "BRAM_tiles",
          "URAM": "URAM", "DSPs": "DSP"}


def table(lines, names, column=1):
    result = {}
    for line in lines:
        cells = [s.strip().rstrip("*") for s in line.strip().strip("|").split("|")]
        if cells[0] in names and names[cells[0]] not in result:
            value = float(cells[column])
            result[names[cells[0]]] = int(value) if value.is_integer() else value
    if set(result) != set(names.values()):
        raise ValueError(f"incomplete utilization table {result}")
    return result


def summarize(folder):
    meta = dict(line.split("=", 1) for line in (folder / "provenance.txt").read_text().splitlines())
    owners = [name for name in ("cpu", "gpu") if name in meta]
    def block(scope):
        return table((folder / (scope + ".rpt")).read_text().splitlines(), fields)
    totals = {scope: block(scope) for scope in ["soc", *owners]}
    totals["remaining_by_difference"] = {
        k: value - sum(totals[s][k] for s in owners) for k, value in totals["soc"].items()}
    result = {"checkpoint_sha256": meta["checkpoint_sha256"], "stage": meta["stage"], "scopes":
              {s: meta[s] for s in owners}, "device": totals}
    if meta["stage"] == "route":
        lines = (folder / "soc.rpt").read_text().split("SLR CLB Logic and Dedicated Block Utilization")[-1].splitlines()
        columns = next(line for line in lines if "Site Type" in line).strip().strip("|").split("|")
        slrs = [c.strip() for c in columns if c.strip().startswith("SLR") and "%" not in c]
        if not slrs:
            raise ValueError("missing SLR counts")
        # The regional overview counts CLB registers. Check that all are FFs.
        if table((folder / "soc.rpt").read_text().splitlines(), {"CLB Registers": "FF"})["FF"] != totals["soc"]["FF"]:
            raise ValueError("implemented design includes a latch")
        slr_fields = dict(fields)
        del slr_fields["Register as Flip Flop"]
        slr_fields["CLB Registers"] = "FF"
        regions = {}
        for column, name in enumerate(slrs, 1):
            counts = {"soc": table(lines, slr_fields, column)}
            for scope in [*owners, "rest"]:
                count = int(meta[f"{name}.{scope}.placed"])
                counts[scope] = block(name + "_" + scope) if count else dict.fromkeys(fields.values(), 0)
            counts["scoped_sum_minus_soc"] = {
                k: sum(counts[s][k] for s in [*owners, "rest"]) - value for k, value in counts["soc"].items()}
            regions[name] = counts
        for k, value in totals["soc"].items():
            if sum(s["soc"][k] for s in regions.values()) != value:
                raise ValueError(f"regional and device totals differ for {k}")
        result["slrs"] = regions
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.folder), indent=2))
