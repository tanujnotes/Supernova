import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.baselineprofile)
}

// Release signing: keystore.properties at the project root (gitignored) with
// storeFile, storePassword, keyAlias and keyPassword. Every tester APK, GitHub
// release and Play upload must carry this one key, or installs can't update.
// Without the file, release builds come out unsigned.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.supernova.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.supernova.launcher"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "v0.1.0"
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // A separate app beside the release one, so the dev build and the
        // APK testers get can sit on one phone. The namespace (and so every
        // class name and the settings alias) stays com.supernova.launcher.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // No Google-encrypted dependency report in the APK signing block: F-Droid's
    // scanner rejects it, and it breaks reproducible-build verification.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Everything lint found on 2026-09-24 is parked in the baseline; anything
        // new fails the build. Regenerate by deleting the file and running lintDebug.
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // "A newer version is available" isn't a defect in this code, and with
        // warningsAsErrors every upstream release would break the build.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9's built-in Kotlin: the compiler is the plugin's own, and its
    // options live under android {} rather than in a kotlin {} block.
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

}

detekt {
    // Static analysis alongside lint: the default rule set, with everything it
    // found on 2026-09-24 parked in the baseline so only new findings fail.
    // Refresh with :app:detektBaseline.
    buildUponDefaultConfig = true
    baseline = file("detekt-baseline.xml")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.viewpager2)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.gridlayout)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)

    // Installs the baseline profile on devices that take one (API 28+), so a
    // fresh install runs pre-compiled code for the start-up path the
    // :baselineprofile module recorded.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    // Debug builds only: watches for leaked activities, views and view models.
    debugImplementation(libs.leakcanary)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlin.reflect)
}
