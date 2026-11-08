// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.feature)
}

dependencies {
    implementation(project(":core:export"))
    implementation(project(":core:project"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
