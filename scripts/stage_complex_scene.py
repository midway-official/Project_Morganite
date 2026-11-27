#!/usr/bin/env python3
"""Build a 32-triangle perspective scene and an independent CPU reference."""
import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "graphics"))
sys.path.insert(0, str(ROOT / "sw"))
from asm import assemble
from reference import compare, render, save
from scenes import Scene, scenes
from shaders import UNITS, programs
from test_render import simulate


def build_scene():
    cube = scenes()["cube_a"]
    points = []
    colors = []
    faces = []

    for center, scale, yaw, pitch, color_order in (
        ((-0.47, 0.05, 0.00), 0.73, 0.62, 0.38, (0, 1, 2)),
        ((0.48, -0.02, -0.39), 0.72, -0.71, 0.43, (2, 0, 1)),
    ):
        cy, sy = math.cos(yaw), math.sin(yaw)
        cx, sx = math.cos(pitch), math.sin(pitch)
        rotate_y = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
        rotate_x = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
        start = len(points)
        for vertex in cube.vertices:
            position = rotate_y @ rotate_x @ (vertex[:3] * scale)
            points.append((*tuple(position + center), 1.0))
            rgba = vertex[4:8].copy()
            colors.append((*rgba[list(color_order)], rgba[3]))
        faces.extend(tuple(int(i) + start for i in face) for face in cube.indices)

    # An octahedron in front of the overlapping cubes adds visible triangular facets.
    octa_center = (0.0, -0.48, -0.48)
    radius = 0.48
    octa_points = ((0, 1, 0), (0, -1, 0), (-1, 0, 0), (1, 0, 0),
                   (0, 0, -1), (0, 0, 1))
    octa_colors = ((1, 0.78, 0.12, 1), (0.93, 0.24, 0.14, 1),
                   (0.12, 0.68, 1, 1), (0.2, 1, 0.48, 1),
                   (0.73, 0.26, 1, 1), (1, 0.4, 0.76, 1))
    start = len(points)
    for point, rgba in zip(octa_points, octa_colors):
        points.append((*(np.asarray(point) * radius + octa_center), 1.0))
        colors.append(rgba)
    for left, right in ((2, 4), (4, 3), (3, 5), (5, 2)):
        faces.extend(((start, start + left, start + right),
                      (start + 1, start + right, start + left)))

    identity = np.eye(4, dtype=np.float32)
    cy, sy = math.cos(0.11), math.sin(0.11)
    cx, sx = math.cos(-0.07), math.sin(-0.07)
    view = np.array([[cy, 0, sy, 0],
                     [sx * sy, cx, -sx * cy, 0],
                     [-cx * sy, sx, cx * cy, 5.0],
                     [0, 0, 0, 1]], dtype=np.float32)
    near, far = 0.1, 10.0
    projection = np.array([[3.0, 0, 0, 0],
                           [0, 3.0, 0, 0],
                           [0, 0, far / (far - near), -near * far / (far - near)],
                           [0, 0, 1, 0]], dtype=np.float32)
    matrices = np.stack((view, identity, projection))
    vertices = np.column_stack((np.asarray(points, dtype=np.float32),
                                np.asarray(colors, dtype=np.float32)))
    return Scene(vertices, np.asarray(faces, dtype=np.uint32), matrices)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    parser.add_argument("--width", type=int, default=320)
    parser.add_argument("--height", type=int, default=240)
    parser.add_argument("--sim", type=Path)
    parser.add_argument("--sms", type=int, default=16)
    parser.add_argument("--units", choices=UNITS, nargs="+", default=list(UNITS))
    parser.add_argument("--readonly", action="store_true")
    args = parser.parse_args()
    if not (0 < args.width <= 320 and args.width % 32 == 0 and 0 < args.height <= 240):
        parser.error("width must be a multiple of 32 through 320 and height in 1..240")
    args.out.mkdir(parents=True, exist_ok=True)
    scene = build_scene()
    if len(scene.vertices) > 32 or len(scene.indices) > 32:
        raise ValueError("scene exceeds the GPU shader ABI")
    actual = render(scene, args.width, args.height)
    save(args.out / "reference", actual[:3])
    Image.fromarray(actual[0]).save(args.out / "reference/color.png")
    depth = np.rint((1 - np.clip(actual[1], 0, 1)) * 65535).astype(np.uint16)
    Image.fromarray(depth).save(args.out / "reference/depth.png")
    encoded = scene.encode()
    (args.out / "complex32.scene").write_bytes(encoded)
    manifest = {
        "name": "complex32",
        "triangles": int(len(scene.indices)),
        "vertices": int(len(scene.vertices)),
        "width": args.width,
        "height": args.height,
        "covered_pixels": int(np.count_nonzero(actual[2])),
        "scene_bytes": len(encoded),
        "scene_sha256": hashlib.sha256(encoded).hexdigest(),
        "reference_sha256": [hashlib.sha256(x.tobytes()).hexdigest() for x in actual[:3]],
        "depth_range": [float(np.min(actual[1][actual[2] != 0])),
                        float(np.max(actual[1][actual[2] != 0]))],
    }
    (args.out / "scene.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if args.sim:
        rtl = args.out / "rtl"
        rtl.mkdir(exist_ok=True)
        code = {name: assemble("\n".join(lines) + "\n")
                for name, lines in programs(args.width, args.height, args.units).items()}
        rendered, stages = simulate(str(args.sim.resolve()), rtl, scene, code, args.width,
                                    args.height, args.sms, args.readonly)
        result = compare(rendered, actual, "complex32")
        result.update(triangles=int(len(scene.indices)), width=args.width, height=args.height,
                      sm_count=args.sms, units=args.units, readonly=args.readonly, stages=stages)
        if any(result[key] for key in ("coverage_errors", "winner_errors", "color_errors", "depth_errors")):
            raise ValueError("RTL render differs from the CPU reference: " + json.dumps(result))
        Image.fromarray(rendered[0]).save(rtl / "color.png")
        Image.fromarray(np.rint((1 - np.clip(rendered[1], 0, 1)) * 65535).astype(np.uint16)).save(
            rtl / "depth.png")
        (rtl / "render.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
