#pragma once
#include <cstdint>
#if defined(__SSE__)
#include <xmmintrin.h>
#endif

namespace mpc {

// Включает flush-to-zero для текущего потока (вызывать из аудио-потока).
inline void enableFlushToZero() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ull << 24);  // FZ
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__SSE__)
    _mm_setcsr(_mm_getcsr() | 0x8040);  // FTZ | DAZ
#endif
}

}  // namespace mpc
