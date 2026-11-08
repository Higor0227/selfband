// SPDX-License-Identifier: GPL-3.0-or-later
// JNI entry points for NativeAudioEngine.kt. Called only from Kotlin control threads.
#include <jni.h>

#include <string>
#include <vector>

#include "engine/AudioEngine.h"

using selfband::AudioEngine;

namespace {
AudioEngine *asEngine(jlong handle) { return reinterpret_cast<AudioEngine *>(handle); }

std::string toStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(s, chars);
    return result;
}

void withDoubles(JNIEnv *env, jdoubleArray array, auto &&fn) {
    const jsize length = env->GetArrayLength(array);
    jdouble *values = env->GetDoubleArrayElements(array, nullptr);
    fn(values, length);
    env->ReleaseDoubleArrayElements(array, values, 0);
}
} // namespace

#define ENGINE_FN(ret, name) \
    extern "C" JNIEXPORT ret JNICALL \
    Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioEngine_##name

ENGINE_FN(jlong, nativeCreate)(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new AudioEngine());
}

ENGINE_FN(void, nativeDestroy)(JNIEnv *, jclass, jlong handle) { delete asEngine(handle); }

ENGINE_FN(jint, nativeOpen)(JNIEnv *, jclass, jlong handle, jint sampleRate, jint outputDeviceId,
                            jint inputDeviceId, jint inputChannelCount, jint inputPreset) {
    AudioEngine::StreamConfig config;
    config.sampleRate = sampleRate;
    config.outputDeviceId = outputDeviceId;
    config.inputDeviceId = inputDeviceId;
    config.inputChannelCount = inputChannelCount;
    config.inputPreset = static_cast<oboe::InputPreset>(inputPreset);
    return static_cast<jint>(asEngine(handle)->open(config));
}

ENGINE_FN(void, nativeClose)(JNIEnv *, jclass, jlong handle) { asEngine(handle)->close(); }

ENGINE_FN(jboolean, nativeSetTracks)(JNIEnv *env, jclass, jlong handle, jobjectArray paths,
                                     jlongArray startFrames, jfloatArray gains) {
    // gains: left/right pairs, two per track.
    const jsize count = env->GetArrayLength(paths);
    std::vector<selfband::TrackConfig> tracks(static_cast<size_t>(count));
    jlong *starts = env->GetLongArrayElements(startFrames, nullptr);
    jfloat *g = env->GetFloatArrayElements(gains, nullptr);
    for (jsize i = 0; i < count; ++i) {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        tracks[i].path = toStdString(env, path);
        env->DeleteLocalRef(path);
        tracks[i].startFrame = starts[i];
        tracks[i].gainL = g[2 * i];
        tracks[i].gainR = g[2 * i + 1];
    }
    env->ReleaseLongArrayElements(startFrames, starts, JNI_ABORT);
    env->ReleaseFloatArrayElements(gains, g, JNI_ABORT);
    return asEngine(handle)->setTracks(tracks) ? JNI_TRUE : JNI_FALSE;
}

ENGINE_FN(void, nativeSetTrackGain)(JNIEnv *, jclass, jlong handle, jint index, jfloat gainL,
                                   jfloat gainR) {
    asEngine(handle)->setTrackGain(index, gainL, gainR);
}

ENGINE_FN(void, nativeSetMaster)(JNIEnv *, jclass, jlong handle, jfloat gain, jboolean limiter) {
    asEngine(handle)->setMaster(gain, limiter == JNI_TRUE);
}

ENGINE_FN(jboolean, nativeStartTransport)(JNIEnv *env, jclass, jlong handle, jlong fromFrame,
                                          jstring recordPath, jint channelMode) {
    return asEngine(handle)->startTransport(fromFrame, toStdString(env, recordPath), channelMode)
           ? JNI_TRUE : JNI_FALSE;
}

ENGINE_FN(void, nativeStopTransport)(JNIEnv *env, jclass, jlong handle, jdoubleArray result) {
    withDoubles(env, result, [&](jdouble *v, jsize n) { asEngine(handle)->stopTransport(v, n); });
}

ENGINE_FN(jint, nativeGetTrackPeaks)(JNIEnv *env, jclass, jlong handle, jfloatArray out) {
    const jsize length = env->GetArrayLength(out);
    jfloat *values = env->GetFloatArrayElements(out, nullptr);
    const jint n = asEngine(handle)->fillTrackPeaks(values, length);
    env->ReleaseFloatArrayElements(out, values, 0);
    return n;
}

ENGINE_FN(void, nativeSetMetronome)(JNIEnv *, jclass, jlong handle, jboolean enabled, jdouble bpm,
                                    jint beatsPerBar, jboolean accent, jint sound, jfloat volume,
                                    jlong countInStartFrame) {
    asEngine(handle)->setMetronome(enabled == JNI_TRUE, bpm, beatsPerBar, accent == JNI_TRUE, sound,
                                   volume, countInStartFrame);
}

ENGINE_FN(void, nativeGetStatus)(JNIEnv *env, jclass, jlong handle, jdoubleArray out) {
    withDoubles(env, out, [&](jdouble *v, jsize n) { asEngine(handle)->fillStatus(v, n); });
}

ENGINE_FN(jboolean, nativeSetTestSignal)(JNIEnv *env, jclass, jlong handle, jfloatArray samples) {
    const jsize length = env->GetArrayLength(samples);
    jfloat *values = env->GetFloatArrayElements(samples, nullptr);
    const bool ok = asEngine(handle)->setTestSignal(values, length);
    env->ReleaseFloatArrayElements(samples, values, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

ENGINE_FN(void, nativeSetInputProcessing)(JNIEnv *, jclass, jlong handle, jfloat inputGain,
                                          jfloat monitorGain, jint monitorChannelMode) {
    asEngine(handle)->setInputProcessing(inputGain, monitorGain, monitorChannelMode);
}
