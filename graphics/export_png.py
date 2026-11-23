#!/usr/bin/env python3
"""Export verified raw color and depth planes as viewable PNG images."""
import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image

from reference import read


def export(raw_dir, comparisons, dest):
    results = json.loads(comparisons.read_text())
    for name, scene in results["scenes"].items():
        width, height = scene["width"], scene["height"]
        color, depth, winner = read(raw_dir/name, width, height)
        if [hashlib.sha256(a.tobytes()).hexdigest() for a in (color, depth, winner)] != scene["sha256"]:
            raise ValueError("input does not match compared render: "+name)
        folder = dest/name
        folder.mkdir(parents=True, exist_ok=True)
        depth16 = np.rint((1-np.clip(depth,0,1))*65535).astype("uint16")
        for filename, pixels in (("color.png",color), ("depth.png",depth16)):
            path = folder/filename
            Image.fromarray(pixels).save(path)
            with Image.open(path) as saved:
                if not np.array_equal(np.asarray(saved), pixels):
                    raise ValueError("PNG readback mismatch: "+str(path))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("raw_dir",type=Path)
    p.add_argument("comparisons",type=Path)
    p.add_argument("dest",type=Path)
    args = p.parse_args()
    export(args.raw_dir,args.comparisons,args.dest)
