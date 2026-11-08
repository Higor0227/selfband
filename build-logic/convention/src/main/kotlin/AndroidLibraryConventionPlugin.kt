// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            namespace = derivedNamespace()
            compileSdk = libs.version("compileSdk").toInt()
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
                targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
            }
        }
        dependencies {
            add("testImplementation", libs.lib("junit"))
        }
    }
}
