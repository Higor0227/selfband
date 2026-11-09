// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.selfband.android.library)
    alias(libs.plugins.selfband.compose)
}

dependencies {
    implementation(project(":core:model"))
    api(platform(libs.compose.bom))
    api(libs.compose.material3)
    api(libs.compose.material.icons.core)
}
