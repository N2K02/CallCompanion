plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One-decimal release version, supplied by CI (for example -PappVersion=5.7).
val appVersion = (findProperty("appVersion") as String?) ?: "5.6"
require(Regex("""\d+\.\d""").matches(appVersion)) {
    "appVersion must look like 5.7, got $appVersion"
}
val appVersionCode = appVersion.split(".").let { (major, minor) ->
    major.toInt() * 10 + minor.toInt()
}

android {
    namespace = "com.callcompanion.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callcompanion.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        // Fixed test key retained so CI builds remain installable without extra secrets.
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
            isMinifyEnabled = false
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
}
