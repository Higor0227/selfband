// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.library)
    alias(libs.plugins.selfband.hilt)
}

android {
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        // Generic ABIs only; arm64 is what real phones use, x86_64 is for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                // Oboe's prefab package is built against the shared C++ runtime.
                arguments += "-DANDROID_STL=c++_shared"
                cppFlags += "-std=c++20"
            }
        }
    }

    buildFeatures { prefab = true }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.oboe)
    implementation(libs.kotlinx.coroutines.android)
}
