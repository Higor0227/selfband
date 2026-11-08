// SPDX-License-Identifier: GPL-3.0-or-later
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).get().requiredVersion

internal fun VersionCatalog.lib(alias: String) = findLibrary(alias).get()

/** Base package; library namespaces are derived from the Gradle path. */
internal const val BASE_PACKAGE = "io.github.higor0227.selfband"

/** ":core:audio" -> "io.github.higor0227.selfband.core.audio" */
internal fun Project.derivedNamespace(): String =
    BASE_PACKAGE + path.replace(':', '.').replace('-', '_')

internal const val JAVA_VERSION = 17
