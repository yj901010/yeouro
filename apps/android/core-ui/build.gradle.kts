plugins { id("com.android.library"); kotlin("android"); kotlin("plugin.compose") }
android {
    namespace = "kr.yeouro.ui"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    api(project(":core-network"))
    api(platform("androidx.compose:compose-bom:2025.04.01"))
    api("androidx.compose.material3:material3")
    api("androidx.compose.foundation:foundation")
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    api("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
