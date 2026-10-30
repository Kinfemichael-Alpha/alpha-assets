plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.alpha.hub"
    compileSdk = 34
    ndkVersion = "26.3.11579264"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.kinfemichael.apps.alphaai"   // የመተግበሪያ መለያ (በ Play ላይ ቋሚ ነው) / permanent app id
        minSdk = 24            // Android 7.0+ / አንድ APK ለሁሉም
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // 32-bit ስልኮችም APK እንዲጭኑ ሁለቱም ABI ይፈቀዳሉ (offline AI ለ arm64 ብቻ ይሆናል)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake {
                abiFilters += listOf("arm64-v8a")
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
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
    implementation("com.google.android.gms:play-services-ads:23.3.0")
}
