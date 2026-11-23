"""Double precision CPU rasterizer, pixel checks and raw HBM image export."""
import hashlib
from pathlib import Path

import numpy as np


def render(scene, width, height):
    vertices = scene.vertices.astype(np.float64)
    clip = vertices[:, :4]
    for matrix in scene.matrices.astype(np.float64):
        clip = clip @ matrix.T
    if scene.shift:
        clip[:, 0] += clip[:, 3] / 8
    w = clip[:, 3]
    assert np.all(np.isfinite(clip)) and np.all(w > 0)
    ndc = clip[:, :3] / w[:, None]
    assert np.all(np.abs(ndc[:, :2]) <= 1) and np.all((ndc[:, 2] >= 0) & (ndc[:, 2] <= 1))
    screen = np.rint((ndc[:, :2] * [1,-1] + 1) * [width*8, height*8]).astype(np.int64)
    rgba = np.zeros((height, width, 4), dtype=np.uint8)
    rgba[:, :, 3] = 255
    depth = np.ones((height, width), dtype=np.float64)
    winner = np.zeros((height, width), dtype="<u4")
    coverage = np.zeros_like(winner)
    y, x = np.mgrid[0:height, 0:width] * 16 + 8

    def orient(a, b, px, py):
        return (b[0]-a[0])*(py-a[1]) - (b[1]-a[1])*(px-a[0])

    for ordinal, face in enumerate(scene.indices, 1):
        ids = list(face)
        a, b, c = screen[ids]
        area = orient(a, b, *c)
        if area == 0:
            continue
        if area < 0:
            ids[1], ids[2] = ids[2], ids[1]
        a, b, c = screen[ids]
        inside = np.ones((height, width), dtype=bool)
        weights = []
        for start, end in ((b,c), (c,a), (a,b)):
            v = orient(start, end, x, y)
            dx, dy = end - start
            inclusive = dy < 0 or (dy == 0 and dx > 0)
            inside &= (v > 0) | ((v == 0) & inclusive)
            weights.append(v / abs(area))
        coverage += inside
        weights = np.stack(weights, axis=-1)
        # Preserve exactly constant depth, including coplanar reversed indices.
        z = ndc[ids[0], 2] + weights[..., 1:] @ (ndc[ids[1:], 2] - ndc[ids[0], 2])
        update = inside & (z < depth)
        bary = weights[update] / w[ids]
        color = (bary @ vertices[ids, 4:]) / bary.sum(axis=-1, keepdims=True)
        if scene.swap:
            color[:, [0, 2]] = color[:, [2, 0]]
        rgba[update] = np.rint(np.clip(color, 0, 1) * 255).astype(np.uint8)
        depth[update], winner[update] = z[update], ordinal
    return rgba, depth.astype("<f4"), winner, coverage


def save(folder, arrays):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    for name, data in zip(("color.rgba", "depth.f32", "winner.u32"), arrays):
        (folder / name).write_bytes(data.tobytes())


def read(folder, width, height):
    return [np.fromfile(folder/name, dtype=kind).reshape(shape) for name, kind, shape in (
        ("color.rgba", "u1", (height,width,4)), ("depth.f32", "<f4", (height,width)),
        ("winner.u32", "<u4", (height,width)))]


def compare(actual, expected, scene):
    a, z, w = actual
    b, ref_z, ref_w, _ = expected
    delta = np.abs(a.astype(np.int16) - b)
    dz = np.abs(z - ref_z)
    exact = scene in {"degenerate", "shared", "overlap"}
    return {"covered": int(np.count_nonzero(w)), "coverage_errors": int(np.sum((w != 0) != (ref_w != 0))),
            "winner_errors": int(np.sum(w != ref_w)), "max_channel_error": int(delta.max()),
            "color_errors": int(np.sum(np.any(delta > 1, axis=2))),
            "max_depth_error": float(dz[np.isfinite(dz)].max(initial=0)),
            "depth_errors": int(np.sum(~np.isfinite(z) | (dz > (0 if exact else 2e-6)))),
            "sha256": [hashlib.sha256(x.tobytes()).hexdigest() for x in actual]}
