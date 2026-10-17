pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.9.2"
        kotlin("android") version "2.1.20"
        kotlin("jvm") version "2.1.20"
        kotlin("plugin.compose") version "2.1.20"
        kotlin("plugin.serialization") version "2.1.20"
        id("com.google.devtools.ksp") version "2.1.20-2.0.1"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ColorMemo"
include(":core")
// Run the protocol tests on any JDK without installing the Android SDK.
if (!providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)) {
    include(":app")
}
