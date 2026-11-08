// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

namespace selfband {

/**
 * Layout of the per-stream stats block copied to Kotlin. Must match
 * NativeAudioProbe.kt (StatField). Every value is a double.
 */
enum StatField : int32_t {
    kStatOpen = 0,           // 1 if the stream is open
    kStatApi,                // oboe::AudioApi
    kStatSampleRate,
    kStatChannelCount,
    kStatFormat,             // oboe::AudioFormat
    kStatFramesPerBurst,
    kStatBufferSize,         // frames
    kStatBufferCapacity,     // frames
    kStatSharingMode,        // oboe::SharingMode
    kStatPerformanceMode,    // oboe::PerformanceMode
    kStatMMap,               // 1 = MMAP, 0 = legacy path, -1 = unknown
    kStatXRuns,              // -1 = not supported by this API
    kStatLatencyMs,          // -1 = unavailable
    kStatDeviceId,
    kStatCallbacks,
    kStatFrames,
    kStatPeak,               // input only: decaying peak, linear 0..1+
    kStatDisconnected,       // 1 if the stream was closed by the system (e.g. device unplugged)
    kStatCount
};

/**
 * Phase 0 diagnostics: opens an output stream (playing silence) and optionally an
 * input stream, and reports what the device actually granted.
 *
 * Threading: start/stop/fillStats are called from control threads and serialized by
 * mControlLock. The audio callbacks touch only atomics and the output buffer: no
 * allocation, no locks, no logging, no I/O.
 */
class AudioProbe {
public:
    struct Config {
        bool withInput = false;
        int32_t outputDeviceId = oboe::kUnspecified;
        int32_t inputDeviceId = oboe::kUnspecified;
        oboe::InputPreset inputPreset = oboe::InputPreset::VoiceRecognition;
        int32_t inputChannelCount = 1;
    };

    AudioProbe() = default;
    ~AudioProbe();

    oboe::Result start(const Config &config);
    void stop();

    /** Writes 2 * kStatCount values: output block, then input block. */
    void fillStats(double *out, int32_t capacity);

private:
    // One callback object per direction, so each knows which counters to update.
    class StreamCallback : public oboe::AudioStreamDataCallback,
                           public oboe::AudioStreamErrorCallback {
    public:
        explicit StreamCallback(bool isInput) : mIsInput(isInput) {}

        oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                              int32_t numFrames) override;
        void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

        void reset();

        const bool mIsInput;
        std::atomic<int64_t> callbacks{0};
        std::atomic<int64_t> frames{0};
        std::atomic<float> peak{0.0f};
        std::atomic<bool> disconnected{false};
    };

    oboe::Result openOutput(const Config &config);
    oboe::Result openInput(const Config &config);
    void closeAllLocked();
    static void fillStreamStats(oboe::AudioStream *stream, const StreamCallback &cb, double *out);

    std::mutex mControlLock;
    std::shared_ptr<oboe::AudioStream> mOutput;
    std::shared_ptr<oboe::AudioStream> mInput;
    StreamCallback mOutputCallback{false};
    StreamCallback mInputCallback{true};
};

} // namespace selfband
