// SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Selfband"

include(":app")
include(":core:model")
include(":core:project")
include(":core:audio")
include(":core:camera")
include(":core:video")
include(":core:export")
include(":core:ui")
include(":feature:projects")
include(":feature:studio")
include(":feature:recorder")
include(":feature:mixer")
include(":feature:layout")
include(":feature:export")
include(":feature:settings")
