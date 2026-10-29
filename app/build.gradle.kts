plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.alpha.hub"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.alpha.hub"
        minSdk = 24            // Android 7.0+ / አንድ APK ለሁሉም
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 32-bit ስልኮችም APK እንዲጭኑ ሁለቱም ABI ይፈቀዳሉ (offline AI ለ arm64 ብቻ ይሆናል)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // ቋሚ የ debug ፊርማ: እያንዳንዱ ግንባታ አንድ አይነት ፊርማ እንዲኖረው (ያለዚያ "App not installed" ይመጣል)።
    // Fixed debug signing key so every CI build can update the previous install. (Not secret; release key will be separate.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("alpha-debug.keystore")
            storePassword = "android"
            keyAlias = "alphadebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
