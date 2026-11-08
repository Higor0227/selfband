// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine/AudioEngine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <ctime>

#include <oboe/OboeExtensions.h>

#include "common/Log.h"

namespace selfband {

namespace {
constexpr float kPeakDecay = 0.95f;
constexpr int32_t kInputScratchFrames = 8192;   // max input frames drained per callback
constexpr int32_t kReaderChunkFrames = 1024;
constexpr double kTrackRingSeconds = 2.0;
constexpr double kRecordRingSeconds = 4.0;
constexpr auto kStopTimeout = std::chrono::milliseconds(300);

void sleepMs(int ms) { std::this_thread::sleep_for(std::chrono::milliseconds(ms)); }
} // namespace

AudioEngine::~AudioEngine() {
    std::lock_guard<std::mutex> lock(mControlLock);
    closeLocked();
}

// ===========================================================================
// Audio thread
// ===========================================================================

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *stream, void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    const int32_t outChannels = stream->getChannelCount();

    // 1) Drain everything the input has, so input latency stays minimal and constant.
    //    Oboe's non-blocking read is the documented full-duplex pattern.
    int64_t inStart = 0;
    int32_t inCount = 0;
    oboe::AudioStream *input =
            mInDisconnected.load(std::memory_order_relaxed) ? nullptr : mInput.get();
    if (input != nullptr) {
        inStart = input->getFramesRead();
        auto result = input->read(mInScratch.get(), mInScratchFrames, 0);
        if (result) {
            inCount = result.value();
        } else if (result.error() == oboe::Result::ErrorDisconnected) {
            mInDisconnected.store(true, std::memory_order_relaxed);
        }
        // Digital input gain, ramped over this block.
        const float g0 = mInputGain;
        const float g1 = mInputGainTarget.load(std::memory_order_relaxed);
        if (inCount > 0 && (g0 != 1.0f || g1 != 1.0f)) {
            float *buf = mInScratch.get();
            const float step = (g1 - g0) / static_cast<float>(inCount);
            for (int32_t i = 0; i < inCount; ++i) {
                const float g = g0 + step * static_cast<float>(i + 1);
                for (int32_t c = 0; c < mInputChannels; ++c) buf[i * mInputChannels + c] *= g;
            }
        }
        if (inCount > 0) mInputGain = g1;
        // Metering.
        float maxL = 0.0f, maxR = 0.0f;
        const float *in = mInScratch.get();
        if (mInputChannels == 1) {
            for (int32_t i = 0; i < inCount; ++i) maxL = std::max(maxL, std::fabs(in[i]));
            maxR = maxL;
        } else {
            for (int32_t i = 0; i < inCount; ++i) {
                maxL = std::max(maxL, std::fabs(in[i * mInputChannels]));
                maxR = std::max(maxR, std::fabs(in[i * mInputChannels + 1]));
            }
        }
        const float hl = mPeakL.load(std::memory_order_relaxed) * kPeakDecay;
        const float hr = mPeakR.load(std::memory_order_relaxed) * kPeakDecay;
        mPeakL.store(std::max(maxL, hl), std::memory_order_relaxed);
        mPeakR.store(std::max(maxR, hr), std::memory_order_relaxed);
    }

    std::memset(out, 0, static_cast<size_t>(numFrames) * outChannels * sizeof(float));

    // Software monitoring: the input goes back to the headphones, also while idle. It is
    // never recorded (the take comes from the input) and never exported.
    if (input != nullptr) mixMonitor(out, numFrames, outChannels, inCount);

    // 2) Transport transitions requested by the control thread.
    const int32_t requested = mRequested.load(std::memory_order_acquire);
    if (requested != mCurrent) {
        if (requested != kIdle) {
            mPosition = mStartPosition.load(std::memory_order_relaxed);
            mRecOutStart.store(stream->getFramesWritten(), std::memory_order_relaxed);
            mRecInStart.store(inStart, std::memory_order_relaxed);
        }
        mCurrent = requested;
        mCurrentPublic.store(requested, std::memory_order_release);
    }
    if (mCurrent == kIdle) return oboe::DataCallbackResult::Continue;

    // 3) Mix the tracks into the master bus (rings are already aligned to the project timeline).
    const int32_t trackCount = mTrackCount.load(std::memory_order_acquire);
    const int32_t frames = std::min(numFrames, mTrackScratchFrames);
    float *scratch = mTrackScratch.get();
    float *master = mMasterScratch.get();
    std::memset(master, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    for (int32_t t = 0; t < trackCount; ++t) {
        TrackStream &track = *mTracks[t];
        const size_t want = static_cast<size_t>(frames) * 2;
        const size_t got = track.ring.pop(scratch, want);
        if (got < want) {
            std::memset(scratch + got, 0, (want - got) * sizeof(float));
            mUnderruns.fetch_add(1, std::memory_order_relaxed);
        }
        const float l0 = track.currentL;
        const float r0 = track.currentR;
        const float l1 = track.targetL.load(std::memory_order_relaxed);
        const float r1 = track.targetR.load(std::memory_order_relaxed);
        const float heldPeak = track.peak.load(std::memory_order_relaxed) * kPeakDecay;
        if (l0 == 0.0f && r0 == 0.0f && l1 == 0.0f && r1 == 0.0f) {
            track.peak.store(heldPeak, std::memory_order_relaxed);
            continue;
        }
        const float stepL = (l1 - l0) / static_cast<float>(frames);
        const float stepR = (r1 - r0) / static_cast<float>(frames);
        float peak = 0.0f;
        for (int32_t f = 0; f < frames; ++f) {
            const float k = static_cast<float>(f + 1); // ramp: no zipper clicks
            const float l = scratch[2 * f] * (l0 + stepL * k);
            const float r = scratch[2 * f + 1] * (r0 + stepR * k);
            peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
            master[2 * f] += l;
            master[2 * f + 1] += r;
        }
        track.currentL = l1;
        track.currentR = r1;
        track.peak.store(std::max(peak, heldPeak), std::memory_order_relaxed);
    }

    // Master: volume (ramped), clipping protection, meters, then out to the device.
    {
        const float g0 = mMasterGain;
        const float g1 = mMasterGainTarget.load(std::memory_order_relaxed);
        if (g0 != 1.0f || g1 != 1.0f) {
            const float step = (g1 - g0) / static_cast<float>(frames);
            for (int32_t f = 0; f < frames; ++f) {
                const float g = g0 + step * static_cast<float>(f + 1);
                master[2 * f] *= g;
                master[2 * f + 1] *= g;
            }
        }
        mMasterGain = g1;
        float minGain = 1.0f;
        if (mLimiterEnabled.load(std::memory_order_relaxed)) {
            minGain = mLimiter.process(master, frames);
        } else {
            mLimiter.reset();
        }
        float maxL = 0.0f, maxR = 0.0f;
        for (int32_t f = 0; f < frames; ++f) {
            const float l = master[2 * f];
            const float r = master[2 * f + 1];
            maxL = std::max(maxL, std::fabs(l));
            maxR = std::max(maxR, std::fabs(r));
            if (outChannels >= 2) {
                out[f * outChannels] += l;
                out[f * outChannels + 1] += r;
            } else {
                out[f] += 0.5f * (l + r);
            }
        }
        mMasterPeakL.store(std::max(maxL, mMasterPeakL.load(std::memory_order_relaxed) * kPeakDecay),
                           std::memory_order_relaxed);
        mMasterPeakR.store(std::max(maxR, mMasterPeakR.load(std::memory_order_relaxed) * kPeakDecay),
                           std::memory_order_relaxed);
        // Gain reduction display: drops at once, recovers like a peak meter.
        const float heldGain = 1.0f - (1.0f - mLimiterGain.load(std::memory_order_relaxed)) * kPeakDecay;
        mLimiterGain.store(std::min(minGain, heldGain), std::memory_order_relaxed);
    }

    // The click goes to the headphones only: it is mixed after the tracks and never reaches
    // the recording (which comes from the input) or the export.
    mixMetronome(out, numFrames, outChannels, mPosition);

    // Never hand the device values past full scale (monitoring + playback + click can add up).
    for (int32_t i = 0, n = numFrames * outChannels; i < n; ++i) out[i] = std::clamp(out[i], -1.0f, 1.0f);

    mPosition += numFrames;
    mPositionPublic.store(mPosition, std::memory_order_relaxed);

    // 4) Recording: selected input channels -> ring -> disk writer thread.
    if (mCurrent == kRecording && inCount > 0) {
        const float *in = mInScratch.get();
        float *rec = mRecScratch.get();
        size_t samples = 0;
        if (mRecChannels == 2) {
            for (int32_t i = 0; i < inCount; ++i) {
                rec[samples++] = in[i * mInputChannels];
                rec[samples++] = in[i * mInputChannels + 1];
            }
        } else {
            const int32_t ch = (mRecChannelMode == kRecSecond && mInputChannels > 1) ? 1 : 0;
            for (int32_t i = 0; i < inCount; ++i) rec[samples++] = in[i * mInputChannels + ch];
        }
        const size_t pushed = mRecRing.push(rec, samples);
        if (pushed < samples) {
            mRecDropped.fetch_add(static_cast<int64_t>((samples - pushed) / mRecChannels),
                                  std::memory_order_relaxed);
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::mixMonitor(float *out, int32_t numFrames, int32_t outChannels, int32_t inCount) {
    const float g0 = mMonitorGain;
    const float g1 = mMonitorGainTarget.load(std::memory_order_relaxed);
    float *fifoOut = mMonitorScratch.get();
    const size_t maxSamples = static_cast<size_t>(mMonitorScratchFrames) * 2;
    if (g0 == 0.0f && g1 == 0.0f) {
        // Keep the FIFO empty so turning monitoring on never plays stale audio.
        while (mMonitorRing.availableToRead() > 0) mMonitorRing.pop(fifoOut, maxSamples);
        return;
    }
    // Input -> stereo -> FIFO (input and output blocks may differ in size).
    const int32_t mode = mMonitorChannel.load(std::memory_order_relaxed);
    const float *in = mInScratch.get();
    float *st = mRecScratch.get(); // free until the recording step later in this callback
    const int32_t ch = mInputChannels;
    const int32_t sel = (mode == kRecSecond && ch > 1) ? 1 : 0;
    for (int32_t i = 0; i < inCount; ++i) {
        if (mode == kRecStereo && ch > 1) {
            st[2 * i] = in[i * ch];
            st[2 * i + 1] = in[i * ch + 1];
        } else {
            st[2 * i] = st[2 * i + 1] = in[i * ch + sel];
        }
    }
    mMonitorRing.push(st, static_cast<size_t>(inCount) * 2);
    // Bound the FIFO to about two blocks so monitoring latency stays low.
    const int32_t frames = std::min(numFrames, mMonitorScratchFrames);
    const size_t want = static_cast<size_t>(frames) * 2;
    const size_t keep = want * 2;
    size_t avail = mMonitorRing.availableToRead();
    while (avail > keep + want) {
        mMonitorRing.pop(fifoOut, std::min(avail - keep, maxSamples));
        avail = mMonitorRing.availableToRead();
    }
    const size_t got = mMonitorRing.pop(fifoOut, want);
    if (got < want) std::memset(fifoOut + got, 0, (want - got) * sizeof(float));
    const float step = (g1 - g0) / static_cast<float>(frames);
    for (int32_t f = 0; f < frames; ++f) {
        const float g = g0 + step * static_cast<float>(f + 1);
        if (outChannels >= 2) {
            out[f * outChannels] += fifoOut[2 * f] * g;
            out[f * outChannels + 1] += fifoOut[2 * f + 1] * g;
        } else {
            out[f] += 0.5f * (fifoOut[2 * f] + fifoOut[2 * f + 1]) * g;
        }
    }
    mMonitorGain = g1;
}

void AudioEngine::mixMetronome(float *out, int32_t numFrames, int32_t outChannels,
                               int64_t position) {
    const bool songClicks = mMetroEnabled.load(std::memory_order_relaxed);
    const int64_t countInStart = mCountInStart.load(std::memory_order_relaxed);
    if (!songClicks && countInStart >= 0) return;
    const double fpb = mFramesPerBeat.load(std::memory_order_relaxed);
    if (fpb < 1.0) return;
    const int32_t sound = std::clamp(mMetroSound.load(std::memory_order_relaxed), 0, kClickSounds - 1);
    const int32_t beatsPerBar = std::max(1, mBeatsPerBar.load(std::memory_order_relaxed));
    const bool accentOn = mMetroAccent.load(std::memory_order_relaxed);
    const float volume = mMetroVolume.load(std::memory_order_relaxed);
    const int64_t maxLen = static_cast<int64_t>(
            std::max(mClicks[sound][0].size(), mClicks[sound][1].size()));
    if (maxLen == 0) return;

    const int64_t end = position + numFrames;
    // First beat whose click may still be sounding at `position`.
    auto k = static_cast<int64_t>(std::floor(static_cast<double>(position - maxLen) / fpb));
    for (;; ++k) {
        const auto start = static_cast<int64_t>(std::llround(static_cast<double>(k) * fpb));
        if (start >= end) break;
        const bool inCountIn = start < 0 && start >= countInStart;
        const bool inSong = start >= 0 && songClicks;
        if (!inCountIn && !inSong) continue;
        const int64_t beatInBar = ((k % beatsPerBar) + beatsPerBar) % beatsPerBar;
        const std::vector<float> &click = mClicks[sound][accentOn && beatInBar == 0 ? 1 : 0];
        const auto len = static_cast<int64_t>(click.size());
        const int64_t from = std::max<int64_t>(0, position - start);
        const int64_t to = std::min<int64_t>(len, end - start);
        for (int64_t i = from; i < to; ++i) {
            const int64_t f = start + i - position;
            const float s = click[static_cast<size_t>(i)] * volume;
            for (int32_t c = 0; c < outChannels; ++c) out[f * outChannels + c] += s;
        }
    }
}

// Called before Oboe closes a stream after an error: from here on, the stream must not
// be queried (its native handle is being released).
bool AudioEngine::onError(oboe::AudioStream *stream, oboe::Result /*error*/) {
    if (stream->getDirection() == oboe::Direction::Output) {
        mOutDisconnected.store(true);
    } else {
        mInDisconnected.store(true);
    }
    return false; // let Oboe close the stream
}

// Runs on an Oboe thread after the system closed a stream (e.g. its device was unplugged).
void AudioEngine::onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) {
    if (stream->getDirection() == oboe::Direction::Output) {
        mOutDisconnected.store(true);
    } else {
        mInDisconnected.store(true);
    }
    SB_LOGW("%s stream closed by the system: %s",
            stream->getDirection() == oboe::Direction::Output ? "Output" : "Input",
            oboe::convertToText(error));
}

// ===========================================================================
// Control thread
// ===========================================================================

oboe::Result AudioEngine::open(const StreamConfig &config) {
    std::lock_guard<std::mutex> lock(mControlLock);
    closeLocked();
    mOutDisconnected.store(false);
    mInDisconnected.store(false);
    mIoCount = 0;
    mIoNext = 0;
    mClkCount = 0;
    mClkNext = 0;
    mClkEstimated = false;

    oboe::AudioStreamBuilder out;
    out.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(config.sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setUsage(oboe::Usage::Media)
            ->setDeviceId(config.outputDeviceId)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    oboe::Result result = out.openStream(mOutput);
    if (result != oboe::Result::OK) {
        SB_LOGE("Engine: output open failed: %s", oboe::convertToText(result));
        mOutput.reset();
        return result;
    }
    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);

    oboe::AudioStreamBuilder in;
    in.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(config.inputChannelCount)
            ->setSampleRate(config.sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setInputPreset(config.inputPreset)
            ->setDeviceId(config.inputDeviceId)
            ->setErrorCallback(this);
    result = in.openStream(mInput);
    if (result != oboe::Result::OK) {
        SB_LOGE("Engine: input open failed: %s", oboe::convertToText(result));
        closeLocked();
        return result;
    }

    mSampleRate = mOutput->getSampleRate();
    mInputChannels = mInput->getChannelCount();
    mOutMMap = oboe::OboeExtensions::isMMapUsed(mOutput.get());
    mInMMap = oboe::OboeExtensions::isMMapUsed(mInput.get());
    mOutApi = static_cast<int32_t>(mOutput->getAudioApi());
    mInApi = static_cast<int32_t>(mInput->getAudioApi());
    synthesizeClicks();
    mInScratchFrames = kInputScratchFrames;
    mInScratch.reset(new float[static_cast<size_t>(mInScratchFrames) * mInputChannels]);
    mRecScratch.reset(new float[static_cast<size_t>(mInScratchFrames) * 2]);
    mTrackScratchFrames = std::max(mOutput->getBufferCapacityInFrames(), kInputScratchFrames);
    mTrackScratch.reset(new float[static_cast<size_t>(mTrackScratchFrames) * 2]);
    mMasterScratch.reset(new float[static_cast<size_t>(mTrackScratchFrames) * 2]);
    mLimiter.prepare(mSampleRate);
    mMasterGain = mMasterGainTarget.load();
    mRecRing.allocate(static_cast<size_t>(kRecordRingSeconds * mSampleRate * 2));
    mMonitorScratchFrames = kInputScratchFrames;
    mMonitorScratch.reset(new float[static_cast<size_t>(mMonitorScratchFrames) * 2]);
    mMonitorRing.allocate(static_cast<size_t>(mMonitorScratchFrames) * 4);
    mInputGain = mInputGainTarget.load();
    mMonitorGain = 0.0f; // ramps up from silence

    result = mInput->requestStart();
    if (result == oboe::Result::OK) result = mOutput->requestStart();
    if (result != oboe::Result::OK) {
        SB_LOGE("Engine: start failed: %s", oboe::convertToText(result));
        closeLocked();
        return result;
    }
    SB_LOGI("Engine open: sr=%d out[api=%s ch=%d burst=%d buf=%d mmap=%d] "
            "in[api=%s ch=%d preset=%s mmap=%d]",
            mSampleRate, oboe::convertToText(mOutput->getAudioApi()), mOutput->getChannelCount(),
            mOutput->getFramesPerBurst(), mOutput->getBufferSizeInFrames(), mOutMMap ? 1 : 0,
            oboe::convertToText(mInput->getAudioApi()), mInputChannels,
            oboe::convertToText(mInput->getInputPreset()), mInMMap ? 1 : 0);
    return oboe::Result::OK;
}

// Three click sounds, each with a normal and an accented variant. Deterministic synthesis
// (no files, no randomness between runs).
void AudioEngine::synthesizeClicks() {
    const double sr = mSampleRate;
    uint32_t noise = 0x12345678u;
    auto nextNoise = [&noise]() {
        noise = noise * 1664525u + 1013904223u;
        return static_cast<float>(static_cast<int32_t>(noise)) / 2147483648.0f;
    };
    for (int accent = 0; accent < 2; ++accent) {
        const float amp = accent ? 0.9f : 0.6f;
        // 0: beep (sine with exponential decay)
        {
            const double freq = accent ? 1500.0 : 1000.0;
            auto &c = mClicks[0][accent];
            c.assign(static_cast<size_t>(0.045 * sr), 0.0f);
            for (size_t i = 0; i < c.size(); ++i) {
                const double t = i / sr;
                const double attack = std::min(1.0, t / 0.001);
                c[i] = amp * static_cast<float>(attack * std::exp(-t / 0.010) * std::sin(2 * M_PI * freq * t));
            }
        }
        // 1: wood block (higher, shorter, slightly noisy)
        {
            const double freq = accent ? 2600.0 : 2000.0;
            auto &c = mClicks[1][accent];
            c.assign(static_cast<size_t>(0.030 * sr), 0.0f);
            for (size_t i = 0; i < c.size(); ++i) {
                const double t = i / sr;
                const double env = std::min(1.0, t / 0.0005) * std::exp(-t / 0.004);
                c[i] = amp * static_cast<float>(env * (0.85 * std::sin(2 * M_PI * freq * t) + 0.15 * nextNoise()));
            }
        }
        // 2: tick (high-passed noise burst)
        {
            auto &c = mClicks[2][accent];
            c.assign(static_cast<size_t>(0.020 * sr), 0.0f);
            float prev = 0.0f;
            for (size_t i = 0; i < c.size(); ++i) {
                const double t = i / sr;
                const float n = nextNoise();
                const float hp = n - prev; // first-difference high-pass
                prev = n;
                c[i] = amp * 0.7f * static_cast<float>(std::exp(-t / (accent ? 0.004 : 0.0025))) * hp;
            }
        }
    }
}

void AudioEngine::setMetronome(bool enabled, double bpm, int32_t beatsPerBar, bool accent,
                               int32_t sound, float volume, int64_t countInStartFrame) {
    std::lock_guard<std::mutex> lock(mControlLock);
    const double sr = mSampleRate > 0 ? mSampleRate : 48000.0;
    mFramesPerBeat.store(bpm > 0 ? sr * 60.0 / bpm : 0.0);
    mBeatsPerBar.store(beatsPerBar);
    mMetroAccent.store(accent);
    mMetroSound.store(sound);
    mMetroVolume.store(volume);
    mCountInStart.store(countInStartFrame);
    mMetroEnabled.store(enabled);
}

bool AudioEngine::setTestSignal(const float *samples, int32_t count) {
    std::lock_guard<std::mutex> lock(mControlLock);
    // The audio thread reads click buffers only while the transport runs.
    if (mCurrentPublic.load() != kIdle || mRequested.load() != kIdle) {
        SB_LOGE("setTestSignal while transport is running");
        return false;
    }
    const auto n = static_cast<size_t>(std::clamp(count, 0, kMaxTestSignalFrames));
    mClicks[kTestSignalSound][0].assign(samples, samples + n);
    mClicks[kTestSignalSound][1] = mClicks[kTestSignalSound][0];
    return true;
}

void AudioEngine::setInputProcessing(float inputGain, float monitorGain, int32_t monitorChannelMode) {
    mInputGainTarget.store(std::max(0.0f, inputGain), std::memory_order_relaxed);
    mMonitorGainTarget.store(std::max(0.0f, monitorGain), std::memory_order_relaxed);
    mMonitorChannel.store(monitorChannelMode, std::memory_order_relaxed);
}

int32_t AudioEngine::fillTrackPeaks(float *out, int32_t capacity) {
    std::lock_guard<std::mutex> lock(mControlLock);
    const int32_t n = std::min(capacity, static_cast<int32_t>(mTracks.size()));
    for (int32_t i = 0; i < n; ++i) out[i] = mTracks[i]->peak.load(std::memory_order_relaxed);
    return n;
}

bool AudioEngine::streamsUsableLocked() const {
    return mOutput && mInput && !mOutDisconnected.load() && !mInDisconnected.load() &&
           mOutput->getState() != oboe::StreamState::Closed &&
           mInput->getState() != oboe::StreamState::Closed;
}

void AudioEngine::close() {
    std::lock_guard<std::mutex> lock(mControlLock);
    closeLocked();
}

void AudioEngine::closeLocked() {
    stopTransportLocked(nullptr, 0);
    if (mOutput) {
        mOutput->stop();
        mOutput->close();
    }
    if (mInput) {
        mInput->stop();
        mInput->close();
    }
    // Callbacks have stopped; now it is safe to drop the streams and tracks.
    mOutput.reset();
    mInput.reset();
    mTrackCount.store(0);
    mTracks.clear();
}

bool AudioEngine::setTracks(const std::vector<TrackConfig> &tracks) {
    std::lock_guard<std::mutex> lock(mControlLock);
    if (mCurrentPublic.load() != kIdle || mRequested.load() != kIdle) {
        SB_LOGE("setTracks while transport is running");
        return false;
    }
    mTrackCount.store(0, std::memory_order_release);
    mTracks.clear();
    const auto ringSamples = static_cast<size_t>(kTrackRingSeconds * std::max(mSampleRate, 8000) * 2);
    for (const TrackConfig &cfg : tracks) {
        auto track = std::make_unique<TrackStream>();
        if (!track->reader.open(cfg.path)) continue; // logged by the reader
        track->ring.allocate(ringSamples);
        track->startFrame = cfg.startFrame;
        track->targetL.store(cfg.gainL);
        track->targetR.store(cfg.gainR);
        track->currentL = cfg.gainL;
        track->currentR = cfg.gainR;
        mTracks.push_back(std::move(track));
    }
    SB_LOGI("Engine: %zu track(s) loaded", mTracks.size());
    return mTracks.size() == tracks.size();
}

void AudioEngine::setTrackGain(int32_t index, float gainL, float gainR) {
    std::lock_guard<std::mutex> lock(mControlLock);
    if (index >= 0 && index < static_cast<int32_t>(mTracks.size())) {
        mTracks[index]->targetL.store(std::max(0.0f, gainL), std::memory_order_relaxed);
        mTracks[index]->targetR.store(std::max(0.0f, gainR), std::memory_order_relaxed);
    }
}

void AudioEngine::setMaster(float gain, bool limiter) {
    mMasterGainTarget.store(std::max(0.0f, gain), std::memory_order_relaxed);
    mLimiterEnabled.store(limiter, std::memory_order_relaxed);
}

bool AudioEngine::produce(TrackStream &track, int32_t frames) {
    // Static buffers would be shared between threads; these live on the reader side only.
    thread_local std::vector<float> fileBuf;
    thread_local std::vector<float> stereo;
    const int32_t ch = track.reader.channels();
    fileBuf.resize(static_cast<size_t>(frames) * ch);
    stereo.assign(static_cast<size_t>(frames) * 2, 0.0f);

    const int64_t k0 = track.producedPos - track.startFrame; // take frame for the first output
    const int64_t kStart = std::max<int64_t>(k0, 0);
    const int64_t kEnd = std::min<int64_t>(k0 + frames, track.reader.frames());
    if (kStart < kEnd) {
        const int64_t got = track.reader.read(kStart, fileBuf.data(), kEnd - kStart);
        const int64_t dst = kStart - k0;
        for (int64_t i = 0; i < got; ++i) {
            const float l = fileBuf[i * ch];
            const float r = ch > 1 ? fileBuf[i * ch + 1] : l;
            stereo[(dst + i) * 2] = l;
            stereo[(dst + i) * 2 + 1] = r;
        }
    }
    track.ring.push(stereo.data(), stereo.size());
    track.producedPos += frames;
    return true;
}

void AudioEngine::readerLoop() {
    while (mReaderRun.load(std::memory_order_relaxed)) {
        bool worked = false;
        for (auto &track : mTracks) {
            while (track->ring.availableToWrite() >= static_cast<size_t>(kReaderChunkFrames) * 2) {
                produce(*track, kReaderChunkFrames);
                worked = true;
            }
        }
        if (!worked) sleepMs(5);
    }
}

void AudioEngine::writerLoop() {
    std::vector<float> buf(static_cast<size_t>(mSampleRate) * mRecChannels / 4 + 16);
    auto lastHeader = std::chrono::steady_clock::now();
    for (;;) {
        const bool running = mWriterRun.load(std::memory_order_acquire);
        const size_t avail = mRecRing.availableToRead();
        if (avail > 0) {
            const size_t n = mRecRing.pop(buf.data(), std::min(avail, buf.size()) / mRecChannels * mRecChannels);
            mWriter.write(buf.data(), n);
            mRecFrames.store(mWriter.framesWritten(), std::memory_order_relaxed);
            const auto now = std::chrono::steady_clock::now();
            if (now - lastHeader > std::chrono::seconds(1)) {
                mWriter.updateHeader(); // crash safety: at most ~1 s is lost
                lastHeader = now;
            }
            continue;
        }
        if (!running) break;
        sleepMs(5);
    }
    mWriter.close();
}

int32_t AudioEngine::totalXRunsLocked() const {
    int32_t total = 0;
    if (!streamsUsableLocked()) return total;
    if (mOutput) if (auto x = mOutput->getXRunCount()) total += x.value();
    if (mInput) if (auto x = mInput->getXRunCount()) total += x.value();
    return total;
}

bool AudioEngine::startTransport(int64_t fromFrame, const std::string &recordPath,
                                 int32_t channelMode) {
    std::lock_guard<std::mutex> lock(mControlLock);
    if (!mOutput || !mInput) return false;
    stopTransportLocked(nullptr, 0);

    // Prefill every track ring synchronously so playback starts glitch-free.
    for (auto &track : mTracks) {
        track->ring.reset();
        track->producedPos = fromFrame;
        track->currentL = track->targetL.load();
        track->currentR = track->targetR.load();
        while (track->ring.availableToWrite() >= static_cast<size_t>(kReaderChunkFrames) * 2) {
            produce(*track, kReaderChunkFrames);
        }
    }
    mTrackCount.store(static_cast<int32_t>(mTracks.size()), std::memory_order_release);

    const bool recording = !recordPath.empty();
    if (recording) {
        mRecChannelMode = channelMode;
        mRecChannels = (channelMode == kRecStereo && mInputChannels >= 2) ? 2 : 1;
        if (!mWriter.open(recordPath, mRecChannels, mSampleRate)) return false;
        mRecRing.reset();
        mRecFrames.store(0);
        mRecDropped.store(0);
        mRecStartPosition = fromFrame;
        mRecXRunsAtStart = totalXRunsLocked();
        mWriterRun.store(true, std::memory_order_release);
        mWriterThread = std::thread(&AudioEngine::writerLoop, this);
    }

    mReaderRun.store(true);
    mReaderThread = std::thread(&AudioEngine::readerLoop, this);

    mUnderruns.store(0);
    mStartPosition.store(fromFrame, std::memory_order_relaxed);
    mRequested.store(recording ? kRecording : kPlaying, std::memory_order_release);
    SB_LOGI("Transport %s from frame %lld (%d track(s))", recording ? "recording" : "playing",
            static_cast<long long>(fromFrame), static_cast<int>(mTracks.size()));
    return true;
}

void AudioEngine::stopTransport(double *recResult, int32_t capacity) {
    std::lock_guard<std::mutex> lock(mControlLock);
    stopTransportLocked(recResult, capacity);
}

void AudioEngine::stopTransportLocked(double *recResult, int32_t capacity) {
    const int32_t was = mRequested.exchange(kIdle, std::memory_order_acq_rel);
    if (was == kIdle && mCurrentPublic.load() == kIdle && !mReaderThread.joinable() &&
        !mWriterThread.joinable()) {
        return;
    }
    // Wait for the audio thread to acknowledge (it runs every few ms). If the stream is no
    // longer running (e.g. disconnected), there is no callback left to wait for.
    const auto deadline = std::chrono::steady_clock::now() + kStopTimeout;
    while (mCurrentPublic.load(std::memory_order_acquire) != kIdle &&
           std::chrono::steady_clock::now() < deadline) {
        sleepMs(1);
    }
    if (mCurrentPublic.load() != kIdle) {
        SB_LOGW("Transport stop not acknowledged by the audio thread (stream stopped?)");
    }
    mTrackCount.store(0, std::memory_order_release);

    mReaderRun.store(false);
    if (mReaderThread.joinable()) mReaderThread.join();

    const bool hadTake = mWriterThread.joinable();
    if (hadTake) {
        mWriterRun.store(false, std::memory_order_release);
        mWriterThread.join(); // drains the ring and finalizes the WAV header
    }
    if (hadTake && recResult != nullptr && capacity >= kRrCount) {
        const bool usable = streamsUsableLocked();
        if (usable) {
            sampleIoOffsetLocked();
            sampleOutputClockLocked();
        }
        double spread = 0.0;
        const double io = ioOffsetMedianLocked(&spread);
        auto latOut = usable ? mOutput->calculateLatencyMillis() : oboe::ResultWithValue<double>(oboe::Result::ErrorNull);
        auto latIn = usable ? mInput->calculateLatencyMillis() : oboe::ResultWithValue<double>(oboe::Result::ErrorNull);
        recResult[kRrFrames] = static_cast<double>(mRecFrames.load());
        recResult[kRrChannels] = mRecChannels;
        recResult[kRrStartPosition] = static_cast<double>(mRecStartPosition);
        recResult[kRrOutputStartFrame] = static_cast<double>(mRecOutStart.load());
        recResult[kRrInputStartFrame] = static_cast<double>(mRecInStart.load());
        recResult[kRrIoOffset] = io;
        recResult[kRrIoOffsetValid] = mIoCount > 0 ? 1.0 : 0.0;
        recResult[kRrIoOffsetSpread] = spread;
        recResult[kRrOutLatencyMs] = latOut ? latOut.value() : -1.0;
        recResult[kRrInLatencyMs] = latIn ? latIn.value() : -1.0;
        recResult[kRrXRunsDuring] = totalXRunsLocked() - mRecXRunsAtStart;
        recResult[kRrDropped] = static_cast<double>(mRecDropped.load());
        if (mClkCount > 0) {
            double sorted[kIoWindow];
            std::copy(mClkSamples, mClkSamples + mClkCount, sorted);
            std::sort(sorted, sorted + mClkCount);
            recResult[kRrOutClock] = sorted[mClkCount / 2];
            recResult[kRrOutClockValid] = mClkEstimated ? 2.0 : 1.0;
        } else {
            recResult[kRrOutClock] = 0.0;
            recResult[kRrOutClockValid] = 0.0;
        }
        SB_LOGI("Take finished: %lld frames, ch=%d, O_s=%lld I_s=%lld ioOffset=%.1f (spread %.1f, n=%d) "
                "xruns=%d dropped=%lld",
                static_cast<long long>(mRecFrames.load()), mRecChannels,
                static_cast<long long>(mRecOutStart.load()),
                static_cast<long long>(mRecInStart.load()), io, spread, mIoCount,
                static_cast<int>(recResult[kRrXRunsDuring]),
                static_cast<long long>(mRecDropped.load()));
    }
}

// ioOffset = (t_in - t_out) * sr - pos_in + pos_out (see AlignmentMath.kt).
void AudioEngine::sampleIoOffsetLocked() {
    if (!streamsUsableLocked()) return;
    auto out = mOutput->getTimestamp(CLOCK_MONOTONIC);
    auto in = mInput->getTimestamp(CLOCK_MONOTONIC);
    if (!out || !in) return;
    const double value = static_cast<double>(in.value().timestamp - out.value().timestamp) *
                         mSampleRate / 1e9 -
                         static_cast<double>(in.value().position) +
                         static_cast<double>(out.value().position);
    mIoSamples[mIoNext] = value;
    mIoNext = (mIoNext + 1) % kIoWindow;
    if (mIoCount < kIoWindow) ++mIoCount;
}

void AudioEngine::sampleOutputClockLocked() {
    if (!mOutput || mOutDisconnected.load() || mOutput->getState() == oboe::StreamState::Closed) return;
    double value;
    bool estimated = false;
    if (auto ts = mOutput->getTimestamp(CLOCK_MONOTONIC)) {
        value = static_cast<double>(ts.value().timestamp) * mSampleRate / 1e9 -
                static_cast<double>(ts.value().position);
    } else {
        // No timestamps (e.g. OpenSL ES): assume the frame being presented now is the one
        // written a full buffer ago.
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        const double t = static_cast<double>(now.tv_sec) * 1e9 + static_cast<double>(now.tv_nsec);
        const double presented = static_cast<double>(mOutput->getFramesWritten()) -
                                 static_cast<double>(mOutput->getBufferSizeInFrames());
        value = t * mSampleRate / 1e9 - presented;
        estimated = true;
    }
    if (estimated && mClkCount > 0 && !mClkEstimated) return; // keep real timestamps
    if (!estimated && mClkEstimated) mClkCount = 0;            // drop estimates
    mClkEstimated = estimated;
    mClkSamples[mClkNext] = value;
    mClkNext = (mClkNext + 1) % kIoWindow;
    if (mClkCount < kIoWindow) ++mClkCount;
}

double AudioEngine::ioOffsetMedianLocked(double *spread) const {
    if (mIoCount == 0) {
        if (spread) *spread = 0.0;
        return 0.0;
    }
    double sorted[kIoWindow];
    std::copy(mIoSamples, mIoSamples + mIoCount, sorted);
    std::sort(sorted, sorted + mIoCount);
    if (spread) *spread = sorted[mIoCount - 1] - sorted[0];
    return sorted[mIoCount / 2];
}

void AudioEngine::fillStatus(double *o, int32_t capacity) {
    if (capacity < kEsCount) return;
    std::lock_guard<std::mutex> lock(mControlLock);
    std::fill(o, o + kEsCount, 0.0);
    const bool open = mOutput && mInput;
    o[kEsOpen] = open ? 1.0 : 0.0;
    o[kEsTransport] = mCurrentPublic.load();
    o[kEsPosition] = static_cast<double>(mPositionPublic.load());
    o[kEsPeakL] = mPeakL.load();
    o[kEsPeakR] = mPeakR.load();
    o[kEsUnderruns] = static_cast<double>(mUnderruns.load());
    o[kEsRecFrames] = static_cast<double>(mRecFrames.load());
    o[kEsRecDropped] = static_cast<double>(mRecDropped.load());
    o[kEsOutDisconnected] = mOutDisconnected.load() ? 1.0 : 0.0;
    o[kEsInDisconnected] = mInDisconnected.load() ? 1.0 : 0.0;
    o[kEsOutXRuns] = -1;
    o[kEsInXRuns] = -1;
    o[kEsOutLatencyMs] = -1;
    o[kEsInLatencyMs] = -1;
    if (!open) return;
    o[kEsSampleRate] = mSampleRate;
    o[kEsInputChannels] = mInputChannels;
    o[kEsOutApi] = mOutApi;
    o[kEsInApi] = mInApi;
    o[kEsOutMMap] = mOutMMap ? 1.0 : 0.0;
    o[kEsInMMap] = mInMMap ? 1.0 : 0.0;
    // A stream closed by the system (device unplugged) must not be queried any further.
    if (!streamsUsableLocked()) return;

    sampleIoOffsetLocked();
    sampleOutputClockLocked();
    double spread = 0.0;
    o[kEsIoOffset] = ioOffsetMedianLocked(&spread);
    o[kEsIoOffsetValid] = mIoCount > 0 ? 1.0 : 0.0;
    o[kEsIoOffsetSpread] = spread;

    if (auto x = mOutput->getXRunCount()) o[kEsOutXRuns] = x.value();
    if (auto x = mInput->getXRunCount()) o[kEsInXRuns] = x.value();
    if (auto l = mOutput->calculateLatencyMillis()) o[kEsOutLatencyMs] = l.value();
    if (auto l = mInput->calculateLatencyMillis()) o[kEsInLatencyMs] = l.value();
    o[kEsOutDeviceId] = mOutput->getDeviceId();
    o[kEsInDeviceId] = mInput->getDeviceId();
    o[kEsOutBufferFrames] = mOutput->getBufferSizeInFrames();
    o[kEsOutBurst] = mOutput->getFramesPerBurst();
    if (mClkCount > 0) {
        double sorted[kIoWindow];
        std::copy(mClkSamples, mClkSamples + mClkCount, sorted);
        std::sort(sorted, sorted + mClkCount);
        o[kEsOutClock] = sorted[mClkCount / 2];
        o[kEsOutClockValid] = mClkEstimated ? 2.0 : 1.0;
    }
    o[kEsOutputStart] = static_cast<double>(mRecOutStart.load());
    o[kEsStartPosition] = static_cast<double>(mStartPosition.load());
    o[kEsMasterPeakL] = mMasterPeakL.load();
    o[kEsMasterPeakR] = mMasterPeakR.load();
    o[kEsLimiterGain] = mLimiterGain.load();
}

} // namespace selfband
