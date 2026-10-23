plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10"
}

// Release builds get their version from CI (-PappVersion=1.x). Local builds default to 0.1,
// below every release, so the in-app updater and Android can install releases over them.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1"
require(Regex("""\d+\.\d""").matches(appVersion)) {
    "appVersion must look like 5.7, got $appVersion"
}
val appVersionCode = appVersion.split(".").let { (major, minor) ->
    major.toInt() * 10 + minor.toInt()
}

android {
    namespace = "com.pcl.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.pcl.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: appVersionCode
        versionName = appVersion + ((findProperty("appVersionSuffix") as String?) ?: "")
    }

    buildFeatures {
        compose = true
    }


    signingConfigs {
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

 // Opt-in compile-only shared library evaluation; no production runtime cutover.
dependencies {
    implementation("dev.n2k02:theme:0.1.4")
        implementation("dev.n2k02:updater:0.1.13")
}
