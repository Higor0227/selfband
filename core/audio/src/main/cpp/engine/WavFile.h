// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <cstdio>
#include <string>

namespace selfband {

/**
 * Streaming WAV writer for 32-bit IEEE float, canonical 44-byte header.
 * Used only from the disk writer thread (never the audio thread).
 * The header sizes are refreshed periodically so a crash loses at most the last
 * second; :core:project repairs the header from the file size on the next load.
 */
class WavWriter {
public:
    ~WavWriter() { close(); }

    bool open(const std::string &path, int32_t channels, int32_t sampleRate);
    /** Appends interleaved frames. */
    bool write(const float *samples, size_t sampleCount);
    /** Rewrites header sizes and flushes to the kernel. */
    void updateHeader();
    void close();

    int64_t framesWritten() const { return mChannels > 0 ? mSamples / mChannels : 0; }
    bool isOpen() const { return mFile != nullptr; }

private:
    void writeHeader(int64_t dataBytes);

    FILE *mFile = nullptr;
    int32_t mChannels = 0;
    int32_t mSampleRate = 0;
    int64_t mSamples = 0;
};

/** Reader for WAV files written by WavWriter (finds the data chunk generically). */
class WavReader {
public:
    ~WavReader() { close(); }

    bool open(const std::string &path);
    /** Reads up to frameCount interleaved frames starting at frameIndex; returns frames read. */
    int64_t read(int64_t frameIndex, float *out, int64_t frameCount);
    void close();

    int32_t channels() const { return mChannels; }
    int64_t frames() const { return mFrames; }

private:
    int mFd = -1;
    int32_t mChannels = 0;
    int64_t mDataOffset = 0;
    int64_t mFrames = 0;
};

} // namespace selfband
