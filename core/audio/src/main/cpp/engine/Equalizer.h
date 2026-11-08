// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

#include "engine/Effect.h"

namespace selfband {

/** Biquad coefficients, normalized so that a0 = 1. */
struct BiquadCoeffs {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;

    // Formulas from Robert Bristow-Johnson's "Audio EQ Cookbook". The Kotlin twin in
    // core:model (EqMath) uses the same formulas to draw the curve; keep them in step.
    static BiquadCoeffs highPass(double fs, double hz, double q) {
        const double w = 2.0 * M_PI * hz / fs, c = std::cos(w), alpha = std::sin(w) / (2.0 * q);
        return norm((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha);
    }
    static BiquadCoeffs peaking(double fs, double hz, double q, double db) {
        const double a = std::pow(10.0, db / 40.0);
        const double w = 2.0 * M_PI * hz / fs, c = std::cos(w), alpha = std::sin(w) / (2.0 * q);
        return norm(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a);
    }
    /** Shelf slope S = 1 (the steepest without overshoot). */
    static BiquadCoeffs lowShelf(double fs, double hz, double db) {
        const double a = std::pow(10.0, db / 40.0);
        const double w = 2.0 * M_PI * hz / fs, c = std::cos(w);
        const double alpha = std::sin(w) / 2.0 * std::sqrt(2.0), k = 2.0 * std::sqrt(a) * alpha;
        return norm(a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c),
                    a * ((a + 1) - (a - 1) * c - k), (a + 1) + (a - 1) * c + k,
                    -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k);
    }
    static BiquadCoeffs highShelf(double fs, double hz, double db) {
        const double a = std::pow(10.0, db / 40.0);
        const double w = 2.0 * M_PI * hz / fs, c = std::cos(w);
        const double alpha = std::sin(w) / 2.0 * std::sqrt(2.0), k = 2.0 * std::sqrt(a) * alpha;
        return norm(a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c),
                    a * ((a + 1) + (a - 1) * c - k), (a + 1) - (a - 1) * c + k,
                    2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k);
    }

private:
    static BiquadCoeffs norm(double b0, double b1, double b2, double a0, double a1, double a2) {
        BiquadCoeffs r;
        r.b0 = static_cast<float>(b0 / a0);
        r.b1 = static_cast<float>(b1 / a0);
        r.b2 = static_cast<float>(b2 / a0);
        r.a1 = static_cast<float>(a1 / a0);
        r.a2 = static_cast<float>(a2 / a0);
        return r;
    }
};

/** Stereo biquad, transposed direct form II (behaves well when coefficients move). */
struct StereoBiquad {
    BiquadCoeffs k;
    float z1[2] = {0, 0}, z2[2] = {0, 0};

    void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0.0f; }

    inline float tick(float x, int ch) {
        const float y = k.b0 * x + z1[ch];
        z1[ch] = undenormal(k.b1 * x - k.a1 * y + z2[ch]);
        z2[ch] = undenormal(k.b2 * x - k.a2 * y);
        return y;
    }
};

/**
 * Track EQ (SPEC 2.5): high-pass (12 dB/oct), low shelf, one peaking band, high shelf, and a
 * bypass. Parameters are smoothed per block and the coefficients recomputed only when they
 * moved. Switching the high-pass or the whole EQ on/off crossfades over a few blocks.
 */
class Equalizer : public Effect {
public:
    enum Param : int32_t {
        kEnabled = 0,   // 0/1 (bypass = 0)
        kHighPassOn,    // 0/1
        kHighPassHz,
        kLowShelfHz,
        kLowShelfDb,
        kPeakHz,
        kPeakDb,
        kPeakQ,
        kHighShelfHz,
        kHighShelfDb,
        kParamCount
    };

    Equalizer() {
        const float defaults[kParamCount] = {1, 0, 80, 120, 0, 1000, 0, 1, 8000, 0};
        for (int i = 0; i < kParamCount; ++i) {
            mTarget[i].store(defaults[i]);
            mCurrent[i] = defaults[i];
        }
    }

    void prepare(int32_t sampleRate, int32_t /*maxFrames*/) override {
        mSampleRate = std::max(sampleRate, 8000);
        for (int i = 0; i < kParamCount; ++i) mCurrent[i] = mTarget[i].load();
        mEnabledMix = mCurrent[kEnabled];
        mHighPassMix = mCurrent[kHighPassOn];
        updateCoefficients();
        reset();
    }

    void reset() override {
        for (auto &b : mBands) b.reset();
    }

    void setParam(int32_t id, float value) override {
        if (id >= 0 && id < kParamCount) mTarget[id].store(value, std::memory_order_relaxed);
    }

    void process(float *lr, int32_t frames) override {
        if (frames <= 0) return;
        const float a = blockSmoothing(frames, mSampleRate, 0.03f);
        bool moved = false;
        for (int i = kHighPassHz; i < kParamCount; ++i) {
            const float t = mTarget[i].load(std::memory_order_relaxed);
            if (mCurrent[i] != t) {
                float next = mCurrent[i] + (t - mCurrent[i]) * a;
                if (std::fabs(t - next) <= 1e-3f * std::max(1.0f, std::fabs(t))) next = t;
                mCurrent[i] = next;
                moved = true;
            }
        }
        if (moved) updateCoefficients();

        // On/off switches crossfade linearly over ~20 ms.
        const float fade = std::min(1.0f, static_cast<float>(frames) / (0.02f * static_cast<float>(mSampleRate)));
        const float e0 = mEnabledMix;
        const float e1 = step(e0, mTarget[kEnabled].load(std::memory_order_relaxed) > 0.5f ? 1.0f : 0.0f, fade);
        const float h0 = mHighPassMix;
        const float h1 = step(h0, mTarget[kHighPassOn].load(std::memory_order_relaxed) > 0.5f ? 1.0f : 0.0f, fade);
        mEnabledMix = e1;
        mHighPassMix = h1;
        if (e0 == 0.0f && e1 == 0.0f) {
            reset(); // fully bypassed: start clean when switched back on
            return;
        }
        const bool hpActive = h0 > 0.0f || h1 > 0.0f;
        if (!hpActive) mBands[0].reset();
        const float inv = 1.0f / static_cast<float>(frames);
        for (int32_t f = 0; f < frames; ++f) {
            const float t = static_cast<float>(f + 1) * inv;
            const float em = e0 + (e1 - e0) * t;
            const float hm = h0 + (h1 - h0) * t;
            for (int ch = 0; ch < 2; ++ch) {
                const float dry = lr[2 * f + ch];
                float x = dry;
                if (hpActive) x += (mBands[0].tick(x, ch) - x) * hm;
                x = mBands[1].tick(x, ch);
                x = mBands[2].tick(x, ch);
                x = mBands[3].tick(x, ch);
                lr[2 * f + ch] = dry + (x - dry) * em;
            }
        }
    }

private:
    static float step(float from, float to, float amount) {
        if (from < to) return std::min(to, from + amount);
        if (from > to) return std::max(to, from - amount);
        return to;
    }

    void updateCoefficients() {
        const double fs = mSampleRate;
        const double maxHz = 0.45 * fs;
        auto hz = [&](int id, double lo, double hi) {
            return std::clamp(static_cast<double>(mCurrent[id]), lo, std::min(hi, maxHz));
        };
        auto db = [&](int id) { return std::clamp(static_cast<double>(mCurrent[id]), -24.0, 24.0); };
        mBands[0].k = BiquadCoeffs::highPass(fs, hz(kHighPassHz, 10, 1000), M_SQRT1_2);
        mBands[1].k = BiquadCoeffs::lowShelf(fs, hz(kLowShelfHz, 20, 2000), db(kLowShelfDb));
        mBands[2].k = BiquadCoeffs::peaking(fs, hz(kPeakHz, 20, 20000),
                                            std::clamp(static_cast<double>(mCurrent[kPeakQ]), 0.1, 20.0),
                                            db(kPeakDb));
        mBands[3].k = BiquadCoeffs::highShelf(fs, hz(kHighShelfHz, 500, 20000), db(kHighShelfDb));
    }

    int32_t mSampleRate = 48000;
    std::atomic<float> mTarget[kParamCount];
    float mCurrent[kParamCount];        // audio thread only (after prepare)
    float mEnabledMix = 1.0f;           // audio thread only
    float mHighPassMix = 0.0f;          // audio thread only
    StereoBiquad mBands[4];             // high-pass, low shelf, peak, high shelf
};

} // namespace selfband
