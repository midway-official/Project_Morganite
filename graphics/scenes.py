"""Indexed meshes and little-endian Linux upload format."""
import math
import struct
from dataclasses import dataclass

import numpy as np


@dataclass
class Scene:
    vertices: np.ndarray
    indices: np.ndarray
    matrices: np.ndarray
    shift: bool = False
    swap: bool = False

    def encode(self):
        flags = int(self.shift) | (int(self.swap) << 1)
        return (struct.pack("<4I", 0x3153474d, len(self.vertices), len(self.indices), flags)
                + self.matrices.astype("<f4").tobytes() + self.vertices.astype("<f4").tobytes()
                + self.indices.astype("<u4").tobytes())


def scenes():
    identity = np.tile(np.eye(4, dtype=np.float32), (3, 1, 1))
    colors = [(1, .125, 0, 1), (0, 1, .25, 1), (.125, 0, 1, 1)]
    def mesh(points, faces, rgba=None, matrices=identity, **flags):
        vertices = [(*p, *(rgba[i] if rgba is not None else colors[i % 3])) for i, p in enumerate(points)]
        return Scene(np.array(vertices, dtype="<f4"), np.array(faces, dtype="<u4"), matrices, **flags)
    tri = [(-.75, -.625, .25, 1), (1.25, -.75, 1.125, 2), (0, .75, .5, 1)]
    faces = [(0, 1, 2)]
    quad = [(-.375, .5, .5, 1), (.375, .5, .5, 1), (.375, -.5, .5, 1), (-.375, -.5, .5, 1)]
    overlap = [(x, y, z, 1) for z in (.75, .25, .25) for x, y in ((-.5,-.5), (.5,-.5), (0,.75))]
    cases = {"triangle": mesh(tri, faces), "vertex_shift": mesh(tri, faces, shift=True),
             "fragment_swap": mesh(tri, faces, swap=True), "shared": mesh(quad, [(0, 1, 2), (0, 2, 3)]),
             "overlap": mesh(overlap, [(0,1,2), (3,4,5), (6,7,8)], [c for c in colors for _ in range(3)]),
             "degenerate": mesh(quad, [(0,0,2), (1,2,2)])}
    corners = [(x,y,z,1) for z in (-.75,.75) for y in (-.75,.75) for x in (-.75,.75)]
    palette = [((x+1)*.5,(y+1)*.5,(z+1)*.5,1) for x,y,z,_ in corners]
    faces = [(a,b,c) for a,b,c,d in ((0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3))
             for a,b,c in ((a,b,c),(a,c,d))]
    for name, yaw, pitch in (("cube_a",.46,.31), ("cube_b",-.71,.49)):
        cy, sy, cx, sx = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
        matrices = identity.copy()
        matrices[0] = [[cy,0,sy,0],[sx*sy,cx,-sx*cy,0],[-cx*sy,sx,cx*cy,0],[0,0,0,1]]
        matrices[1, 2, 3] = 3.5
        matrices[2] = [[1.55,0,0,0],[0,2,0,0],[0,0,8/7,-8/7],[0,0,1,0]]
        cases[name] = mesh(corners, faces, palette, matrices)
    # Two overlapping, indexed cubes use 24 of the 32 triangle-mask bits.
    points = [(x*.67+dx, y*.67+dy, z*.67+dz, w)
              for dx,dy,dz in ((-.48,-.13,.14),(.48,.13,-.14)) for x,y,z,w in corners]
    cases["twin_cubes"] = mesh(points, faces + [tuple(i+8 for i in f) for f in faces],
                               palette + [(c[2],c[0],c[1],c[3]) for c in palette],
                               cases["cube_a"].matrices)
    return cases
