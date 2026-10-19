plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.google.devtools.ksp")
}

val releaseSigningValues = listOf(
    "MDTERM_RELEASE_STORE_FILE",
    "MDTERM_RELEASE_STORE_PASSWORD",
    "MDTERM_RELEASE_KEY_ALIAS",
    "MDTERM_RELEASE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull == "true" ||
    releaseSigningValues.values.any { !it.isNullOrBlank() }

if (requireReleaseSigning) {
    val missing = releaseSigningValues.filterValues { it.isNullOrBlank() }.keys
    check(missing.isEmpty()) { "Missing release signing environment variables: ${missing.joinToString()}" }
    check(file(releaseSigningValues.getValue("MDTERM_RELEASE_STORE_FILE")!!).isFile) {
        "The release signing keystore does not exist"
    }
}

android {
    namespace = "dev.colormemo.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.colormemo.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (requireReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("MDTERM_RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("MDTERM_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("MDTERM_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("MDTERM_RELEASE_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            if (requireReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:html:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-tasklist:4.6.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
