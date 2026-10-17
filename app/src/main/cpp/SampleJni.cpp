// JNI для операций над семплами (вне аудио-потока). Класс Kotlin: audio.SampleOps.
#include <jni.h>

#include <vector>

#include "SampleOps.h"
#include "WavCodec.h"

namespace {

// RAII-обёртка над GetFloatArrayElements. ReleaseMode: 0 = копировать назад, JNI_ABORT = только чтение.
template <int ReleaseMode>
struct FloatElems {
    JNIEnv* env;
    jfloatArray arr;
    jfloat* p;
    jsize n;
    FloatElems(JNIEnv* e, jfloatArray a)
        : env(e), arr(a), p(a ? e->GetFloatArrayElements(a, nullptr) : nullptr),
          n(a ? e->GetArrayLength(a) : 0) {}
    ~FloatElems() { if (p) env->ReleaseFloatArrayElements(arr, p, ReleaseMode); }
    int64_t frames(int channels) const { return channels > 0 ? n / channels : 0; }
};

}  // namespace

#define JNI(name) Java_com_example_mpcpad_audio_SampleOps_##name

extern "C" {

JNIEXPORT jfloatArray JNICALL JNI(nativeComputePeaks)(JNIEnv* env, jobject, jfloatArray data,
                                                      jint channels, jint bins) {
    if (bins <= 0) return nullptr;
    FloatElems<JNI_ABORT> in(env, data);
    std::vector<float> out(static_cast<size_t>(bins) * 2, 0.0f);
    mpc::ops::computePeaks(in.p, channels, in.frames(channels), bins, out.data());
    jfloatArray res = env->NewFloatArray(bins * 2);
    if (res) env->SetFloatArrayRegion(res, 0, bins * 2, out.data());
    return res;
}

JNIEXPORT jfloat JNICALL JNI(nativeNormalize)(JNIEnv* env, jobject, jfloatArray data, jint channels,
                                              jint startFrame, jint endFrame, jfloat target) {
    FloatElems<0> a(env, data);
    return mpc::ops::normalize(a.p, channels, a.frames(channels), startFrame, endFrame, target);
}

JNIEXPORT void JNICALL JNI(nativeFadeIn)(JNIEnv* env, jobject, jfloatArray data, jint channels,
                                         jint startFrame, jint endFrame) {
    FloatElems<0> a(env, data);
    mpc::ops::fadeIn(a.p, channels, a.frames(channels), startFrame, endFrame);
}

JNIEXPORT void JNICALL JNI(nativeFadeOut)(JNIEnv* env, jobject, jfloatArray data, jint channels,
                                          jint startFrame, jint endFrame) {
    FloatElems<0> a(env, data);
    mpc::ops::fadeOut(a.p, channels, a.frames(channels), startFrame, endFrame);
}

JNIEXPORT void JNICALL JNI(nativeReverse)(JNIEnv* env, jobject, jfloatArray data, jint channels,
                                          jint startFrame, jint endFrame) {
    FloatElems<0> a(env, data);
    mpc::ops::reverseRegion(a.p, channels, a.frames(channels), startFrame, endFrame);
}

// Возвращает [start, end) или пустой массив, если семпл целиком тише порога.
JNIEXPORT jintArray JNICALL JNI(nativeFindNonSilent)(JNIEnv* env, jobject, jfloatArray data,
                                                     jint channels, jfloat threshold) {
    FloatElems<JNI_ABORT> a(env, data);
    int64_t s = 0, e = 0;
    if (!mpc::ops::findNonSilent(a.p, channels, a.frames(channels), threshold, &s, &e))
        return env->NewIntArray(0);
    jint v[2] = {static_cast<jint>(s), static_cast<jint>(e)};
    jintArray res = env->NewIntArray(2);
    if (res) env->SetIntArrayRegion(res, 0, 2, v);
    return res;
}

JNIEXPORT jintArray JNICALL JNI(nativeDetectTransients)(JNIEnv* env, jobject, jfloatArray data,
                                                        jint channels, jint sampleRate,
                                                        jfloat sensitivity, jfloat minGapMs) {
    FloatElems<JNI_ABORT> a(env, data);
    auto pos = mpc::ops::detectTransients(a.p, channels, a.frames(channels), sampleRate, sensitivity,
                                          minGapMs);
    std::vector<jint> v(pos.begin(), pos.end());
    jintArray res = env->NewIntArray(static_cast<jsize>(v.size()));
    if (res && !v.empty()) env->SetIntArrayRegion(res, 0, static_cast<jsize>(v.size()), v.data());
    return res;
}

// info: IntArray(2) → [channels, sampleRate]. Возвращает null, если формат не поддержан.
JNIEXPORT jfloatArray JNICALL JNI(nativeParseWav)(JNIEnv* env, jobject, jbyteArray bytes,
                                                  jintArray info) {
    if (!bytes) return nullptr;
    const jsize n = env->GetArrayLength(bytes);
    jbyte* p = env->GetByteArrayElements(bytes, nullptr);
    if (!p) return nullptr;
    std::vector<float> out;
    mpc::WavInfo wi;
    const bool ok = mpc::parseAudioFile(reinterpret_cast<const uint8_t*>(p), static_cast<size_t>(n), out, wi);
    env->ReleaseByteArrayElements(bytes, p, JNI_ABORT);
    if (!ok) return nullptr;

    if (info && env->GetArrayLength(info) >= 2) {
        jint v[2] = {wi.channels, wi.sampleRate};
        env->SetIntArrayRegion(info, 0, 2, v);
    }
    jfloatArray res = env->NewFloatArray(static_cast<jsize>(out.size()));
    if (res) env->SetFloatArrayRegion(res, 0, static_cast<jsize>(out.size()), out.data());
    return res;
}

JNIEXPORT jbyteArray JNICALL JNI(nativeEncodeWav16)(JNIEnv* env, jobject, jfloatArray data,
                                                    jint channels, jint sampleRate) {
    FloatElems<JNI_ABORT> a(env, data);
    auto wav = mpc::encodeWav16(a.p, channels, a.frames(channels), sampleRate);
    if (wav.empty()) return nullptr;
    jbyteArray res = env->NewByteArray(static_cast<jsize>(wav.size()));
    if (res) env->SetByteArrayRegion(res, 0, static_cast<jsize>(wav.size()),
                                     reinterpret_cast<const jbyte*>(wav.data()));
    return res;
}

}  // extern "C"
