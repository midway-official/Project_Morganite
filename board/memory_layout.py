"""Physical byte ranges on the frozen U50C platform. All upper bounds are exclusive."""

import re

RAM_END = 0x1fe00000
GPU_FLOOR = 0x01000000
# hbm_diag.tcl uses a maximum of 128 words per test with the CPU held in reset.
DIAGNOSTICS = [(0x1fdf0000, 0x1fdf0200)] + [
    ((pc << 28) + 0x100000, (pc << 28) + 0x100200) for pc in range(16)
]


def overlaps(a, b):
    return max(a[0], b[0]) < min(a[1], b[1])


def image_ranges(layout):
    # JTAG accesses the last word of each sparse segment at word granularity.
    ranges = [(base, base + ((size + 3) & ~3)) for base, size in layout["segments"]]
    ranges += [tuple(r) for r in layout["zero_ranges"]]
    for i, (base, end) in enumerate(ranges):
        if not (0 <= base < end <= RAM_END) or (base | end) & 3:
            raise ValueError("kernel, firmware or BSS outside aligned Linux RAM")
        if any(overlaps((base, end), other) for other in ranges[:i] + DIAGNOSTICS):
            raise ValueError("overlapping loader or HBM diagnostic ranges")
    return ranges


def validate_buffer(layout, base, size):
    ranges = image_ranges(layout)
    first, limit = layout["dma_window"]
    end = base + size
    if (size != layout.get("dma_bytes", 0x200000) or base & 0xfff or
            not GPU_FLOOR <= first <= base < end <= limit <= RAM_END):
        raise ValueError("allocation outside the GPU DMA aperture")
    if any(overlaps((base, end), other) for other in ranges + DIAGNOSTICS):
        raise ValueError("allocation overlaps loader or HBM diagnostic ranges")


def linux_mapping(layout, base, size, resources, boot):
    validate_buffer(layout, base, size)
    banks = [(int(a, 16), int(b, 16) + 1) for a, b in re.findall(
        r"(?m)^([0-9a-f]+)-([0-9a-f]+) : System RAM$", resources)]
    end = base + size
    if "dma_api" in layout:
        if not any(a <= base and end <= b for a, b in banks):
            raise ValueError("DMA API allocation is outside reported System RAM")
    elif not banks or any(overlaps((base, end), bank) for bank in banks):
        raise ValueError("reserved DMA pool overlaps reported System RAM")
    mapping = re.search(r"coherent DMA (?:0x)?([0-9a-fA-F]+), (\d+) bytes, "
                        r"CPU mapping (?:0x)?([0-9a-fA-F]+)", boot)
    if not mapping or (int(mapping[1], 16), int(mapping[2]), int(mapping[3], 16)) != (
            base, size, 0x80000000 + base):
        raise ValueError("unexpected LA32R uncached CPU alias for coherent DMA")
    return dict(base=base, end=end, bytes=size, cpu_alias=int(mapping[3], 16),
                system_ram=banks, allocation=layout.get("dma_api", "reserved shared-dma-pool"))
