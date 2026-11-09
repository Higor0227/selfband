// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.feature)
}

dependencies {
    implementation(project(":core:audio"))
    implementation(project(":core:project"))
    implementation(project(":core:video"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}
