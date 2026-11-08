// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.dependencies

/**
 * Feature modules: Android library + Compose + Hilt, depending only on core modules.
 * A feature module must never depend on another feature module (SPEC section 3.2);
 * this is enforced below.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("selfband.android.library")
        pluginManager.apply("selfband.compose")
        pluginManager.apply("selfband.hilt")
        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:ui"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("kotlinx-coroutines-android"))
        }
        val selfPath = path
        configurations.configureEach {
            dependencies.withType(ProjectDependency::class.java).configureEach {
                // Plugins may add a dependency of a module on itself (e.g. test variants); allow that.
                check(path == selfPath || !path.startsWith(":feature:")) {
                    "$selfPath must not depend on feature module $path (SPEC section 3.2)"
                }
            }
        }
    }
}
