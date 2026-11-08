// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.library)
    alias(libs.plugins.selfband.hilt)
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:audio"))
    implementation(project(":core:project"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
