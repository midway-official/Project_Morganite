"""Scalar specification for the optional Morganite graphics instructions."""
import math
import struct


def fp(bits):
    return struct.unpack("<f", struct.pack("<I", bits))[0]


def graphics(fn, a, b, c):
    def half(v):
        return (v & 32767) - (v & 32768)
    ax, ay = half(a), half(a >> 16)
    bx, by = half(b), half(b >> 16)
    aa, bb = ay-by, bx-ax
    if fn == 0: return (bb << 16) | (aa & 65535)
    if fn == 1: return ax*by-ay*bx
    if fn == 2: return 0 if aa > 0 or (aa == 0 and bb > 0) else -1
    if fn == 3:
        xs = [(v >> 7) & 255 for v in (a,b,c)]
        ys = [(v >> 23) & 255 for v in (a,b,c)]
        return min(xs) | max(xs)<<8 | min(ys)<<16 | max(ys)<<24
    if fn == 4:
        tx, ty = (b>>7)&255, (b>>23)&255
        return c if (a&255) <= tx <= ((a>>8)&255) and ((a>>16)&255) <= ty <= a>>24 else 0
    if fn == 5: return ax*bx+ay*by+c
    if fn == 6: return int((a|b|c) >> 31 == 0)
    if fn == 7: return int(fp(a) < fp(b))
    if fn == 8:
        alpha = a>>24
        def channel(i):
            src = 255 if i==3 else (a>>(8*i))&255
            dst = (b>>(8*i))&255
            return (src*alpha+dst*(255-alpha)+127)//255
        return sum(channel(i)<<(8*i) for i in range(4))
    exp, frac, sign = (a>>23)&255, a&0x7fffff, a&0x80000000
    if exp == 0: return sign | 0x7f800000
    if (fn == 10 and sign) or (exp == 255 and frac): return 0x7fc00000
    if exp == 255: return sign
    mant = 1.0 + (((frac >> 16)+0.5)/128 if frac else 0)
    # Odd exponent and fraction zero use the first reciprocal-square-root segment.
    if fn == 10 and not frac and (exp-127)%2: mant += 0.5/128
    value = math.ldexp(mant, exp-127)
    value = 1/value if fn == 9 else 1/math.sqrt(value)
    m, e = math.frexp(value)
    if e-1 < -126: return sign
    return sign | ((e+126)<<23) | (int((2*m-1)*32768+0.5)<<8)
