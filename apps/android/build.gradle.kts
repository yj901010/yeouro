// Keep Android/Kotlin plugins on one classloader for the native build.
plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    kotlin("android") apply false
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
}
allprojects { group = "kr.yeouro"; version = "0.1.0" }
