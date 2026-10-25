// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#include "AudioOutput.h"

#include <android/log.h>

#include <algorithm>

#define LOG_TAG "mpcengine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace mpc {

bool AudioOutput::openAndStartLocked() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)   // если нельзя — Oboe сам даст Shared
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setDataCallback(shared_from_this())
        ->setErrorCallback(shared_from_this());

    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        stream_.reset();
        return false;
    }

    // Буфер = 2 burst: компромисс между латентностью и риском XRun.
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    core_.setSampleRate(stream_->getSampleRate());

    r = stream_->requestStart();
    if (r != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(r));
        stream_->close();
        stream_.reset();
        return false;
    }
    LOGI("stream started: %d Hz, burst %d, buffer %d", stream_->getSampleRate(),
         stream_->getFramesPerBurst(), stream_->getBufferSizeInFrames());
    return true;
}

bool AudioOutput::start() {
    std::lock_guard<std::mutex> lk(mutex_);
    if (stream_) return true;
    wantRunning_ = true;
    return openAndStartLocked();
}

void AudioOutput::stop() {
    wantRunning_ = false;  // до взятия мьютекса: см. onErrorAfterClose
    std::lock_guard<std::mutex> lk(mutex_);
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();   // разрываем цикл shared_ptr (стрим держит callbacks = this)
    }
}

int AudioOutput::xruns() {
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lk(mutex_);
        s = stream_;
    }
    if (!s) return 0;
    auto r = s->getXRunCount();
    return r.error() == oboe::Result::OK ? r.value() : 0;
}

std::string AudioOutput::describe() {
    std::shared_ptr<oboe::AudioStream> s;
    {
        std::lock_guard<std::mutex> lk(mutex_);
        s = stream_;
    }
    if (!s) return "stopped";
    std::string out = std::to_string(s->getSampleRate()) + " Hz, burst " +
                      std::to_string(s->getFramesPerBurst()) + ", buffer " +
                      std::to_string(s->getBufferSizeInFrames()) + ", " +
                      (s->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared") +
                      ", " + oboe::convertToText(s->getAudioApi());
    auto lat = s->calculateLatencyMillis();
    if (lat.error() == oboe::Result::OK) out += ", ~" + std::to_string(static_cast<int>(lat.value())) + " ms";
    return out;
}

oboe::DataCallbackResult AudioOutput::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                   int32_t numFrames) {
    // Никаких аллокаций, мьютексов, логов и JNI здесь.
    if (link_.enabled() && --latencyCountdown_ <= 0) {
        // Латентность вывода по меткам времени AAudio (точнее, чем размер буфера).
        latencyCountdown_ = stream->getSampleRate() / std::max(1, numFrames) / 2;
        auto lat = stream->calculateLatencyMillis();
        if (lat.error() == oboe::Result::OK && lat.value() > 0.0 && lat.value() < 500.0)
            outputLatencyUs_ = static_cast<int64_t>(lat.value() * 1000.0);
    }
    const LinkFrame lf = link_.frame(outputLatencyUs_);
    core_.render(static_cast<float*>(audioData), numFrames, &lf);
    return oboe::DataCallbackResult::Continue;
}

void AudioOutput::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Например, отключили наушники/USB: пересоздаём поток, состояние движка сохраняется.
    if (error != oboe::Result::ErrorDisconnected || !wantRunning_) return;
    std::lock_guard<std::mutex> lk(mutex_);
    if (!wantRunning_) return;
    stream_.reset();
    core_.allNotesOff();
    openAndStartLocked();
}

}  // namespace mpc
