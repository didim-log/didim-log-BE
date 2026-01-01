pluginManagement {
    repositories {
        mavenCentral() // MUST BE FIRST - prioritize direct Maven Central access
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral() // MUST BE FIRST - prioritize direct Maven Central access
        google()
    }
}

rootProject.name = "didim-log"
