plugins {
    base
    // Android's Kotlin target needs AGP in the same parent plugin classloader.
    id("com.android.application") apply false
    kotlin("android") apply false
    kotlin("jvm") apply false
    kotlin("plugin.serialization") apply false
    kotlin("plugin.compose") apply false
    id("com.google.devtools.ksp") apply false
}
