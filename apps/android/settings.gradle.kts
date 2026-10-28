pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.9.2"
        id("com.android.library") version "8.9.2"
        kotlin("android") version "2.1.20"
        kotlin("jvm") version "2.1.20"
        kotlin("plugin.serialization") version "2.1.20"
        kotlin("plugin.compose") version "2.1.20"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "YeouroNative"
if (providers.gradleProperty("jvmOnly").isPresent) rootProject.buildFileName = "build.jvm.gradle.kts"
include(":core-model", ":core-domain", ":core-network")
// Shared contracts, HTTP and state tests can run without an Android SDK/license.
if (!providers.gradleProperty("jvmOnly").isPresent) {
    include(":core-ui", ":vehicle-app", ":phone-app")
}
