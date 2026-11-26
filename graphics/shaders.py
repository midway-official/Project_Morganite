#!/usr/bin/env python3
"""Link the SIMT raster library with independently selectable vertex/fragment programs."""
import argparse
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "sw"))
from asm import assemble


def bits(v):
    return struct.unpack("<I", struct.pack("<f", v))[0]


def reciprocal(src, dst, sfu=False):
    if sfu:
        return [f"rcp.approx.f32 r{dst},r{src}", "li r29,0x80000000",
                f"xor r29,r{src},r29", "li r30,0x3f800000"] + [
                    op for _ in range(2) for op in
                    (f"fma r28,r29,r{dst},r30", f"fma r{dst},r28,r{dst},r{dst}")]
    # Positive finite inputs in the supported clip volume. Four FP32 Newton iterations.
    return [f"li r30,0x7ef311c3", f"sub r{dst},r30,r{src}", "li r29,0x80000000",
            f"xor r29,r{src},r29", "li r30,0x40000000"] + [
                s for _ in range(4) for s in (f"fma r28,r29,r{dst},r30", f"fmul r{dst},r{dst},r28")] + [
                    "li r30,0x3f800000", f"fma r28,r29,r{dst},r30", f"fma r{dst},r28,r{dst},r{dst}"]


def vertex(shift, width, height, units=()):
    out = ["arg r1", "tid r2", "lw r3,0(r1)", "slli r4,r2,5", "add r3,r3,r4",
           "lw r4,4(r1)", "slli r5,r2,6", "add r4,r4,r5"]
    out += [f"lw r{8+i},{4*i}(r3)" for i in range(4)]
    for stage in range(3):
        for row in range(4):
            for j in range(4):
                out += [f"lw r20,{64+stage*64+row*16+j*4}(r1)"]
                out += [f"fmul r{12+row},r20,r8" if j == 0 else f"fma r{12+row},r20,r{8+j},r{12+row}"]
        out += [f"addi r{8+i},r{12+i},0" for i in range(4)]
    if shift:
        out += ["li r20,0x3e000000", "fma r8,r11,r20,r8"]
    out += [f"sw r{8+i},{4*i}(r4)" for i in range(4)]
    out += reciprocal(11, 16,"sfu" in units)
    out += ["sw r16,28(r4)", "fmul r10,r10,r16", "sw r10,24(r4)"]
    for axis, scale in enumerate((width*8, -height*8)):
        out += [f"fmul r17,r{8+axis},r16", f"li r18,{bits(scale)}", f"li r19,{bits(abs(scale))}",
                "fma r17,r17,r18,r19", "ftoi r17,r17", f"sw r17,{16+axis*4}(r4)"]
    for i in range(4):
        out += [f"lw r17,{16+4*i}(r3)", "fmul r17,r17,r16", f"sw r17,{32+i*4}(r4)"]
    return out + ["exit"]


def setup(units=()):
    out = ["arg r1", "tid r2", "lw r3,8(r1)", "lw r4,4(r1)", "lw r6,12(r1)",
           "slli r5,r2,7", "add r6,r6,r5", "addi r5,r0,12", "mul r5,r5,r2", "add r3,r3,r5"]
    for i in range(3):
        out += [f"lw r{7+i},{4*i}(r3)", f"slli r{7+i},r{7+i},6", f"add r{7+i},r{7+i},r4",
                f"lw r{10+2*i},16(r{7+i})", f"lw r{11+2*i},20(r{7+i})"]
    out += ["sub r16,r12,r10", "sub r17,r15,r11", "mul r18,r16,r17", "sub r16,r13,r11",
            "sub r17,r14,r10", "mul r19,r16,r17", "sub r18,r18,r19", "slt r19,r18,r0", "split r19,positive"]
    for a, b in ((8,9),(12,14),(13,15)):
        out += [f"addi r19,r{a},0", f"addi r{a},r{b},0", f"addi r{b},r19,0"]
    out += ["sub r18,r0,r18", "next positive", "positive: join", "sltu r19,r0,r18", "sw r19,124(r6)",
            "xori r19,r19,1", "add r18,r18,r19", "itof r18,r18"]
    out += reciprocal(18,19,"sfu" in units) + ["sw r19,48(r6)"]
    if "bins" in units or "setup" in units:
        for i in range(3):
            out += [f"slli r{23+i},r{11+2*i},16", f"or r{23+i},r{23+i},r{10+2*i}"]
    if "bins" in units:
        out += ["bin.bounds r19,r23,r24,r25", "lw r20,32(r1)", "slli r21,r2,2",
                "add r20,r20,r21", "sw r19,0(r20)"]
    for i in range(3):
        a, b = (i+1)%3, (i+2)%3
        xa, ya, xb, yb = 10+2*a, 11+2*a, 10+2*b, 11+2*b
        if "setup" in units:
            out += [f"tri.delta r22,r{23+a},r{23+b}", "slli r16,r22,16", "srai r16,r16,16",
                    "srai r17,r22,16", f"tri.c r18,r{23+a},r{23+b}", f"tri.bias r19,r{23+a},r{23+b}"]
        else:
            out += [f"sub r16,r{ya},r{yb}", f"sub r17,r{xb},r{xa}", f"mul r18,r{xa},r{yb}",
                f"mul r19,r{xb},r{ya}", "sub r18,r18,r19", "slt r19,r0,r16", "sltiu r20,r16,1",
                "slt r21,r0,r17", "and r20,r20,r21", "or r19,r19,r20", "addi r19,r19,-1"]
        out += [f"sw r{16+j},{16*i+4*j}(r6)" for j in range(4)]
    for field in range(6):  # z/w, 1/w, r/w, g/w, b/w, a/w
        for i in range(3):
            out += [f"lw r16,{24+field*4}(r{7+i})", f"sw r16,{52+field*12+i*4}(r6)"]
    return out+["exit"]


def interpolate(load, base, offset, dst, b1, b2):
    # Base-plus-differences preserves constant depth and constant varyings exactly.
    return [f"{load} r24,{offset}(r{base})", f"{load} r25,{offset+4}(r{base})",
            f"{load} r26,{offset+8}(r{base})", "fsub r25,r25,r24", "fsub r26,r26,r24",
            f"fma r{dst},r25,r{b1},r24", f"fma r{dst},r26,r{b2},r{dst}"]


def bin_tiles(width):
    # Four adjacent tiles per group, including frame widths with more than 32 tiles.
    strips = width//32
    return ["arg r1","gid r10","tid r3",f"li r4,{(65536+strips-1)//strips}",
            "mul r2,r10,r4","srli r2,r2,16",f"li r4,{strips}","mul r4,r2,r4","sub r4,r10,r4",
            "slli r4,r4,2","add r3,r4,r3",f"li r4,{width//8}","mul r4,r4,r2","add r4,r4,r3",
            "slli r4,r4,2","lw r5,36(r1)","add r5,r5,r4","slli r2,r2,23","slli r3,r3,7",
            "or r2,r2,r3","lw r6,16(r1)","lw r7,32(r1)","li r8,1","li r9,0","bz r6,done",
            "next_tri: lw r10,0(r7)","bin.mask r11,r10,r2,r8","or r9,r9,r11","slli r8,r8,1",
            "addi r7,r7,4","addi r6,r6,-1","bz r6,done","jmp next_tri","done: sw r9,0(r5)","exit"]


def pixel(swap, width, height, units=()):
    assert width % 32 == 0
    tiles = width // 32
    magic = (65536 + tiles - 1)//tiles
    assert all((i*magic >> 16) == i//tiles for i in range(tiles*height))
    out = ["arg r1", "gid r10", "tid r11", "slli r31,r10,5", "add r31,r31,r11", f"li r12,{magic}",
           "mul r3,r10,r12", "srli r3,r3,16", f"li r12,{tiles}", "mul r12,r12,r3",
           "sub r2,r10,r12", "slli r2,r2,5", "add r2,r2,r11", "slli r2,r2,4", "addi r2,r2,8",
           "slli r3,r3,4", "addi r3,r3,8", "slli r11,r11,2", "lw r4,12(r1)", "lw r5,16(r1)",
           "li r6,0x3f800000", "addi r7,r0,0", "bz r5,shade"]
    if "bins" in units:
        out += ["srli r12,r3,7", f"li r21,{width//8}", "mul r12,r12,r21", "srli r21,r2,7",
                "add r12,r12,r21", "slli r12,r12,2", "lw r21,36(r1)", "add r12,r12,r21", "lw r12,0(r12)"]
    out += ["triangle: add r20,r4,r11","lw r20,0(r20)", "s.sw r20,0(r11)", "bar", "s.lw r20,124(r0)"]
    if "bins" in units:
        out += ["and r20,r20,r12", "srli r12,r12,1"]
    for i in range(3):
        out += [f"s.lw r21,{16*i}(r0)", f"s.lw r22,{16*i+4}(r0)", f"s.lw r23,{16*i+8}(r0)",
                ]
        if "raster" in units:
            out += ["slli r22,r22,16","slli r21,r21,16","srli r21,r21,16","or r22,r22,r21",
                    "slli r21,r3,16","or r21,r21,r2",f"rast.edge r{13+i},r22,r21,r23",
                    f"s.lw r21,{16*i+12}(r0)",f"add r{24+i},r21,r{13+i}"]
        else:
            out += ["mul r21,r21,r2", "mul r22,r22,r3", f"add r{13+i},r21,r22", f"add r{13+i},r{13+i},r23",
                    f"s.lw r21,{16*i+12}(r0)", f"add r21,r21,r{13+i}", "slti r21,r21,0", "xori r21,r21,1",
                    "and r20,r20,r21"]
    if "raster" in units:
        out += ["rast.cover r21,r24,r25,r26","and r20,r20,r21"]
    out += ["split r20,covered_end", "s.lw r21,48(r0)", "itof r14,r14", "itof r15,r15",
            "fmul r14,r14,r21", "fmul r15,r15,r21"]
    out += interpolate("s.lw",0,52,16,14,15)
    out += [("rop.less" if "rop" in units else "flt")+" r20,r16,r6", "split r20,depth_end", "addi r6,r16,0", "addi r7,r4,0", "addi r8,r14,0",
            "addi r9,r15,0", "next depth_end", "depth_end: join", "next covered_end", "covered_end: join",
            "bar", "addi r4,r4,128", "addi r5,r5,-1", "bz r5,shade", "jmp triangle",
            "shade: li r18,0xff000000", "addi r17,r0,0", "split r7,background"]
    out += interpolate("lw",7,64,16,8,9) + reciprocal(16,19,"sfu" in units)
    for i in range(4):
        out += interpolate("lw",7,76+i*12,20+i,8,9) + [f"fmul r{20+i},r{20+i},r19"]
    # Fragment ABI: r20..r23 hold interpolated RGBA. r24..r30 are temporary.
    if swap:
        out += ["addi r24,r20,0", "addi r20,r22,0", "addi r22,r24,0"]
    out += ["li r29,0x437f0000", "addi r30,r0,255", "addi r18,r0,0"]
    for i in range(4):
        out += [f"fmul r24,r{20+i},r29", "ftou r24,r24", "sltu r25,r30,r24", "sub r25,r0,r25",
                "xor r26,r24,r30", "and r26,r26,r25", "xor r24,r24,r26", f"slli r24,r24,{8*i}",
                "or r18,r18,r24"]
    out += ["lw r17,12(r1)", "sub r17,r7,r17", "srli r17,r17,7", "addi r17,r17,1", "next background",
            "background: join", "slli r31,r31,2"]
    for field, reg in ((20,18),(24,6),(28,17)):
        out += [f"lw r4,{field}(r1)", "add r4,r4,r31", f"sw r{reg},0(r4)"]
    return out+["exit"]


UNITS = ("setup","bins","raster","rop","sfu")


def programs(width=320, height=240, units=()):
    stages = {"vertex":vertex(False,width,height,units), "vertex_shift":vertex(True,width,height,units),
              "setup":setup(units), "pixel":pixel(False,width,height,units), "pixel_swap":pixel(True,width,height,units)}
    if "bins" in units:
        stages["bin"] = bin_tiles(width)
    return stages


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out",type=Path)
    parser.add_argument("--units", choices=UNITS, nargs="+", default=[])
    args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    for name, lines in programs(units=args.units).items():
        text="\n".join(lines)+"\n"
        words=assemble(text)
        assert len(words)<=1024
        (args.out/(name+".s")).write_text(text)
        (args.out/(name+".bin")).write_bytes(struct.pack("<"+"I"*len(words),*words))
