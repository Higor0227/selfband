// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            compileSdk = libs.version("compileSdk").toInt()
            defaultConfig {
                minSdk = libs.version("minSdk").toInt()
                targetSdk = libs.version("targetSdk").toInt()
            }
            compileOptions {
                sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
                targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
            }
        }
    }
}
