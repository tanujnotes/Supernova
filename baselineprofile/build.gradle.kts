import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates the app's baseline profile (see BaselineProfileGenerator): run
// `./gradlew :app:generateBaselineProfile` with the test phone attached, and
// commit what lands in app/src/release/generated/baselineProfiles.
//
// WARNING: generating UNINSTALLS the launcher on that device and reinstalls
// it as a release-like variant. Every setting, the home layout, the auto
// backups and the widget bindings go with it, and the phone loses its
// default home. Export a backup from Settings > Backup (or archive
// files/datastore through run-as) before running this on a phone you use.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.supernova.launcher.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // The Samsung, not a Gradle-managed emulator: the profile should come
    // from the device the launcher is tuned on.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
