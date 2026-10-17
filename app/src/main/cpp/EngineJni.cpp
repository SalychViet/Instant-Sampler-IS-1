// Единственная точка входа из Kotlin. Здесь нет тайминга и DSP — только маршалинг.
#include <jni.h>

#include <memory>
#include <vector>

#include "AudioOutput.h"

using mpc::AudioOutput;
using mpc::Sample;

namespace {
using Handle = std::shared_ptr<AudioOutput>;
AudioOutput* get(jlong h) { return h ? reinterpret_cast<Handle*>(h)->get() : nullptr; }
}  // namespace

#define JNI(name) Java_com_example_mpcpad_audio_AudioEngine_##name

extern "C" {

JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Handle(std::make_shared<AudioOutput>()));
}

JNIEXPORT void JNICALL JNI(nativeDestroy)(JNIEnv*, jobject, jlong h) {
    if (!h) return;
    auto* handle = reinterpret_cast<Handle*>(h);
    (*handle)->stop();
    delete handle;
}

JNIEXPORT jboolean JNICALL JNI(nativeStart)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o && o->start();
}

JNIEXPORT void JNICALL JNI(nativeStop)(JNIEnv*, jobject, jlong h) {
    if (auto* o = get(h)) o->stop();
}

JNIEXPORT jboolean JNICALL JNI(nativeNoteOn)(JNIEnv*, jobject, jlong h, jint pad, jfloat vel) {
    auto* o = get(h);
    return o && o->core().noteOn(pad, vel);
}

JNIEXPORT jboolean JNICALL JNI(nativeNoteOff)(JNIEnv*, jobject, jlong h, jint pad) {
    auto* o = get(h);
    return o && o->core().noteOff(pad);
}

JNIEXPORT jboolean JNICALL JNI(nativeSetParam)(JNIEnv*, jobject, jlong h, jint pad, jint id,
                                               jfloat value) {
    auto* o = get(h);
    return o && o->core().setParam(pad, id, value);
}

JNIEXPORT jboolean JNICALL JNI(nativeSetMaster)(JNIEnv*, jobject, jlong h, jfloat v) {
    auto* o = get(h);
    return o && o->core().setMasterVolume(v);
}

// data: interleaved PCM float, channels = 1 или 2 (остальное: берутся первые два канала).
JNIEXPORT jboolean JNICALL JNI(nativeLoadSample)(JNIEnv* env, jobject, jlong h, jint pad,
                                                 jfloatArray data, jint channels, jint rate) {
    auto* o = get(h);
    if (!o || !data || channels < 1 || rate < 8000) return JNI_FALSE;

    const jsize total = env->GetArrayLength(data);
    const int64_t frames = total / channels;
    if (frames < 2) return JNI_FALSE;

    std::vector<float> in(static_cast<size_t>(total));
    env->GetFloatArrayRegion(data, 0, total, in.data());

    auto s = std::make_unique<Sample>();
    s->frames = frames;
    s->sampleRate = rate;
    s->data.resize(static_cast<size_t>(frames) * 2);
    for (int64_t i = 0; i < frames; ++i) {
        const float l = in[static_cast<size_t>(i) * channels];
        const float r = channels > 1 ? in[static_cast<size_t>(i) * channels + 1] : l;
        s->data[2 * i] = l;
        s->data[2 * i + 1] = r;
    }
    return o->core().loadSample(pad, std::move(s));
}

JNIEXPORT void JNICALL JNI(nativeAllNotesOff)(JNIEnv*, jobject, jlong h) {
    if (auto* o = get(h)) o->core().allNotesOff();
}

JNIEXPORT jlong JNICALL JNI(nativeActiveMask)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? static_cast<jlong>(o->core().activePadMask()) : 0;
}

JNIEXPORT jfloat JNICALL JNI(nativeMasterPeak)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? o->core().masterPeak() : 0.0f;
}

JNIEXPORT jint JNICALL JNI(nativeActiveVoices)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? o->core().activeVoiceCount() : 0;
}

// ---- секвенсор ----

JNIEXPORT jboolean JNICALL JNI(nativeSeq)(JNIEnv*, jobject, jlong h, jint op, jint pat, jint pad,
                                          jint a, jint b, jfloat f) {
    auto* o = get(h);
    if (!o || op < 0 || op > static_cast<jint>(mpc::SeqOp::SetLength)) return JNI_FALSE;
    const auto sop = static_cast<mpc::SeqOp>(op);
    if (o->link().enabled()) {
        // С Link старт/стоп и темп — общие для всей сессии: секвенсор подхватит их в аудио-потоке.
        if (sop == mpc::SeqOp::Play) { o->link().requestPlaying(true); return JNI_TRUE; }
        if (sop == mpc::SeqOp::Stop) {
            o->link().requestPlaying(false);
            return o->core().seq(sop, pat, pad, a, b, f);
        }
        if (sop == mpc::SeqOp::SetBpm) o->link().setTempo(f);
    }
    return o->core().seq(sop, pat, pad, a, b, f);
}

JNIEXPORT jlong JNICALL JNI(nativeSeqState)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? static_cast<jlong>(o->core().seqState()) : 0;
}

// Копирует события паттерна в out (упакованные 64-битные слова). Возвращает число событий или -1.
JNIEXPORT jint JNICALL JNI(nativeSeqCopyPattern)(JNIEnv* env, jobject, jlong h, jint pat,
                                                 jlongArray out) {
    auto* o = get(h);
    if (!o || !out) return -1;
    const jsize cap = env->GetArrayLength(out);
    uint64_t buf[mpc::kMaxPatternEvents];
    const int n = o->core().seqCopyPattern(pat, buf, static_cast<int>(cap));
    if (n < 0) return -1;
    static_assert(sizeof(jlong) == sizeof(uint64_t), "jlong must be 64-bit");
    env->SetLongArrayRegion(out, 0, n, reinterpret_cast<const jlong*>(buf));
    return n;
}

// Число событий в каждом из 64 паттернов (out: IntArray(64)).
JNIEXPORT void JNICALL JNI(nativeSeqPatternCounts)(JNIEnv* env, jobject, jlong h, jintArray out) {
    auto* o = get(h);
    if (!o || !out || env->GetArrayLength(out) < mpc::kNumPatterns) return;
    jint counts[mpc::kNumPatterns];
    for (int i = 0; i < mpc::kNumPatterns; ++i) counts[i] = o->core().seqPatternCount(i);
    env->SetIntArrayRegion(out, 0, mpc::kNumPatterns, counts);
}

JNIEXPORT jint JNICALL JNI(nativeSeqPatternBars)(JNIEnv*, jobject, jlong h, jint pat) {
    auto* o = get(h);
    return o ? o->core().seqPatternBars(pat) : 1;
}

// ---- Ableton Link ----

JNIEXPORT void JNICALL JNI(nativeLinkSetEnabled)(JNIEnv*, jobject, jlong h, jboolean on) {
    if (auto* o = get(h)) o->link().setEnabled(on);
}

JNIEXPORT void JNICALL JNI(nativeLinkSetLatency)(JNIEnv*, jobject, jlong h, jfloat ms) {
    if (auto* o = get(h)) o->link().setLatencyMs(ms);
}

JNIEXPORT jint JNICALL JNI(nativeLinkPeers)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? o->link().peers() : 0;
}

JNIEXPORT jfloat JNICALL JNI(nativeLinkTempo)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? static_cast<jfloat>(o->link().tempo()) : 120.0f;
}

JNIEXPORT jint JNICALL JNI(nativeXRunCount)(JNIEnv*, jobject, jlong h) {
    auto* o = get(h);
    return o ? o->xruns() : 0;
}

JNIEXPORT jstring JNICALL JNI(nativeDescribe)(JNIEnv* env, jobject, jlong h) {
    auto* o = get(h);
    return env->NewStringUTF(o ? o->describe().c_str() : "no engine");
}

}  // extern "C"
