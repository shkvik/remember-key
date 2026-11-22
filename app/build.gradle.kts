import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Own release key, kept outside git (signing/). Updates must be signed with the same key: installing a build with
// another key needs an uninstall first, and an uninstall destroys the Keystore keys, the hashes and every copy.
val signingProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
if (signingProps == null) logger.warn("RememberKey: signing/keystore.properties not found — the release APK will be UNSIGNED")

android {
    namespace = "local.rememberkey"
    compileSdk = 36

    defaultConfig {
        applicationId = "local.rememberkey"
        // setAccessibilityDataSensitive (hide the screen from non-accessibility-tool services) needs Android 14
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // FLAG_SECURE on: no screenshots, screen recording or recents preview
        buildConfigField("boolean", "SECURE_WINDOW", "true")
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // never the debug key: an APK signed with it could later only be replaced by uninstalling,
            // which destroys every key. Without signing/ the release APK stays unsigned and cannot be installed.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // the debug build lives next to the release one on the emulator,
            // and allows screenshots there so the screens can be checked; it never goes to the phone
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "SECURE_WINDOW", "false")
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
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/LICENSE.md", "META-INF/NOTICE.md")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
}
