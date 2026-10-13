plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9 compiles Kotlin itself; only the Compose compiler plugin is added.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
