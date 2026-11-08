// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>

namespace selfband {

/**
 * Master clipping protection (SPEC 2.5): a stereo-linked peak limiter with no lookahead, so it
 * adds no delay to the output (the playback timing and the latency calibration stay valid).
 * The gain drops instantly to whatever keeps the louder channel at the ceiling, then recovers
 * smoothly. It never touches signals below the ceiling. No allocation; safe on the audio thread.
 * The same class will run in the offline export render, so preview and export match.
 */
class Limiter {
public:
    /** Peak ceiling: -1 dBFS. */
    static constexpr float kCeiling = 0.891251f;

    void prepare(int32_t sampleRate) {
        const double releaseSeconds = 0.08;
        mRelease = static_cast<float>(std::exp(-1.0 / (releaseSeconds * std::max(sampleRate, 8000))));
        mGain = 1.0f;
    }

    void reset() { mGain = 1.0f; }

    /** In place on interleaved stereo. Returns the smallest gain applied in this block. */
    float process(float *lr, int32_t frames) {
        float minGain = 1.0f;
        float gain = mGain;
        for (int32_t f = 0; f < frames; ++f) {
            const float peak = std::max(std::fabs(lr[2 * f]), std::fabs(lr[2 * f + 1]));
            const float target = peak > kCeiling ? kCeiling / peak : 1.0f;
            gain = target < gain ? target : target + (gain - target) * mRelease;
            lr[2 * f] *= gain;
            lr[2 * f + 1] *= gain;
            minGain = std::min(minGain, gain);
        }
        mGain = gain;
        return minGain;
    }

private:
    float mRelease = 0.999f;
    float mGain = 1.0f;
};

} // namespace selfband
