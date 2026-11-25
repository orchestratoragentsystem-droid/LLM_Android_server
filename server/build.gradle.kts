plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версия LiteRT-LM закреплена, как и в приложении-клиенте.
val litertVersion = "0.18.1"

android {
    namespace = "com.example.llmserver"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.example.llmserver"
        minSdk = 28
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "LITERT_VERSION", "\"$litertVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-android:$litertVersion")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
}
