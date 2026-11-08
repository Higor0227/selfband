// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.higor0227.selfband.feature.projects.ProjectsRoute
import io.github.higor0227.selfband.feature.settings.diagnostics.DiagnosticsRoute

/**
 * Top-level navigation. Feature modules never know about each other; all cross-feature
 * navigation is wired here.
 */
private object Routes {
    const val PROJECTS = "projects"
    const val AUDIO_DIAGNOSTICS = "settings/audio-diagnostics"
}

@Composable
fun SelfbandNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectsRoute(onOpenDiagnostics = { navController.navigate(Routes.AUDIO_DIAGNOSTICS) })
        }
        composable(Routes.AUDIO_DIAGNOSTICS) {
            DiagnosticsRoute(onBack = { navController.popBackStack() })
        }
    }
}
