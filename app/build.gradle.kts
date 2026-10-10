import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Supabase project for friends & nukes, from the (git-ignored) local.properties:
//   supabase.url=https://xxxx.supabase.co
//   supabase.key=<anon / publishable key>
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.shakelock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shakelock"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        buildConfigField("String", "SUPABASE_URL", "\"${localProps.getProperty("supabase.url", "")}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${localProps.getProperty("supabase.key", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Not debuggable (no pulling the app's data off a phone over USB), but signed with the same
            // key as the debug builds so updates still install over existing versions.
            // Back up ~/.android/debug.keystore: without it, there's no way to ship updates.
            signingConfig = signingConfigs.getByName("debug")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// supabase-kt's auth module wants androidx.browser 1.10 (compileSdk 36); we only sign in anonymously.
configurations.all {
    resolutionStrategy.force("androidx.browser:browser:1.8.0")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")

    // Friends & nukes
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.4.3")
}
