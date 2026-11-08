// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <android/log.h>

// Logging macros for CONTROL threads only. Never call these from an audio callback:
// __android_log_print may lock and allocate.
#define SB_LOG_TAG "Selfband"
#define SB_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, SB_LOG_TAG, __VA_ARGS__)
#define SB_LOGI(...) __android_log_print(ANDROID_LOG_INFO, SB_LOG_TAG, __VA_ARGS__)
#define SB_LOGW(...) __android_log_print(ANDROID_LOG_WARN, SB_LOG_TAG, __VA_ARGS__)
#define SB_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SB_LOG_TAG, __VA_ARGS__)
