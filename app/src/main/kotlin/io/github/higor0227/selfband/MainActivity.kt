// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import io.github.higor0227.selfband.core.ui.theme.SelfbandTheme
import io.github.higor0227.selfband.navigation.SelfbandNavHost

@AndroidEntryPoint
/** AppCompat activity so the in-app language and theme apply on every Android version (SPEC 2.8). */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SelfbandTheme {
                SelfbandNavHost()
            }
        }
    }
}
