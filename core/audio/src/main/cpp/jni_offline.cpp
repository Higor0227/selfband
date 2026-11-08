// SPDX-License-Identifier: GPL-3.0-or-later
// JNI entry points for OfflineMix.kt (export render). Called from one Kotlin worker thread.
#include <jni.h>

#include <string>
#include <vector>

#include "engine/OfflineMixer.h"

using selfband::OfflineMixer;

namespace {
OfflineMixer *asMixer(jlong handle) { return reinterpret_cast<OfflineMixer *>(handle); }
} // namespace

#define OFFLINE_FN(ret, name) \
    extern "C" JNIEXPORT ret JNICALL \
    Java_io_github_higor0227_selfband_core_audio_OfflineMix_##name

OFFLINE_FN(jlong, nativeOpen)(JNIEnv *env, jclass, jint sampleRate, jobjectArray paths,
                              jlongArray startFrames, jfloatArray gains, jfloatArray eqs,
                              jfloatArray sends, jfloatArray reverb, jfloat masterGain,
                              jboolean limiter) {
    // Same packing as NativeAudioEngine.nativeSetTracks.
    const jsize count = env->GetArrayLength(paths);
    std::vector<selfband::TrackConfig> tracks(static_cast<size_t>(count));
    jlong *starts = env->GetLongArrayElements(startFrames, nullptr);
    jfloat *g = env->GetFloatArrayElements(gains, nullptr);
    jfloat *e = env->GetFloatArrayElements(eqs, nullptr);
    jfloat *s = env->GetFloatArrayElements(sends, nullptr);
    const jsize eqCount = env->GetArrayLength(eqs);
    constexpr jsize kEq = selfband::Equalizer::kParamCount;
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        const char *chars = env->GetStringUTFChars(path, nullptr);
        tracks[i].path = chars;
        env->ReleaseStringUTFChars(path, chars);
        env->DeleteLocalRef(path);
        tracks[i].startFrame = starts[i];
        tracks[i].gainL = g[2 * i];
        tracks[i].gainR = g[2 * i + 1];
        if ((i + 1) * kEq <= eqCount) {
            for (jsize p = 0; p < kEq; ++p) tracks[i].eq[p] = e[i * kEq + p];
        }
        tracks[i].send = s[i];
    }
    env->ReleaseLongArrayElements(startFrames, starts, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
    env->ReleaseFloatArrayElements(eqs, e, JNI_ABORT);
    env->ReleaseFloatArrayElements(sends, s, JNI_ABORT);

    const jsize reverbCount = env->GetArrayLength(reverb);
    jfloat *r = env->GetFloatArrayElements(reverb, nullptr);
    auto *mixer = new OfflineMixer();
    const bool ok = mixer->open(sampleRate, tracks, r, reverbCount, masterGain, limiter == JNI_TRUE);
    env->ReleaseFloatArrayElements(reverb, r, JNI_ABORT);
    if (!ok) {
        delete mixer;
        return 0;
    }
    return reinterpret_cast<jlong>(mixer);
}

OFFLINE_FN(void, nativeClose)(JNIEnv *, jclass, jlong handle) { delete asMixer(handle); }

OFFLINE_FN(jint, nativeTrackChannels)(JNIEnv *, jclass, jlong handle, jint index) {
    return asMixer(handle)->trackChannels(index);
}

OFFLINE_FN(void, nativeRenderMix)(JNIEnv *env, jclass, jlong handle, jlong position, jint frames,
                                  jfloatArray out) {
    jfloat *o = env->GetFloatArrayElements(out, nullptr);
    asMixer(handle)->renderMix(position, frames, o);
    env->ReleaseFloatArrayElements(out, o, 0);
}

OFFLINE_FN(void, nativeRenderStem)(JNIEnv *env, jclass, jlong handle, jint index, jlong position,
                                   jint frames, jfloatArray out) {
    jfloat *o = env->GetFloatArrayElements(out, nullptr);
    asMixer(handle)->renderStem(index, position, frames, o);
    env->ReleaseFloatArrayElements(out, o, 0);
}
