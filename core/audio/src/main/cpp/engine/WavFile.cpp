// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine/WavFile.h"

#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include "common/Log.h"

namespace selfband {

namespace {
void putU32(uint8_t *p, uint32_t v) {
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}
void putU16(uint8_t *p, uint16_t v) { p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; }
uint32_t getU32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
uint16_t getU16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }
uint32_t clampU32(int64_t v) { return v > 0xFFFFFFFFLL ? 0xFFFFFFFFu : static_cast<uint32_t>(v); }
constexpr int kBytesPerSample = 4;
} // namespace

// ---------------------------------------------------------------------------
// WavWriter
// ---------------------------------------------------------------------------

bool WavWriter::open(const std::string &path, int32_t channels, int32_t sampleRate) {
    close();
    mFile = std::fopen(path.c_str(), "wb");
    if (!mFile) {
        SB_LOGE("WavWriter: cannot open %s", path.c_str());
        return false;
    }
    mChannels = channels;
    mSampleRate = sampleRate;
    mSamples = 0;
    writeHeader(0);
    std::fflush(mFile);
    return true;
}

void WavWriter::writeHeader(int64_t dataBytes) {
    uint8_t h[44];
    std::memcpy(h, "RIFF", 4);
    putU32(h + 4, clampU32(36 + dataBytes));
    std::memcpy(h + 8, "WAVEfmt ", 8);
    putU32(h + 16, 16);
    putU16(h + 20, 3); // WAVE_FORMAT_IEEE_FLOAT
    putU16(h + 22, static_cast<uint16_t>(mChannels));
    putU32(h + 24, static_cast<uint32_t>(mSampleRate));
    putU32(h + 28, static_cast<uint32_t>(mSampleRate * mChannels * kBytesPerSample));
    putU16(h + 32, static_cast<uint16_t>(mChannels * kBytesPerSample));
    putU16(h + 34, 32);
    std::memcpy(h + 36, "data", 4);
    putU32(h + 40, clampU32(dataBytes));
    std::fseek(mFile, 0, SEEK_SET);
    std::fwrite(h, 1, sizeof(h), mFile);
    std::fseek(mFile, 0, SEEK_END);
}

bool WavWriter::write(const float *samples, size_t sampleCount) {
    if (!mFile) return false;
    const size_t n = std::fwrite(samples, sizeof(float), sampleCount, mFile);
    mSamples += static_cast<int64_t>(n);
    return n == sampleCount;
}

void WavWriter::updateHeader() {
    if (!mFile) return;
    writeHeader(mSamples * kBytesPerSample);
    std::fflush(mFile);
}

void WavWriter::close() {
    if (!mFile) return;
    updateHeader();
    fsync(fileno(mFile));
    std::fclose(mFile);
    mFile = nullptr;
}

// ---------------------------------------------------------------------------
// WavReader
// ---------------------------------------------------------------------------

bool WavReader::open(const std::string &path) {
    close();
    mFd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (mFd < 0) {
        SB_LOGE("WavReader: cannot open %s", path.c_str());
        return false;
    }
    uint8_t riff[12];
    if (pread(mFd, riff, 12, 0) != 12 || std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(riff + 8, "WAVE", 4) != 0) {
        SB_LOGE("WavReader: not a WAV file: %s", path.c_str());
        close();
        return false;
    }
    struct stat st{};
    fstat(mFd, &st);
    const int64_t fileSize = st.st_size;

    int64_t pos = 12;
    int32_t format = 0, bits = 0;
    while (pos + 8 <= fileSize) {
        uint8_t ch[8];
        if (pread(mFd, ch, 8, pos) != 8) break;
        const uint32_t size = getU32(ch + 4);
        if (std::memcmp(ch, "fmt ", 4) == 0) {
            uint8_t fmt[16];
            if (pread(mFd, fmt, 16, pos + 8) != 16) break;
            format = getU16(fmt);
            mChannels = getU16(fmt + 2);
            bits = getU16(fmt + 14);
        } else if (std::memcmp(ch, "data", 4) == 0) {
            mDataOffset = pos + 8;
            // Trust the file size over the header: the header may be stale after a crash.
            const int64_t dataBytes = fileSize - mDataOffset;
            if (mChannels > 0) mFrames = dataBytes / (mChannels * kBytesPerSample);
            break;
        }
        pos += 8 + size + (size & 1);
    }
    if (format != 3 || bits != 32 || mChannels <= 0 || mDataOffset == 0) {
        SB_LOGE("WavReader: unsupported format (fmt=%d bits=%d ch=%d) in %s", format, bits,
                mChannels, path.c_str());
        close();
        return false;
    }
    return true;
}

int64_t WavReader::read(int64_t frameIndex, float *out, int64_t frameCount) {
    if (mFd < 0 || frameIndex < 0 || frameIndex >= mFrames) return 0;
    frameCount = std::min(frameCount, mFrames - frameIndex);
    const int64_t bytes = frameCount * mChannels * kBytesPerSample;
    const ssize_t got = pread(mFd, out, static_cast<size_t>(bytes),
                              mDataOffset + frameIndex * mChannels * kBytesPerSample);
    if (got <= 0) return 0;
    return got / (mChannels * kBytesPerSample);
}

void WavReader::close() {
    if (mFd >= 0) ::close(mFd);
    mFd = -1;
    mChannels = 0;
    mFrames = 0;
    mDataOffset = 0;
}

} // namespace selfband
