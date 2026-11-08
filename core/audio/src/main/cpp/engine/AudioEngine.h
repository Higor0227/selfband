// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "engine/Equalizer.h"
#include "engine/Limiter.h"
#include "engine/Reverb.h"
#include "engine/SpscRing.h"
#include "engine/WavFile.h"

namespace selfband {

/** Status block copied to Kotlin. Must match EngineStat in NativeAudioEngine.kt. */
enum EngineStat : int32_t {
    kEsOpen = 0,
    kEsTransport,          // TransportState
    kEsPosition,           // project frame at the playhead
    kEsPeakL,
    kEsPeakR,
    kEsOutXRuns,           // -1 = unsupported
    kEsInXRuns,            // -1 = unsupported
    kEsUnderruns,          // track streaming underruns (disk too slow)
    kEsRecFrames,          // frames written to the take file so far
    kEsRecDropped,         // input frames lost because the disk writer fell behind
    kEsOutDisconnected,
    kEsInDisconnected,
    kEsIoOffset,           // median ioOffset in frames (see AlignmentMath.kt)
    kEsIoOffsetValid,      // 1 if timestamps are available on both streams
    kEsIoOffsetSpread,     // max - min of the recent ioOffset window, frames
    kEsOutLatencyMs,       // -1 = unavailable
    kEsInLatencyMs,        // -1 = unavailable
    kEsSampleRate,
    kEsInputChannels,
    kEsOutApi,
    kEsInApi,
    kEsOutMMap,
    kEsInMMap,
    kEsOutDeviceId,
    kEsInDeviceId,
    kEsOutBufferFrames,
    kEsOutBurst,
    // Presentation clock (the video preview follows it): the project frame heard at
    // CLOCK_MONOTONIC time t is startPosition + t * sr / 1e9 - outClock - outputStart
    // (see AlignmentMath.projectPositionAt).
    kEsOutClock,           // median of t_out * sr / 1e9 - pos_out
    kEsOutClockValid,      // 1 = from timestamps, 2 = estimated, 0 = none
    kEsOutputStart,        // output frame that played the transport's first frame
    kEsStartPosition,      // project frame the transport started from
    // Master bus (SPEC 2.5).
    kEsMasterPeakL,        // post-limiter output peak, held with decay
    kEsMasterPeakR,
    kEsLimiterGain,        // smallest limiter gain recently (1 = not limiting), held
    kEsCount
};

/** Result of a recording, copied to Kotlin. Must match RecResult in NativeAudioEngine.kt. */
enum RecResultField : int32_t {
    kRrFrames = 0,
    kRrChannels,
    kRrStartPosition,      // P_s
    kRrOutputStartFrame,   // O_s
    kRrInputStartFrame,    // I_s
    kRrIoOffset,
    kRrIoOffsetValid,
    kRrIoOffsetSpread,
    kRrOutLatencyMs,
    kRrInLatencyMs,
    kRrXRunsDuring,
    kRrDropped,
    kRrOutClock,           // median of t_out * sr / 1e9 - pos_out (frames, CLOCK_MONOTONIC)
    kRrOutClockValid,      // 1 = from timestamps, 2 = estimated from the buffer size, 0 = none
    kRrCount
};

enum TransportState : int32_t { kIdle = 0, kPlaying = 1, kRecording = 2 };

/** Which input channels go into a take. */
enum RecordChannelMode : int32_t { kRecFirst = 0, kRecSecond = 1, kRecStereo = 2 };

struct TrackConfig {
    std::string path;
    int64_t startFrame = 0; // project frame of the take's first sample
    float gainL = 1.0f;     // volume, pan, mute, solo and phase already folded in
    float gainR = 1.0f;
    float eq[Equalizer::kParamCount] = {1, 0, 80, 120, 0, 1000, 0, 1, 8000, 0};
    float send = 0.0f;      // reverb send (linear), taken after the track gains
};

/**
 * Full-duplex engine: one low-latency output stream drives the clock (data callback);
 * the input stream is read non-blocking inside the output callback.
 *
 * Audio thread rules: no allocation, no locks of ours, no logging, no I/O. All
 * communication goes through SpscRing and atomics. File I/O happens on a reader thread
 * (track streaming) and a writer thread (recording).
 *
 * Control methods are serialized by mControlLock and must not be called from the
 * audio thread.
 */
class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    struct StreamConfig {
        int32_t sampleRate = 48000;
        int32_t outputDeviceId = oboe::kUnspecified;
        int32_t inputDeviceId = oboe::kUnspecified;
        int32_t inputChannelCount = 1;
        oboe::InputPreset inputPreset = oboe::InputPreset::VoiceRecognition;
    };

    AudioEngine() = default;
    ~AudioEngine() override;

    oboe::Result open(const StreamConfig &config);
    void close();

    /** Replaces the playback tracks. Only while the transport is idle. */
    bool setTracks(const std::vector<TrackConfig> &tracks);
    /**
     * Smoothly changes a track's left/right gains on the master bus (0 = silent, negative =
     * phase inverted). Any time.
     */
    void setTrackGain(int32_t index, float gainL, float gainR);

    /** Track EQ parameters, in Equalizer::Param order (smoothed). Any time. */
    void setTrackEq(int32_t index, const float *params, int32_t count);

    /** Track reverb send level (linear, ramped). Any time. */
    void setTrackSend(int32_t index, float send);

    /** Reverb parameters, in Reverb::Param order (smoothed). Any time. */
    void setReverb(const float *params, int32_t count);

    /** Master volume (ramped) and clipping protection on/off. Any time. */
    void setMaster(float gain, bool limiter);

    /** Starts playback (and recording if recordPath is non-empty) from fromFrame. */
    bool startTransport(int64_t fromFrame, const std::string &recordPath, int32_t channelMode);
    /** Stops the transport; fills a kRrCount block if a take was being recorded. */
    void stopTransport(double *recResult, int32_t capacity);

    void fillStatus(double *out, int32_t capacity);

    /** Writes the current per-track output peak (post-gain), up to capacity values. */
    int32_t fillTrackPeaks(float *out, int32_t capacity);

    /**
     * Metronome / count-in (only ever mixed into the output, never recorded or exported).
     * countInStartFrame < 0 enables count-in clicks for project positions in
     * [countInStartFrame, 0). Can be changed at any time.
     */
    void setMetronome(bool enabled, double bpm, int32_t beatsPerBar, bool accent, int32_t sound,
                      float volume, int64_t countInStartFrame);

    /**
     * Replaces the calibration test signal (click sound kTestSignalSound). Only while the
     * transport is idle. Longer signals are truncated to kMaxTestSignalFrames.
     */
    bool setTestSignal(const float *samples, int32_t count);

    /**
     * Input processing, any time (ramped on the audio thread): digital input gain (applied
     * before metering, monitoring and recording) and software monitoring gain (0 = off).
     * monitorChannelMode picks which input channels are monitored (RecordChannelMode).
     */
    void setInputProcessing(float inputGain, float monitorGain, int32_t monitorChannelMode);

    static constexpr int32_t kTestSignalSound = 3;
    static constexpr int32_t kMaxTestSignalFrames = 48000;

    // Audio thread.
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream, void *audioData,
                                          int32_t numFrames) override;
    bool onError(oboe::AudioStream *stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream *stream, oboe::Result error) override;

private:
    struct TrackStream {
        WavReader reader;
        SpscRing ring;          // interleaved stereo, aligned to the project timeline
        int64_t startFrame = 0;
        int64_t producedPos = 0; // next project frame the reader will produce
        std::atomic<float> targetL{1.0f};
        std::atomic<float> targetR{1.0f};
        float currentL = 1.0f; // audio thread only
        float currentR = 1.0f; // audio thread only
        std::atomic<float> peak{0.0f};
        Equalizer eq;           // prepared in setTracks()/open()
        std::atomic<float> sendTarget{0.0f};
        float sendCurrent = 0.0f; // audio thread only
    };

    void mixMetronome(float *out, int32_t numFrames, int32_t outChannels, int64_t position);
    void mixMonitor(float *out, int32_t numFrames, int32_t outChannels, int32_t inCount);
    void synthesizeClicks();
    bool streamsUsableLocked() const;

    void closeLocked();
    void stopTransportLocked(double *recResult, int32_t capacity);
    bool produce(TrackStream &track, int32_t frames); // reader side
    void readerLoop();
    void writerLoop();
    void sampleIoOffsetLocked();
    double ioOffsetMedianLocked(double *spread) const;
    void sampleOutputClockLocked();
    int32_t totalXRunsLocked() const;

    std::mutex mControlLock;
    std::shared_ptr<oboe::AudioStream> mOutput;
    std::shared_ptr<oboe::AudioStream> mInput;
    int32_t mSampleRate = 0;
    int32_t mInputChannels = 0;
    // Static stream facts cached at open: never query a stream the system may have closed.
    bool mOutMMap = false;
    bool mInMMap = false;
    int32_t mOutApi = 0;
    int32_t mInApi = 0;

    // Metronome. Click samples are synthesized at open (control thread).
    static constexpr int kClickSounds = 4; // 3 synthesized + the calibration test signal
    std::vector<float> mClicks[kClickSounds][2]; // [sound][0 = normal, 1 = accent]
    std::atomic<bool> mMetroEnabled{false};
    std::atomic<double> mFramesPerBeat{24000.0};
    std::atomic<int32_t> mBeatsPerBar{4};
    std::atomic<bool> mMetroAccent{true};
    std::atomic<int32_t> mMetroSound{0};
    std::atomic<float> mMetroVolume{0.7f};
    std::atomic<int64_t> mCountInStart{0};

    // Input processing.
    std::atomic<float> mInputGainTarget{1.0f};
    float mInputGain = 1.0f;            // audio thread only
    std::atomic<float> mMonitorGainTarget{0.0f};
    float mMonitorGain = 0.0f;          // audio thread only
    std::atomic<int32_t> mMonitorChannel{kRecFirst};
    SpscRing mMonitorRing;              // used by the audio thread only (jitter FIFO)

    // Scratch buffers, allocated in open().
    std::unique_ptr<float[]> mInScratch;
    int32_t mInScratchFrames = 0;
    std::unique_ptr<float[]> mRecScratch;
    std::unique_ptr<float[]> mTrackScratch;
    std::unique_ptr<float[]> mMasterScratch; // stereo, mTrackScratchFrames
    std::unique_ptr<float[]> mReverbScratch; // stereo, mTrackScratchFrames (summed sends)
    std::unique_ptr<float[]> mMonitorScratch;
    int32_t mMonitorScratchFrames = 0;
    int32_t mTrackScratchFrames = 0;

    // Tracks: the vector only changes while idle; the audio thread reads mTrackCount.
    std::vector<std::unique_ptr<TrackStream>> mTracks;
    std::atomic<int32_t> mTrackCount{0};

    // Transport handshake (control -> audio thread).
    std::atomic<int32_t> mRequested{kIdle};
    std::atomic<int32_t> mCurrentPublic{kIdle};
    std::atomic<int64_t> mStartPosition{0};
    int32_t mCurrent = kIdle;   // audio thread only
    int64_t mPosition = 0;      // audio thread only
    std::atomic<int64_t> mPositionPublic{0};
    std::atomic<int64_t> mUnderruns{0};

    // Recording.
    SpscRing mRecRing;
    WavWriter mWriter;
    std::thread mWriterThread;
    std::atomic<bool> mWriterRun{false};
    std::atomic<int64_t> mRecFrames{0};
    std::atomic<int64_t> mRecDropped{0};
    std::atomic<int64_t> mRecOutStart{0};
    std::atomic<int64_t> mRecInStart{0};
    int32_t mRecChannelMode = kRecFirst; // read by the audio thread only while recording
    int32_t mRecChannels = 1;
    int64_t mRecStartPosition = 0;
    int32_t mRecXRunsAtStart = 0;

    // Track streaming.
    std::thread mReaderThread;
    std::atomic<bool> mReaderRun{false};

    // Master bus.
    std::atomic<float> mMasterGainTarget{1.0f};
    float mMasterGain = 1.0f;            // audio thread only
    std::atomic<bool> mLimiterEnabled{true};
    Limiter mLimiter;                    // audio thread only (prepared in open)
    Reverb mReverb;                      // send bus; prepared in open
    std::atomic<float> mMasterPeakL{0.0f};
    std::atomic<float> mMasterPeakR{0.0f};
    std::atomic<float> mLimiterGain{1.0f};

    // Metering / health.
    std::atomic<float> mPeakL{0.0f};
    std::atomic<float> mPeakR{0.0f};
    std::atomic<bool> mOutDisconnected{false};
    std::atomic<bool> mInDisconnected{false};

    // ioOffset window (control thread only).
    static constexpr int kIoWindow = 32;
    double mIoSamples[kIoWindow] = {};
    int mIoCount = 0;
    int mIoNext = 0;
    // Output clock window: t_out * sr / 1e9 - pos_out, so that the output frame presented at
    // monotonic time t is t * sr / 1e9 - clock (used to place video frames on the timeline).
    double mClkSamples[kIoWindow] = {};
    int mClkCount = 0;
    int mClkNext = 0;
    bool mClkEstimated = false;
};

} // namespace selfband
