// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Salavat (SALYCH MUSIC). IS-1 — see LICENSE.

#include "LinkSync.h"

#include <ableton/Link.hpp>

namespace mpc {
namespace {
constexpr double kQuantum = 4.0;   // такт 4/4
}

LinkSync::LinkSync() : link_(std::make_unique<ableton::Link>(120.0)) {
    link_->enableStartStopSync(true);
    link_->setNumPeersCallback([this](std::size_t n) { peers_.store(static_cast<int>(n), std::memory_order_relaxed); });
    link_->setTempoCallback([this](double bpm) { tempo_.store(bpm, std::memory_order_relaxed); });
}

LinkSync::~LinkSync() { link_->enable(false); }

int64_t LinkSync::nowUs() const { return link_->clock().micros().count(); }

void LinkSync::setEnabled(bool on) {
    link_->enable(on);
    enabled_.store(on, std::memory_order_relaxed);
    if (!on) peers_.store(0, std::memory_order_relaxed);
}

void LinkSync::requestPlaying(bool playing) {
    auto st = link_->captureAppSessionState();
    const auto now = std::chrono::microseconds(nowUs());
    if (playing) {
        // Одни в сессии — доля 0 придётся на момент, когда первый звук дойдёт до динамика (старт сразу).
        // С другими участниками Link сохранит их фазу и старт будет по ближайшему такту.
        const auto at = now + std::chrono::microseconds(lastOutputLatencyUs_.load(std::memory_order_relaxed) +
                                                        userLatencyUs_.load(std::memory_order_relaxed) + 20000);
        st.setIsPlayingAndRequestBeatAtTime(true, at, 0.0, kQuantum);
    } else {
        st.setIsPlaying(false, now);
    }
    link_->commitAppSessionState(st);
}

void LinkSync::setTempo(double bpm) {
    auto st = link_->captureAppSessionState();
    st.setTempo(bpm, std::chrono::microseconds(nowUs()));
    link_->commitAppSessionState(st);
}

LinkFrame LinkSync::frame(int64_t outputLatencyUs) {
    LinkFrame f;
    f.enabled = enabled_.load(std::memory_order_relaxed);
    if (!f.enabled) return f;
    lastOutputLatencyUs_.store(outputLatencyUs, std::memory_order_relaxed);
    const auto host = std::chrono::microseconds(nowUs() + outputLatencyUs + userLatencyUs_.load(std::memory_order_relaxed));
    const auto st = link_->captureAudioSessionState();
    f.beat = st.beatAtTime(host, kQuantum);
    f.tempo = st.tempo();
    f.playing = st.isPlaying();
    f.quantum = kQuantum;
    return f;
}

}  // namespace mpc
