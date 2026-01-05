pluginManagement {
    repositories {
        google() // 1순위: Google 미러 사용 (Rate Limiting 회피)
        gradlePluginPortal()
        mavenCentral() // 2순위: 없는 경우에만 Maven Central 사용
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google() // 1순위: Google 미러 사용 (Rate Limiting 회피)
        mavenCentral() // 2순위: 없는 경우에만 Maven Central 사용
    }
}

rootProject.name = "didim-log"
