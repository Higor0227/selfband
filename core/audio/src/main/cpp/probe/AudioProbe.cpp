// SPDX-License-Identifier: GPL-3.0-or-later
#include "probe/AudioProbe.h"

#include <cmath>
#include <cstring>

#include <oboe/OboeExtensions.h>

#include "common/Log.h"

namespace selfband {

namespace {
// Per-callback decay applied to the held input peak (simple meter ballistics).
constexpr float kPeakDecay = 0.95f;
} // namespace

// ---------------------------------------------------------------------------
// Audio thread
// ---------------------------------------------------------------------------

oboe::DataCallbackResult AudioProbe::StreamCallback::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    const int32_t samples = numFrames * stream->getChannelCount();
    if (mIsInput) {
        const auto *in = static_cast<const float *>(audioData);
        float maxAbs = 0.0f;
        for (int32_t i = 0; i < samples; ++i) {
            const float a = std::fabs(in[i]);
            if (a > maxAbs) maxAbs = a;
        }
        const float held = peak.load(std::memory_order_relaxed) * kPeakDecay;
        peak.store(maxAbs > held ? maxAbs : held, std::memory_order_relaxed);
    } else {
        std::memset(audioData, 0, static_cast<size_t>(samples) * sizeof(float));
    }
    callbacks.fetch_add(1, std::memory_order_relaxed);
    frames.fetch_add(numFrames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

// Called before Oboe closes the stream after an error: stop querying it from now on.
bool AudioProbe::StreamCallback::onError(oboe::AudioStream * /*stream*/, oboe::Result /*error*/) {
    disconnected.store(true, std::memory_order_relaxed);
    return false;
}

// Called on a separate Oboe-owned thread after the stream has been closed.
void AudioProbe::StreamCallback::onErrorAfterClose(oboe::AudioStream * /*stream*/,
                                                   oboe::Result error) {
    disconnected.store(true, std::memory_order_relaxed);
    SB_LOGW("%s stream closed by system: %s", mIsInput ? "Input" : "Output",
            oboe::convertToText(error));
}

void AudioProbe::StreamCallback::reset() {
    callbacks.store(0);
    frames.store(0);
    peak.store(0.0f);
    disconnected.store(false);
}

// ---------------------------------------------------------------------------
// Control thread
// ---------------------------------------------------------------------------

AudioProbe::~AudioProbe() { stop(); }

oboe::Result AudioProbe::start(const Config &config) {
    std::lock_guard<std::mutex> lock(mControlLock);
    closeAllLocked();
    mOutputCallback.reset();
    mInputCallback.reset();

    oboe::Result result = openOutput(config);
    if (result != oboe::Result::OK) return result;

    if (config.withInput) {
        result = openInput(config);
        if (result != oboe::Result::OK) {
            closeAllLocked();
            return result;
        }
    }

    if (mInput) {
        result = mInput->requestStart();
        if (result != oboe::Result::OK) {
            SB_LOGE("Input requestStart failed: %s", oboe::convertToText(result));
            closeAllLocked();
            return result;
        }
    }
    result = mOutput->requestStart();
    if (result != oboe::Result::OK) {
        SB_LOGE("Output requestStart failed: %s", oboe::convertToText(result));
        closeAllLocked();
    }
    return result;
}

oboe::Result AudioProbe::openOutput(const Config &config) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Media)
            ->setDeviceId(config.outputDeviceId)
            ->setDataCallback(&mOutputCallback)
            ->setErrorCallback(&mOutputCallback);

    oboe::Result result = builder.openStream(mOutput);
    if (result != oboe::Result::OK) {
        SB_LOGE("Output openStream failed: %s", oboe::convertToText(result));
        mOutput.reset();
        return result;
    }
    // Two bursts is the usual low-latency starting point; underruns show up as xruns.
    mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);
    SB_LOGI("Output opened: api=%s sr=%d ch=%d burst=%d buf=%d sharing=%s perf=%s mmap=%d",
            oboe::convertToText(mOutput->getAudioApi()), mOutput->getSampleRate(),
            mOutput->getChannelCount(), mOutput->getFramesPerBurst(),
            mOutput->getBufferSizeInFrames(), oboe::convertToText(mOutput->getSharingMode()),
            oboe::convertToText(mOutput->getPerformanceMode()),
            oboe::OboeExtensions::isMMapUsed(mOutput.get()) ? 1 : 0);
    return oboe::Result::OK;
}

oboe::Result AudioProbe::openInput(const Config &config) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(config.inputChannelCount)
            ->setInputPreset(config.inputPreset)
            ->setDeviceId(config.inputDeviceId)
            // Match the output rate so a later full-duplex stream needs no resampling.
            ->setSampleRate(mOutput ? mOutput->getSampleRate() : oboe::kUnspecified)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setDataCallback(&mInputCallback)
            ->setErrorCallback(&mInputCallback);

    oboe::Result result = builder.openStream(mInput);
    if (result != oboe::Result::OK) {
        SB_LOGE("Input openStream failed: %s", oboe::convertToText(result));
        mInput.reset();
        return result;
    }
    SB_LOGI("Input opened: api=%s sr=%d ch=%d burst=%d sharing=%s perf=%s preset=%s mmap=%d",
            oboe::convertToText(mInput->getAudioApi()), mInput->getSampleRate(),
            mInput->getChannelCount(), mInput->getFramesPerBurst(),
            oboe::convertToText(mInput->getSharingMode()),
            oboe::convertToText(mInput->getPerformanceMode()),
            oboe::convertToText(mInput->getInputPreset()),
            oboe::OboeExtensions::isMMapUsed(mInput.get()) ? 1 : 0);
    return oboe::Result::OK;
}

void AudioProbe::stop() {
    std::lock_guard<std::mutex> lock(mControlLock);
    closeAllLocked();
}

void AudioProbe::closeAllLocked() {
    if (mOutput) {
        mOutput->stop();
        mOutput->close();
        mOutput.reset();
    }
    if (mInput) {
        mInput->stop();
        mInput->close();
        mInput.reset();
    }
}

void AudioProbe::fillStats(double *out, int32_t capacity) {
    if (capacity < 2 * kStatCount) return;
    std::lock_guard<std::mutex> lock(mControlLock);
    fillStreamStats(mOutput.get(), mOutputCallback, out);
    fillStreamStats(mInput.get(), mInputCallback, out + kStatCount);
}

void AudioProbe::fillStreamStats(oboe::AudioStream *stream, const StreamCallback &cb,
                                 double *out) {
    for (int32_t i = 0; i < kStatCount; ++i) out[i] = 0.0;
    out[kStatDisconnected] = cb.disconnected.load() ? 1.0 : 0.0;
    out[kStatCallbacks] = static_cast<double>(cb.callbacks.load());
    out[kStatFrames] = static_cast<double>(cb.frames.load());
    out[kStatPeak] = cb.peak.load();
    // A stream the system closed (device unplugged) must not be queried any further.
    if (stream == nullptr || cb.disconnected.load() ||
        stream->getState() == oboe::StreamState::Closed) return;

    out[kStatOpen] = 1.0;
    out[kStatApi] = static_cast<double>(stream->getAudioApi());
    out[kStatSampleRate] = stream->getSampleRate();
    out[kStatChannelCount] = stream->getChannelCount();
    out[kStatFormat] = static_cast<double>(stream->getFormat());
    out[kStatFramesPerBurst] = stream->getFramesPerBurst();
    out[kStatBufferSize] = stream->getBufferSizeInFrames();
    out[kStatBufferCapacity] = stream->getBufferCapacityInFrames();
    out[kStatSharingMode] = static_cast<double>(stream->getSharingMode());
    out[kStatPerformanceMode] = static_cast<double>(stream->getPerformanceMode());
    out[kStatMMap] = stream->getAudioApi() == oboe::AudioApi::AAudio
                     ? (oboe::OboeExtensions::isMMapUsed(stream) ? 1.0 : 0.0)
                     : -1.0;
    auto xruns = stream->getXRunCount();
    out[kStatXRuns] = xruns ? xruns.value() : -1.0;
    auto latency = stream->calculateLatencyMillis();
    out[kStatLatencyMs] = latency ? latency.value() : -1.0;
    out[kStatDeviceId] = stream->getDeviceId();
}

} // namespace selfband
