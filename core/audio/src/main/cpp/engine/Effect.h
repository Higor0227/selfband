// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cmath>
#include <cstdint>

namespace selfband {

/**
 * Common shape of the mixer effects (SPEC 3.3). Buffers are interleaved stereo, processed in
 * place.
 *
 * - prepare(): control thread, while the audio thread is not using the effect. May allocate.
 * - process() and reset(): audio thread only. No allocation, locks, logging or I/O.
 * - setParam(): any thread. Values are atomics that the audio thread smooths, so a change
 *   never clicks.
 *
 * The same objects will run in the offline export render, so preview and export match.
 */
class Effect {
public:
    virtual ~Effect() = default;
    virtual void prepare(int32_t sampleRate, int32_t maxFrames) = 0;
    virtual void reset() = 0;
    virtual void process(float *lr, int32_t frames) = 0;
    virtual void setParam(int32_t id, float value) = 0;
};

/** Block-rate one-pole smoothing coefficient for a time constant of tauSeconds. */
inline float blockSmoothing(int32_t frames, int32_t sampleRate, float tauSeconds) {
    return 1.0f - std::exp(-static_cast<float>(frames) / (tauSeconds * static_cast<float>(sampleRate)));
}

/** Flushes values that would decay into denormals (slow on some CPUs) to zero. */
inline float undenormal(float x) { return std::fabs(x) < 1e-15f ? 0.0f : x; }

} // namespace selfband
