import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "local.rememberkey.diag"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.rememberkey.diag"
        // KeyInfo.getSecurityLevel and BiometricManager.getStrings need Android 12
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // only to time Argon2id on the phone (pure Java, no native code)
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
}
