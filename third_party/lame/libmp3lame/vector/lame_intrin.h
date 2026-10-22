/* Заглушка: SIMD-оптимизации LAME (xmm) не используются в сборке IS-1. */
#ifndef LAME_INTRIN_H
#define LAME_INTRIN_H
void init_xrpow_core_sse(gr_info * const cod_info, FLOAT xrpow[576], int upper, FLOAT * sum);
void fht_SSE2(FLOAT* , int);
#endif
