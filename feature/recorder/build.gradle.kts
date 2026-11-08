// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.feature)
}

dependencies {
    implementation(project(":core:project"))
    implementation(project(":core:audio"))
    implementation(project(":core:camera"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
