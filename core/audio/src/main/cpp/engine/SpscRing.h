// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <memory>

namespace selfband {

/**
 * Lock-free single-producer/single-consumer ring of floats.
 *
 * Memory is allocated only in allocate(), on a control thread. push()/pop() never
 * allocate or block and are safe to call from the audio thread (as producer or consumer).
 * reset() must only be called while neither side is active.
 */
class SpscRing {
public:
    void allocate(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity) cap <<= 1;
        mBuffer.reset(new float[cap]);
        mCapacity = cap;
        mMask = cap - 1;
        reset();
    }

    void reset() {
        mRead.store(0, std::memory_order_relaxed);
        mWrite.store(0, std::memory_order_relaxed);
    }

    size_t capacity() const { return mCapacity; }

    size_t availableToRead() const {
        return mWrite.load(std::memory_order_acquire) - mRead.load(std::memory_order_relaxed);
    }

    size_t availableToWrite() const {
        return mCapacity - (mWrite.load(std::memory_order_relaxed) -
                            mRead.load(std::memory_order_acquire));
    }

    /** Writes up to count samples; returns how many were written. Producer side only. */
    size_t push(const float *data, size_t count) {
        const size_t w = mWrite.load(std::memory_order_relaxed);
        const size_t r = mRead.load(std::memory_order_acquire);
        const size_t space = mCapacity - (w - r);
        if (count > space) count = space;
        copyIn(w, data, count);
        mWrite.store(w + count, std::memory_order_release);
        return count;
    }

    /** Reads up to count samples; returns how many were read. Consumer side only. */
    size_t pop(float *out, size_t count) {
        const size_t r = mRead.load(std::memory_order_relaxed);
        const size_t w = mWrite.load(std::memory_order_acquire);
        const size_t avail = w - r;
        if (count > avail) count = avail;
        copyOut(r, out, count);
        mRead.store(r + count, std::memory_order_release);
        return count;
    }

private:
    void copyIn(size_t pos, const float *data, size_t count) {
        const size_t start = pos & mMask;
        const size_t first = (count < mCapacity - start) ? count : mCapacity - start;
        std::memcpy(mBuffer.get() + start, data, first * sizeof(float));
        if (count > first) std::memcpy(mBuffer.get(), data + first, (count - first) * sizeof(float));
    }

    void copyOut(size_t pos, float *out, size_t count) const {
        const size_t start = pos & mMask;
        const size_t first = (count < mCapacity - start) ? count : mCapacity - start;
        std::memcpy(out, mBuffer.get() + start, first * sizeof(float));
        if (count > first) std::memcpy(out + first, mBuffer.get(), (count - first) * sizeof(float));
    }

    std::unique_ptr<float[]> mBuffer;
    size_t mCapacity = 0;
    size_t mMask = 0;
    // Monotonic indices (wrap via mask); size_t overflow is harmless with unsigned math.
    alignas(64) std::atomic<size_t> mRead{0};
    alignas(64) std::atomic<size_t> mWrite{0};
};

} // namespace selfband
