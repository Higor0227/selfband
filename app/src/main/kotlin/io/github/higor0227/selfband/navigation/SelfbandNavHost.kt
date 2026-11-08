// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.higor0227.selfband.feature.layout.LayoutEditorViewModel
import io.github.higor0227.selfband.feature.layout.LayoutRoute
import io.github.higor0227.selfband.feature.projects.ProjectsRoute
import io.github.higor0227.selfband.feature.recorder.RecorderRoute
import io.github.higor0227.selfband.feature.recorder.RecorderViewModel
import io.github.higor0227.selfband.feature.settings.diagnostics.DiagnosticsRoute

/**
 * Top-level navigation. Feature modules never know about each other; all cross-feature
 * navigation is wired here.
 */
private object Routes {
    const val PROJECTS = "projects"
    const val RECORDER = "recorder/{${RecorderViewModel.ARG_PROJECT_ID}}"
    const val LAYOUT = "layout/{${LayoutEditorViewModel.ARG_PROJECT_ID}}"
    const val AUDIO_DIAGNOSTICS = "settings/audio-diagnostics"

    fun recorder(projectId: String) = "recorder/$projectId"
    fun layout(projectId: String) = "layout/$projectId"
}

@Composable
fun SelfbandNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectsRoute(
                onOpenProject = { id -> navController.navigate(Routes.recorder(id)) },
                onOpenDiagnostics = { navController.navigate(Routes.AUDIO_DIAGNOSTICS) },
            )
        }
        composable(
            Routes.RECORDER,
            arguments = listOf(navArgument(RecorderViewModel.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) {
            RecorderRoute(
                onBack = { navController.popBackStack() },
                onOpenLayout = { id -> navController.navigate(Routes.layout(id)) },
            )
        }
        composable(
            Routes.LAYOUT,
            arguments = listOf(navArgument(LayoutEditorViewModel.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) {
            LayoutRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.AUDIO_DIAGNOSTICS) {
            DiagnosticsRoute(onBack = { navController.popBackStack() })
        }
    }
}
