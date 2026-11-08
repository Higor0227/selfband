// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.application)
    alias(libs.plugins.selfband.compose)
    alias(libs.plugins.selfband.hilt)
}

android {
    namespace = "io.github.higor0227.selfband"

    defaultConfig {
        applicationId = "io.github.higor0227.selfband"
        versionCode = 1
        versionName = "0.0.1"
    }

    androidResources {
        // Per-app language (Android 13+) for en and pt-BR.
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
