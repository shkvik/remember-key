import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin: key text rules, schedule, PIN pauses, the vault file format and its crypto.
// Android-only parts (Keystore, fingerprint) come in through the HwMac interface, so all of it runs in JVM tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Argon2id (pure Java)
    api(libs.bouncycastle)
    // org.json is part of Android; on the JVM the tests bring the real library
    compileOnly(libs.org.json)
    testImplementation(libs.org.json)
    testImplementation(libs.junit)
}
