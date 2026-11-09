// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.application)
    alias(libs.plugins.selfband.compose)
    alias(libs.plugins.selfband.hilt)
}

android {
    namespace = "io.github.higor0227.selfband"
    // Same NDK as :core:audio, so the packaging step can strip the native libraries.
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.github.higor0227.selfband"
        // Release versions are set here; the CI release job checks that the tag is v<versionName>.
        // versionCode = major * 10000 + minor * 100 + patch.
        versionCode = 1
        versionName = "0.0.1"
    }

    // Release signing comes only from the environment (GitHub Secrets in CI, see docs/RELEASING.md);
    // no key or password is ever stored in the repository.
    val keystoreFile = providers.environmentVariable("SELFBAND_KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = providers.environmentVariable("SELFBAND_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("SELFBAND_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SELFBAND_KEY_PASSWORD").get()
            }
        }
    }
    // Local testing of the minified build: `-Pselfband.debugSignedRelease=true` signs it with the
    // debug key, so it installs over a debug build without losing data. Never for distribution.
    val debugSignedRelease = providers.gradleProperty("selfband.debugSignedRelease").orNull == "true"

    // F-Droid rejects the encrypted dependency list Google adds to the APK signing block; the
    // Play app bundle keeps it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    androidResources {
        // Per-app language (Android 13+) for en and pt-BR.
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a key the release build is unsigned (e.g. for F-Droid, which signs it itself).
            signingConfig = when {
                keystoreFile != null -> signingConfigs.getByName("release")
                debugSignedRelease -> signingConfigs.getByName("debug")
                else -> null
            }
            // Native symbol table in the app bundle, so Play Console can symbolicate native crashes.
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":core:audio"))
    implementation(project(":core:project"))
    implementation(project(":feature:projects"))
    implementation(project(":feature:layout"))
    implementation(project(":feature:recorder"))
    implementation(project(":feature:export"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
