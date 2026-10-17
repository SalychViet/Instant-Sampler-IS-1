#pragma once
#include <atomic>
#include <memory>

#include "Sequencer.h"

namespace ableton { class Link; }

namespace mpc {

// Ableton Link: общий темп, фаза такта и старт/стоп с Ableton Live и другими Link-приложениями в сети.
//
// control-потоки: setEnabled, setLatencyMs, requestPlaying, setTempo, peers, tempo.
// аудио-поток: frame() — без аллокаций и блокировок (captureAudioSessionState у Link realtime-safe).
class LinkSync {
public:
    LinkSync();
    ~LinkSync();

    void setEnabled(bool on);
    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }
    // Ручной сдвиг: положительный — звук приложения раньше относительно сетки Link.
    void setLatencyMs(float ms) { userLatencyUs_.store(static_cast<int64_t>(ms * 1000.0f), std::memory_order_relaxed); }

    void requestPlaying(bool playing);   // старт — с ближайшей границы такта сессии
    void setTempo(double bpm);

    int peers() const { return peers_.load(std::memory_order_relaxed); }
    double tempo() const { return tempo_.load(std::memory_order_relaxed); }

    // Аудио-поток: снимок на первый кадр буфера. outputLatencyUs — от callback до динамика.
    LinkFrame frame(int64_t outputLatencyUs);

private:
    int64_t nowUs() const;

    std::unique_ptr<ableton::Link> link_;
    std::atomic<bool> enabled_{false};
    std::atomic<int> peers_{0};
    std::atomic<double> tempo_{120.0};
    std::atomic<int64_t> userLatencyUs_{0};
    std::atomic<int64_t> lastOutputLatencyUs_{20000};
};

}  // namespace mpc
