// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine/OfflineMixer.h"

#include <algorithm>
#include <cstring>

#include "common/Log.h"

namespace selfband {

bool OfflineMixer::open(int32_t sampleRate, const std::vector<TrackConfig> &tracks,
                        const float *reverbParams, int32_t reverbParamCount, float masterGain,
                        bool limiter) {
    mTracks.clear();
    int32_t maxChannels = 2;
    for (const TrackConfig &cfg : tracks) {
        auto track = std::make_unique<Track>();
        if (!track->reader.open(cfg.path)) {
            SB_LOGE("Export: cannot open %s", cfg.path.c_str());
            return false;
        }
        track->startFrame = cfg.startFrame;
        track->mix.configure(cfg, sampleRate, kMaxBlockFrames);
        maxChannels = std::max(maxChannels, track->reader.channels());
        mTracks.push_back(std::move(track));
    }
    const int32_t n = std::min(reverbParamCount, static_cast<int32_t>(Reverb::kParamCount));
    for (int32_t p = 0; p < n; ++p) mMaster.setReverbParam(p, reverbParams[p]);
    mMaster.gainTarget.store(std::max(0.0f, masterGain));
    mMaster.limiterEnabled.store(limiter);
    mMaster.prepare(sampleRate, kMaxBlockFrames);
    mMaster.reset();
    mFileBuf.assign(static_cast<size_t>(kMaxBlockFrames) * maxChannels, 0.0f);
    mTrackBuf.assign(static_cast<size_t>(kMaxBlockFrames) * 2, 0.0f);
    mReverbIn.assign(static_cast<size_t>(kMaxBlockFrames) * 2, 0.0f);
    SB_LOGI("Export: %zu track(s) opened", mTracks.size());
    return true;
}

void OfflineMixer::renderMix(int64_t position, int32_t frames, float *out) {
    frames = std::clamp(frames, 0, kMaxBlockFrames);
    std::memset(out, 0, static_cast<size_t>(frames) * 2 * sizeof(float));
    std::fill(mReverbIn.begin(), mReverbIn.begin() + frames * 2, 0.0f);
    for (auto &track : mTracks) {
        readTakeStereo(track->reader, track->startFrame, position, frames, mFileBuf.data(), mTrackBuf.data());
        track->mix.mix(mTrackBuf.data(), frames, out, mReverbIn.data());
    }
    mMaster.process(out, mReverbIn.data(), frames);
}

int32_t OfflineMixer::trackChannels(int32_t index) const {
    if (index < 0 || index >= trackCount()) return 0;
    return mTracks[index]->reader.channels();
}

void OfflineMixer::renderStem(int32_t index, int64_t position, int32_t frames, float *out) {
    frames = std::clamp(frames, 0, kMaxBlockFrames);
    if (index < 0 || index >= trackCount()) return;
    Track &track = *mTracks[index];
    const int32_t ch = track.reader.channels();
    std::memset(out, 0, static_cast<size_t>(frames) * ch * sizeof(float));
    const int64_t k0 = position - track.startFrame;
    const int64_t kStart = std::max<int64_t>(k0, 0);
    const int64_t kEnd = std::min<int64_t>(k0 + frames, track.reader.frames());
    if (kStart >= kEnd) return;
    track.reader.read(kStart, out + (kStart - k0) * ch, kEnd - kStart);
}

} // namespace selfband
