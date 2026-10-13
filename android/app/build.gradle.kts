plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись релиза — из переменных окружения (секреты GitHub Actions, см. README).
// Без них APK подписывается отладочным ключом: ставится, но обновить его поверх другой сборки не получится.
val ks = System.getenv("FRX_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "dev.frx.userbot"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.frx.userbot"
        minSdk = 33
        targetSdk = 35
        versionCode = (System.getenv("FRX_APK_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("FRX_APK_VERSION_NAME") ?: "1.0.0"
        ndk { abiFilters += (System.getenv("FRX_ABI") ?: "arm64-v8a") }
    }

    signingConfigs {
        if (ks != null) {
            create("release") {
                storeFile = ks
                storePassword = System.getenv("FRX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FRX_KEY_ALIAS")
                keyPassword = System.getenv("FRX_KEY_PASSWORD") ?: System.getenv("FRX_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // libfrxpython.so — исполняемый файл: Android запускает его только из распакованной папки нативных библиотек
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    androidResources {
        noCompress += listOf("zip")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}
