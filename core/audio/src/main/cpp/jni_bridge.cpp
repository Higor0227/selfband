// SPDX-License-Identifier: GPL-3.0-or-later
// JNI entry points for :core:audio. Called only from Kotlin control threads.
#include <jni.h>

#include <oboe/Oboe.h>

#include "probe/AudioProbe.h"

using selfband::AudioProbe;

namespace {
AudioProbe *asProbe(jlong handle) { return reinterpret_cast<AudioProbe *>(handle); }
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeCreate(
        JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new AudioProbe());
}

JNIEXPORT void JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeDestroy(
        JNIEnv *, jclass, jlong handle) {
    delete asProbe(handle);
}

JNIEXPORT jint JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeStart(
        JNIEnv *, jclass, jlong handle, jboolean withInput, jint outputDeviceId,
        jint inputDeviceId, jint inputPreset, jint inputChannelCount) {
    AudioProbe::Config config;
    config.withInput = withInput == JNI_TRUE;
    config.outputDeviceId = outputDeviceId;
    config.inputDeviceId = inputDeviceId;
    config.inputPreset = static_cast<oboe::InputPreset>(inputPreset);
    config.inputChannelCount = inputChannelCount;
    return static_cast<jint>(asProbe(handle)->start(config));
}

JNIEXPORT void JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeStop(
        JNIEnv *, jclass, jlong handle) {
    asProbe(handle)->stop();
}

JNIEXPORT void JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeGetStats(
        JNIEnv *env, jclass, jlong handle, jdoubleArray out) {
    const jsize length = env->GetArrayLength(out);
    jdouble *values = env->GetDoubleArrayElements(out, nullptr);
    asProbe(handle)->fillStats(values, length);
    env->ReleaseDoubleArrayElements(out, values, 0);
}

JNIEXPORT jstring JNICALL
Java_io_github_higor0227_selfband_core_audio_internal_NativeAudioProbe_nativeResultText(
        JNIEnv *env, jclass, jint result) {
    return env->NewStringUTF(oboe::convertToText(static_cast<oboe::Result>(result)));
}

} // extern "C"
