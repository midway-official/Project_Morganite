"""Recheck U50C frame buffers and N-body states against CPU references."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from stage_complex_scene import build_scene
from reference import compare, read, render
from stage_stress import bodies, fractal, raytrace


def verify_scene(board):
    expected = render(build_scene(), 320, 240)
    records = {}
    for mode in ("hardware", "software"):
        folder = board/"scene"/mode
        actual = read(folder, 320, 240)
        comparison = compare(actual, expected, "complex32")
        if any(comparison[key] for key in ("coverage_errors","winner_errors","color_errors","depth_errors")):
            raise ValueError(f"{mode} raster errors: {comparison}")
        color, depth, winner = actual
        Image.fromarray(color).save(folder/"color.png")
        visible = winner != 0
        preview = np.zeros(depth.shape, dtype=np.uint8)
        values = depth[visible]
        preview[visible] = np.rint(32 + 223*(values.max()-values)/np.ptp(values))
        Image.fromarray(preview).save(folder/"depth.png")
        (folder/"comparison.json").write_text(json.dumps(comparison,indent=2)+"\n")
        records[mode] = comparison["sha256"]
    if records["hardware"] != records["software"]:
        raise ValueError("pipeline outputs differ")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("board", type=Path)
    args = p.parse_args()
    verify_scene(args.board)
    folder = args.board/"stress"
    configs = json.loads((folder/"suite.json").read_text())["cases"]
    report = json.loads((folder/"results.json").read_text())
    for name, cfg in configs.items():
        result = report[name]
        if not result.get("pass"):
            raise ValueError(f"missing board pass for {name}")
        if name == "nbody":
            _, _, reference = bodies(cfg["bodies"], cfg["steps"], cfg["seed"])
            one, all_sm = [(folder/f"nbody-{mask}.f32").read_bytes() for mask in (1,65535)]
            if one != all_sm or len(one) != len(reference):
                raise ValueError("N-body mask or length mismatch")
            error = float(np.abs(np.frombuffer(one, "<f4")-np.frombuffer(reference,"<f4")).max())
            if not error <= cfg["max_abs_error"] or error != result["max_abs_error"]:
                raise ValueError(f"N-body error {error}")
        else:
            w, h = cfg["width"], cfg["height"]
            if name == "raytrace":
                _, ref = raytrace(w, h, dense=cfg["spheres"] == 20)
            else:
                _, ref = fractal(name, w, h, cfg["iterations"])
            raw = (folder/(name+".rgba")).read_bytes()
            with Image.open(folder/(name+".png")) as png:
                if png.size != (w,h) or png.mode != "RGBA" or png.tobytes() != raw:
                    raise ValueError(f"{name} PNG differs from the board readback")
            error = int(np.abs(np.frombuffer(raw, "B").reshape(ref.shape)-ref.astype(np.int16)).max())
            if error != result["max_channel_error"] or error > cfg["max_channel_error"]:
                raise ValueError(f"{name} max channel error {error}")
        print(name, "OK")


if __name__ == "__main__":
    main()
