plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cipherlink.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cipherlink.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // itel p36 et autres telephones 32 bits inclus
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // cle de debug : APK installable tout de suite, a remplacer pour le Play Store
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Moteur natif sing-box officiel distribué par le projet singbox-android/libbox.
    implementation("com.github.singbox-android:libbox:1.14.0")
}
