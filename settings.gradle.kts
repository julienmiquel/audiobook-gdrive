pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "Audiotheque"

// Architecture (cf. spécification) : trois modules métier partageant une base Room,
// et une interface Compose. Seul :sync parle à Google Drive.
include(":app")
include(":core")
include(":library")
include(":sync")
include(":player")
