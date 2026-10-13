plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vgs.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vgs.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        // Fixed key committed to the repo so every CI build can be installed
        // over the previous one without uninstalling. For testing only.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            // R8 removes unused code (most of Compose) and resources: tens of MB -> a few MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same fixed key as debug, so it installs over earlier builds.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Material 3 Expressive (Pixel style) lives in the 1.5 line.
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.activity:activity-compose:1.11.0")
}
