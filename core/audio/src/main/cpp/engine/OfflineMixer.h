// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <memory>
#include <vector>

#include "engine/MixGraph.h"
#include "engine/WavFile.h"

namespace selfband {

/**
 * Export render (SPEC 3.3): runs the same mix graph as live playback, as fast as the disk
 * allows, on the caller's thread. Independent from AudioEngine, so it works whether or not the
 * streams are open. Also reads dry, aligned stems.
 *
 * Render blocks in order from the start position: effect state (EQ, reverb tail, limiter)
 * carries over between calls just as in playback.
 */
class OfflineMixer {
public:
    static constexpr int32_t kMaxBlockFrames = 4096;

    /** Opens every take. False if one cannot be read. */
    bool open(int32_t sampleRate, const std::vector<TrackConfig> &tracks, const float *reverbParams,
              int32_t reverbParamCount, float masterGain, bool limiter);

    /** Clears effect state (EQ, reverb tail, limiter) before rendering a new pass. */
    void reset();

    /** Mix of project frames [position, position + frames) as interleaved stereo. */
    void renderMix(int64_t position, int32_t frames, float *out);

    int32_t trackCount() const { return static_cast<int32_t>(mTracks.size()); }
    int32_t trackChannels(int32_t index) const;

    /**
     * Track [index] as recorded (no EQ, gain or effects), placed on the timeline, in its own
     * channel count: silence before and after the take.
     */
    void renderStem(int32_t index, int64_t position, int32_t frames, float *out);

private:
    struct Track {
        WavReader reader;
        int64_t startFrame = 0;
        TrackMix mix;
    };

    std::vector<std::unique_ptr<Track>> mTracks;
    MasterBus mMaster;
    std::vector<float> mFileBuf;
    std::vector<float> mTrackBuf;
    std::vector<float> mReverbIn;
};

} // namespace selfband
