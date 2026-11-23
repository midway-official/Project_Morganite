# Morganite graphics ABI 1

Each frame submits a vertex job, an indexed primitive setup job and a pixel
job. A frame uses 320 by 240 pixels, RGBA8 in little-endian RGBA byte order,
an FP32 depth plane and a 32-bit winning primitive ordinal. Zero denotes
background. Clear color is opaque black and clear depth is 1.

Each input vertex contains position XYZW and RGBA as eight FP32 words.
The vertex program multiplies by row-major model, view and projection
matrices. The scene file carries a 16-byte header, 192 bytes of matrices,
eight words per vertex and three zero-based uint32 indices per triangle.
Its header words are magic 0x3153474d, vertex count, triangle count and
shader flags. Bit 0 selects the shifted vertex program, bit 1 selects the
red/blue-swapping fragment program. The Linux example supports 1 to 32
vertices and triangles per scene. Each job uses the device address returned
by the driver for all pointers.

Clip inputs have finite positive w, x and y in [-w,w] and z in [0,w].
The viewport maps y downwards. Projected positions are rounded to nearest
even on a 1/16 pixel grid. Pixel centers lie at (16x+8,16y+8) on that grid.
Triangle setup normalizes orientation. For positive signed area the edge
includes boundary samples when dy is negative or when dy is zero and dx is
positive. Degenerates contribute zero pixels. One thread owns each pixel,
visits submitted triangles in order, and updates on strictly smaller z/w.
Equal depth preserves the first winner. Color interpolates color/w divided
by interpolated 1/w. The selected fragment routine receives linear RGBA in
r20..r23, uses r24..r30 as temporaries and returns RGBA in r20..r23. The
library quantizes to nearest even UNORM8. Four Newton iterations and a fused
residual correction implement reciprocal on the scene input domain.

The reference in `graphics/reference.py` uses double precision, integer
edges and its own per-triangle raster loop. All pixels must match coverage
and winning primitive. RGBA tolerates one unit per channel. Absolute depth
error is limited to 2e-6, with exact checks for the shared edge, coplanar
tie and degenerate cases. Each frame archives the raw CPU and GPU planes.

```bash
source scripts/toolchain.sh
bash scripts/test_gpu.sh
python3 graphics/test_render.py rtl build/render-320
bash linux/build_userspace.sh
python3 linux/build_kernel.py
# In the LA32R shell
/bin/mgpu render /gpu/cube_a.scene /tmp/frame
```
