// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import io.github.higor0227.selfband.core.model.SelfbandLog
import io.github.higor0227.selfband.core.model.ThemeMode
import io.github.higor0227.selfband.core.project.AppSettingsRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class SelfbandApplication : Application() {
    @Inject lateinit var settings: AppSettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Theme and log level before the first screen (the settings file is a few hundred bytes).
        val initial = runBlocking { settings.get() }
        applySettings(initial.theme, initial.verboseLog)
        MainScope().launch {
            settings.settings.map { it.theme to it.verboseLog }.distinctUntilChanged()
                .collect { (theme, verbose) -> applySettings(theme, verbose) }
        }
    }

    private fun applySettings(theme: ThemeMode, verbose: Boolean) {
        SelfbandLog.verbose = verbose
        val mode = when (theme) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }
}
