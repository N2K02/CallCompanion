buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 has Kotlin built in; this picks the Kotlin version it uses.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.0" apply false
    // Must match the Kotlin version above.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
