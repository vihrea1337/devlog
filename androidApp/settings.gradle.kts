// Настройки Gradle-проекта Android-приложения DevLog.
// pluginManagement — откуда качать плагины сборки (Android Gradle Plugin, Kotlin).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// dependencyResolutionManagement — откуда качать библиотеки (androidx, compose, retrofit и т.д.).
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DevLogAndroid"
include(":app")

// Общий модуль с моделями обмена (DTO) — тот же, что использует бэкенд.
// Так приложение и сервер не могут разъехаться по полям незаметно.
includeBuild("../shared")
