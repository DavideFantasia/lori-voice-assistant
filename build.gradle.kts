// Top-level build file: qui si dichiarano i plugin condivisi da tutti i
// moduli, senza applicarli (apply false) — vengono applicati nei build.gradle.kts
// dei singoli moduli (vedi app/build.gradle.kts).
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false

    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
