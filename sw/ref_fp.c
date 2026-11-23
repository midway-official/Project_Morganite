#include <stdint.h>
#include "softfloat.h"

/* Scalar oracle ABI. Result in bits 31:0, IEEE flags in bits 36:32. */
uint64_t morganite_reference_fp(unsigned op, uint32_t av, uint32_t bv, uint32_t cv)
{
    float32_t a = {av}, b = {bv}, c = {cv};
    uint32_t result;
    softfloat_roundingMode = softfloat_round_near_even;
    softfloat_detectTininess = softfloat_tininess_afterRounding;
    softfloat_exceptionFlags = 0;
    switch (op) {
    case 0:  result = f32_mulAdd(a, b, c).v; break;
    case 1:  result = f32_add(a, b).v; break;
    case 2:  result = f32_sub(a, b).v; break;
    case 3:  result = f32_mul(a, b).v; break;
    case 4:  result = f32_eq(a, b); break;
    case 5:  result = f32_lt(a, b); break;
    case 6:  result = f32_le(a, b); break;
    case 7:  result = i32_to_f32((int32_t)av).v; break;
    case 8:  result = ui32_to_f32(av).v; break;
    case 9:  result = f32_to_i32(a, softfloat_round_near_even, true); break;
    case 10: result = f32_to_ui32(a, softfloat_round_near_even, true); break;
    case 11: result = f32_to_i32(a, softfloat_round_minMag, true); break;
    case 12: result = f32_to_ui32(a, softfloat_round_minMag, true); break;
    default: return UINT64_MAX;
    }
    return ((uint64_t)softfloat_exceptionFlags << 32) | result;
}
