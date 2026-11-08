// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>

#include "engine/Equalizer.h"
#include "engine/Limiter.h"
#include "engine/Reverb.h"

namespace selfband {

/**
 * The mix graph shared by live playback (AudioEngine) and the offline export render
 * (OfflineMixer), so the exported mix is the one that was heard (SPEC 3.3):
 *
 *   track → EQ → gain/pan/phase (ramped) → master
 *                          └→ send (post-fader, ramped) → reverb → master
 *   master → volume (ramped) → limiter → out
 *
 * All processing is allocation-free. Parameters are atomics, so the control thread can change
 * them while the audio thread mixes.
 */

struct TrackConfig {
    std::string path;
    int64_t startFrame = 0; // project frame of the take's first sample
    float gainL = 1.0f;     // volume, pan, mute, solo and phase already folded in
    float gainR = 1.0f;
    float eq[Equalizer::kParamCount] = {1, 0, 80, 120, 0, 1000, 0, 1, 8000, 0};
    float send = 0.0f;      // reverb send (linear), taken after the track gains
};

/** Per-track processing: EQ, gains and the reverb send. */
struct TrackMix {
    std::atomic<float> targetL{1.0f};
    std::atomic<float> targetR{1.0f};
    float currentL = 1.0f;      // mixing thread only
    float currentR = 1.0f;      // mixing thread only
    Equalizer eq;
    std::atomic<float> sendTarget{0.0f};
    float sendCurrent = 0.0f;   // mixing thread only

    /** Control thread, before mixing starts. */
    void configure(const TrackConfig &cfg, int32_t sampleRate, int32_t maxFrames) {
        targetL.store(cfg.gainL);
        targetR.store(cfg.gainR);
        currentL = cfg.gainL;
        currentR = cfg.gainR;
        for (int32_t p = 0; p < Equalizer::kParamCount; ++p) eq.setParam(p, cfg.eq[p]);
        eq.prepare(sampleRate, maxFrames);
        sendTarget.store(std::max(0.0f, cfg.send));
        sendCurrent = std::max(0.0f, cfg.send);
    }

    void reset() { eq.reset(); }

    /**
     * Mixes one block of this track (stereo, [frames]; the EQ works on it in place) into
     * [master] and [reverbIn]. Returns the post-gain peak, or -1 if the track is silent and
     * was skipped.
     */
    float mix(float *track, int32_t frames, float *master, float *reverbIn) {
        const float l0 = currentL;
        const float r0 = currentR;
        const float l1 = targetL.load(std::memory_order_relaxed);
        const float r1 = targetR.load(std::memory_order_relaxed);
        if (l0 == 0.0f && r0 == 0.0f && l1 == 0.0f && r1 == 0.0f) return -1.0f;
        eq.process(track, frames); // pre-fader
        const float inv = 1.0f / static_cast<float>(frames);
        const float stepL = (l1 - l0) * inv;
        const float stepR = (r1 - r0) * inv;
        const float s0 = sendCurrent;
        const float s1 = sendTarget.load(std::memory_order_relaxed);
        const bool sending = s0 != 0.0f || s1 != 0.0f;
        const float stepS = (s1 - s0) * inv;
        float peak = 0.0f;
        for (int32_t f = 0; f < frames; ++f) {
            const float k = static_cast<float>(f + 1); // ramp: no zipper clicks
            const float l = track[2 * f] * (l0 + stepL * k);
            const float r = track[2 * f + 1] * (r0 + stepR * k);
            peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
            master[2 * f] += l;
            master[2 * f + 1] += r;
            if (sending) {
                const float s = s0 + stepS * k;
                reverbIn[2 * f] += l * s;
                reverbIn[2 * f + 1] += r * s;
            }
        }
        currentL = l1;
        currentR = r1;
        sendCurrent = s1;
        return peak;
    }
};

/** Reverb return, master volume and the limiter. */
class MasterBus {
public:
    std::atomic<float> gainTarget{1.0f};
    std::atomic<bool> limiterEnabled{true};

    /** Control thread, before mixing starts. May allocate. */
    void prepare(int32_t sampleRate, int32_t maxFrames) {
        mLimiter.prepare(sampleRate);
        mReverb.prepare(sampleRate, maxFrames);
        mGain = gainTarget.load();
    }

    void reset() {
        mReverb.reset();
        mLimiter.reset();
    }

    void setReverbParam(int32_t id, float value) { mReverb.setParam(id, value); }

    /**
     * Turns the summed sends in [reverbIn] into the wet signal, adds it to [master], then
     * applies the master volume and the limiter in place. Returns the smallest limiter gain.
     */
    float process(float *master, float *reverbIn, int32_t frames) {
        mReverb.process(reverbIn, frames);
        for (int32_t i = 0, n = frames * 2; i < n; ++i) master[i] += reverbIn[i];
        const float g0 = mGain;
        const float g1 = gainTarget.load(std::memory_order_relaxed);
        if (g0 != 1.0f || g1 != 1.0f) {
            const float step = (g1 - g0) / static_cast<float>(frames);
            for (int32_t f = 0; f < frames; ++f) {
                const float g = g0 + step * static_cast<float>(f + 1);
                master[2 * f] *= g;
                master[2 * f + 1] *= g;
            }
        }
        mGain = g1;
        if (limiterEnabled.load(std::memory_order_relaxed)) return mLimiter.process(master, frames);
        mLimiter.reset();
        return 1.0f;
    }

private:
    float mGain = 1.0f; // mixing thread only
    Limiter mLimiter;
    Reverb mReverb;
};

/**
 * Reads [frames] frames of a take placed at project frame [takeStart], starting at project
 * frame [position], as stereo into [stereo] (mono takes are duplicated; silence outside the
 * take). [fileBuf] must hold frames * reader channels floats.
 */
template <typename Reader>
inline void readTakeStereo(Reader &reader, int64_t takeStart, int64_t position, int32_t frames,
                           float *fileBuf, float *stereo) {
    std::memset(stereo, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    const int32_t ch = reader.channels();
    const int64_t k0 = position - takeStart; // take frame for the first output frame
    const int64_t kStart = std::max<int64_t>(k0, 0);
    const int64_t kEnd = std::min<int64_t>(k0 + frames, reader.frames());
    if (kStart >= kEnd) return;
    const int64_t got = reader.read(kStart, fileBuf, kEnd - kStart);
    const int64_t dst = kStart - k0;
    for (int64_t i = 0; i < got; ++i) {
        const float l = fileBuf[i * ch];
        const float r = ch > 1 ? fileBuf[i * ch + 1] : l;
        stereo[(dst + i) * 2] = l;
        stereo[(dst + i) * 2 + 1] = r;
    }
}

} // namespace selfband
