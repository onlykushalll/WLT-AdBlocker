// Top-level build file. Plugins declared here with `apply false` so they
// can be applied in the module-level build.gradle.kts with version info
// inherited from this file (single source of truth for plugin versions).
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0+ requires the dedicated Compose compiler plugin. In older
    // Kotlin versions (1.9.x) the Compose compiler was wired via
    // `composeOptions.kotlinCompilerExtensionVersion`; in 2.0+ it's a
    // separate Gradle plugin. We still keep the composeOptions line in
    // app/build.gradle.kts for documentation — it's a no-op when the
    // plugin is applied.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
