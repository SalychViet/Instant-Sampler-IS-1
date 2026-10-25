// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#pragma once
#include <atomic>
#include <memory>
#include <mutex>
#include <string>

#include <oboe/Oboe.h>

#include "EngineCore.h"
#include "LinkSync.h"

namespace mpc {

// Владелец Oboe-потока. Вызывает EngineCore::render из аудио-callback.
class AudioOutput : public std::enable_shared_from_this<AudioOutput>,
                    public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    EngineCore& core() { return core_; }
    LinkSync& link() { return link_; }

    bool start();
    void stop();
    int xruns();
    std::string describe();

    // --- Oboe callbacks ---
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openAndStartLocked();

    EngineCore core_;
    LinkSync link_;
    int64_t outputLatencyUs_ = 20000;   // обновляется в callback раз в ~0.5 с
    int latencyCountdown_ = 0;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex mutex_;
    std::atomic<bool> wantRunning_{false};
};

}  // namespace mpc
