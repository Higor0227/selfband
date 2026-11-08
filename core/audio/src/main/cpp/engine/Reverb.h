// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>

#include "engine/Effect.h"

namespace selfband {

/**
 * The single send reverb on the master bus (SPEC 2.5). A Schroeder-Moorer design in the
 * style of Freeverb (public domain, by Jezar at Dreampoint), written for this project:
 * pre-delay, 8 damped feedback combs in parallel and 4 allpasses in series per channel, the
 * right channel slightly detuned for width.
 *
 * - Size scales every delay line (0 = small, 1 = large). Buffers are allocated for the
 *   largest size in prepare(), so moving it never allocates.
 * - Decay is the RT60 in seconds: each comb's feedback is set so that it falls 60 dB in that
 *   time whatever its length, which keeps decay and size independent.
 * - Damping is the high-frequency loss in the feedback path (0 = bright, 1 = dark).
 * - Return is the wet level that reaches the master.
 *
 * process() takes the summed sends (stereo) and replaces them with the wet signal.
 */
class Reverb : public Effect {
public:
    enum Param : int32_t {
        kSize = 0,      // 0..1
        kDecaySeconds,  // RT60
        kPreDelayMs,
        kDamping,       // 0..1
        kReturnGain,    // linear
        kParamCount
    };

    static constexpr float kMaxPreDelayMs = 250.0f;

    Reverb() {
        const float defaults[kParamCount] = {0.5f, 1.2f, 12.0f, 0.5f, 1.0f};
        for (int i = 0; i < kParamCount; ++i) {
            mTarget[i].store(defaults[i]);
            mCurrent[i] = defaults[i];
        }
    }

    void prepare(int32_t sampleRate, int32_t /*maxFrames*/) override {
        mSampleRate = std::max(sampleRate, 8000);
        const double scale = mSampleRate / 44100.0 * kMaxSizeFactor;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < kCombs; ++i) {
                mComb[ch][i].allocate(static_cast<int32_t>((kCombTuning[i] + ch * kSpread) * scale) + 2);
            }
            for (int i = 0; i < kAllpasses; ++i) {
                mAllpass[ch][i].allocate(static_cast<int32_t>((kAllpassTuning[i] + ch * kSpread) * scale) + 2);
            }
        }
        mPreDelaySize = static_cast<int32_t>(kMaxPreDelayMs / 1000.0f * mSampleRate) + 2;
        mPreDelay.reset(new float[mPreDelaySize]);
        for (int i = 0; i < kParamCount; ++i) mCurrent[i] = mTarget[i].load();
        mReturn = mCurrent[kReturnGain];
        applySize(true);
        applyDecayAndDamping();
        reset();
    }

    void reset() override {
        for (int ch = 0; ch < 2; ++ch) {
            for (auto &c : mComb[ch]) c.clear();
            for (auto &a : mAllpass[ch]) a.clear();
        }
        if (mPreDelay) std::fill(mPreDelay.get(), mPreDelay.get() + mPreDelaySize, 0.0f);
        mPreDelayPos = 0;
    }

    void setParam(int32_t id, float value) override {
        if (id >= 0 && id < kParamCount) mTarget[id].store(value, std::memory_order_relaxed);
    }

    void process(float *lr, int32_t frames) override {
        if (frames <= 0 || !mPreDelay) return;
        const float a = blockSmoothing(frames, mSampleRate, 0.05f);
        bool decayMoved = false;
        for (int i = kDecaySeconds; i <= kDamping; ++i) {
            const float t = mTarget[i].load(std::memory_order_relaxed);
            if (mCurrent[i] != t) {
                float next = mCurrent[i] + (t - mCurrent[i]) * a;
                if (std::fabs(t - next) <= 1e-3f * std::max(1.0f, std::fabs(t))) next = t;
                mCurrent[i] = next;
                if (i != kPreDelayMs) decayMoved = true;
            }
        }
        const float size = mTarget[kSize].load(std::memory_order_relaxed);
        if (size != mCurrent[kSize]) {
            // Delay lengths jump (no smoothing): smoothing them would pitch-bend the tail.
            mCurrent[kSize] = size;
            applySize(false);
            decayMoved = true;
        }
        if (decayMoved) applyDecayAndDamping();

        const int32_t preDelay = std::clamp(
                static_cast<int32_t>(mCurrent[kPreDelayMs] / 1000.0f * static_cast<float>(mSampleRate)),
                0, mPreDelaySize - 1);
        const float r0 = mReturn;
        const float r1 = std::max(0.0f, mTarget[kReturnGain].load(std::memory_order_relaxed));
        const float rStep = (r1 - r0) / static_cast<float>(frames);
        float *pd = mPreDelay.get();
        for (int32_t f = 0; f < frames; ++f) {
            const float in = (lr[2 * f] + lr[2 * f + 1]) * kInputGain;
            pd[mPreDelayPos] = in;
            int32_t readPos = mPreDelayPos - preDelay;
            if (readPos < 0) readPos += mPreDelaySize;
            const float x = pd[readPos];
            if (++mPreDelayPos >= mPreDelaySize) mPreDelayPos = 0;

            float out[2];
            for (int ch = 0; ch < 2; ++ch) {
                float acc = 0.0f;
                for (auto &c : mComb[ch]) acc += c.tick(x);
                for (auto &ap : mAllpass[ch]) acc = ap.tick(acc);
                out[ch] = acc;
            }
            const float g = r0 + rStep * static_cast<float>(f + 1);
            lr[2 * f] = out[0] * g;
            lr[2 * f + 1] = out[1] * g;
        }
        mReturn = r1;
    }

private:
    static constexpr int kCombs = 8;
    static constexpr int kAllpasses = 4;
    // Freeverb's tunings at 44.1 kHz, in samples.
    static constexpr int32_t kCombTuning[kCombs] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    static constexpr int32_t kAllpassTuning[kAllpasses] = {556, 441, 341, 225};
    static constexpr int32_t kSpread = 23;
    static constexpr double kMinSizeFactor = 0.35;
    static constexpr double kMaxSizeFactor = 1.35;
    static constexpr float kInputGain = 0.015f;

    struct Comb {
        std::unique_ptr<float[]> buf;
        int32_t capacity = 0, length = 1, pos = 0;
        float feedback = 0.0f, damp = 0.0f, store = 0.0f;

        void allocate(int32_t n) {
            capacity = std::max(n, 2);
            buf.reset(new float[capacity]);
            clear();
        }
        void clear() {
            if (buf) std::fill(buf.get(), buf.get() + capacity, 0.0f);
            store = 0.0f;
            pos = 0;
        }
        inline float tick(float x) {
            const float y = buf[pos];
            store = undenormal(y * (1.0f - damp) + store * damp);
            buf[pos] = x + store * feedback;
            if (++pos >= length) pos = 0;
            return y;
        }
    };

    struct Allpass {
        std::unique_ptr<float[]> buf;
        int32_t capacity = 0, length = 1, pos = 0;

        void allocate(int32_t n) {
            capacity = std::max(n, 2);
            buf.reset(new float[capacity]);
            clear();
        }
        void clear() {
            if (buf) std::fill(buf.get(), buf.get() + capacity, 0.0f);
            pos = 0;
        }
        inline float tick(float x) {
            const float b = buf[pos];
            buf[pos] = undenormal(x + b * 0.5f);
            if (++pos >= length) pos = 0;
            return b - x;
        }
    };

    void applySize(bool initial) {
        const double factor = kMinSizeFactor +
                              (kMaxSizeFactor - kMinSizeFactor) * std::clamp(static_cast<double>(mCurrent[kSize]), 0.0, 1.0);
        const double scale = mSampleRate / 44100.0 * factor;
        for (int ch = 0; ch < 2; ++ch) {
            for (int i = 0; i < kCombs; ++i) {
                Comb &c = mComb[ch][i];
                c.length = std::clamp(static_cast<int32_t>((kCombTuning[i] + ch * kSpread) * scale), 1, c.capacity);
                if (initial || c.pos >= c.length) c.pos = 0;
            }
            for (int i = 0; i < kAllpasses; ++i) {
                Allpass &ap = mAllpass[ch][i];
                ap.length = std::clamp(static_cast<int32_t>((kAllpassTuning[i] + ch * kSpread) * scale), 1, ap.capacity);
                if (initial || ap.pos >= ap.length) ap.pos = 0;
            }
        }
    }

    void applyDecayAndDamping() {
        const double rt60 = std::clamp(static_cast<double>(mCurrent[kDecaySeconds]), 0.1, 20.0);
        const float damp = 0.4f * std::clamp(mCurrent[kDamping], 0.0f, 1.0f);
        for (auto &channel : mComb) {
            for (Comb &c : channel) {
                // -60 dB after rt60 seconds: g^(rt60 * fs / length) = 10^-3.
                c.feedback = static_cast<float>(std::pow(10.0, -3.0 * c.length / (rt60 * mSampleRate)));
                c.damp = damp;
            }
        }
    }

    int32_t mSampleRate = 48000;
    std::atomic<float> mTarget[kParamCount];
    float mCurrent[kParamCount];        // audio thread only (after prepare)
    float mReturn = 1.0f;               // audio thread only
    Comb mComb[2][kCombs];
    Allpass mAllpass[2][kAllpasses];
    std::unique_ptr<float[]> mPreDelay;
    int32_t mPreDelaySize = 0;
    int32_t mPreDelayPos = 0;
};

} // namespace selfband
