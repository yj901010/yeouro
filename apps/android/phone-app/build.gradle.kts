plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }
val releaseApi = providers.gradleProperty("YEOURO_API_BASE_URL").orElse("")
android {
    namespace = "kr.yeouro.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.yeouro.phone"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
        }
        release {
            isMinifyEnabled = false
            // Empty means unconfigured, never a production localhost or HTTP fallback.
            buildConfigField("String", "API_BASE_URL", "\"${releaseApi.get()}\"")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true)) doFirst {
        require(releaseApi.get().startsWith("https://") && !releaseApi.get().contains('"')) {
            "Release requires -PYEOURO_API_BASE_URL=https://your-backend/"
        }
    }
}
dependencies { implementation(project(":core-ui")) }
