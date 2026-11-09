// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import io.github.higor0227.selfband.core.ui.theme.SelfbandTheme
import io.github.higor0227.selfband.navigation.SelfbandNavHost
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** AppCompat activity so the in-app language and theme apply on every Android version (SPEC 2.8). */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var settings: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Already read by SelfbandApplication, so this returns at once.
        val showOnboarding = !runBlocking { settings.get() }.onboardingDone
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle()
            SelfbandTheme(dynamicColor = appSettings.dynamicColor) {
                SelfbandNavHost(showOnboarding)
            }
        }
    }
}
