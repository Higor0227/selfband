// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.higor0227.selfband.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.higor0227.selfband.feature.export.ExportRoute
import io.github.higor0227.selfband.feature.export.ExportViewModel
import io.github.higor0227.selfband.feature.layout.LayoutEditorViewModel
import io.github.higor0227.selfband.feature.layout.LayoutRoute
import io.github.higor0227.selfband.feature.projects.ProjectsRoute
import io.github.higor0227.selfband.feature.recorder.RecorderRoute
import io.github.higor0227.selfband.feature.recorder.RecorderViewModel
import io.github.higor0227.selfband.feature.settings.SettingsRoute
import io.github.higor0227.selfband.feature.settings.diagnostics.DiagnosticsRoute
import io.github.higor0227.selfband.feature.settings.onboarding.OnboardingRoute

/**
 * Top-level navigation. Feature modules never know about each other; all cross-feature
 * navigation is wired here.
 */
private object Routes {
    const val PROJECTS = "projects"
    const val RECORDER = "recorder/{${RecorderViewModel.ARG_PROJECT_ID}}"
    const val LAYOUT = "layout/{${LayoutEditorViewModel.ARG_PROJECT_ID}}"
    const val EXPORT = "export/{${ExportViewModel.ARG_PROJECT_ID}}"
    const val SETTINGS = "settings"
    const val AUDIO_DIAGNOSTICS = "settings/audio-diagnostics"
    /** First run: the wizard replaces itself with the project list when done. */
    const val ONBOARDING = "onboarding"
    /** From Settings: the wizard goes back to Settings when done. */
    const val ONBOARDING_AGAIN = "settings/onboarding"

    fun recorder(projectId: String) = "recorder/$projectId"
    fun layout(projectId: String) = "layout/$projectId"
    fun export(projectId: String) = "export/$projectId"
}

/** [showOnboarding]: the first-run wizard was not finished yet (SPEC 2.8). */
@Composable
fun SelfbandNavHost(showOnboarding: Boolean) {
    val navController = rememberNavController()
    // Fixed for the life of the back stack, so a recreated activity restores it as it was.
    val start = rememberSaveable { if (showOnboarding) Routes.ONBOARDING else Routes.PROJECTS }
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(
                onDone = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ONBOARDING_AGAIN) {
            OnboardingRoute(onDone = { navController.popBackStack() })
        }
        composable(Routes.PROJECTS) {
            ProjectsRoute(
                onOpenProject = { id -> navController.navigate(Routes.recorder(id)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.RECORDER,
            arguments = listOf(navArgument(RecorderViewModel.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) {
            RecorderRoute(
                onBack = { navController.popBackStack() },
                onOpenLayout = { id -> navController.navigate(Routes.layout(id)) },
                onOpenExport = { id -> navController.navigate(Routes.export(id)) },
            )
        }
        composable(
            Routes.LAYOUT,
            arguments = listOf(navArgument(LayoutEditorViewModel.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) {
            LayoutRoute(onBack = { navController.popBackStack() })
        }
        composable(
            Routes.EXPORT,
            arguments = listOf(navArgument(ExportViewModel.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) {
            ExportRoute(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                onBack = { navController.popBackStack() },
                onOpenDiagnostics = { navController.navigate(Routes.AUDIO_DIAGNOSTICS) },
                onOpenOnboarding = { navController.navigate(Routes.ONBOARDING_AGAIN) },
            )
        }
        composable(Routes.AUDIO_DIAGNOSTICS) {
            DiagnosticsRoute(onBack = { navController.popBackStack() })
        }
    }
}
