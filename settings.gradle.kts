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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeModule("com.github.MuntashirAkon.spake2-java", "spake2-android") }
        }
    }
}

rootProject.name = "padconnect"
include(":app")
include(":remote", ":adb")
project(":remote").projectDir = file("scrcpy-android/app")
project(":adb").projectDir = file("scrcpy-android/vendor/libadb-android/libadb")
 
