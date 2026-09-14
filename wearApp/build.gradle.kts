import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Wear OS companion for the phone app.
 *
 * Two constraints drive this file:
 *  1. The `applicationId` (and the debug suffix) must match the phone app exactly, because
 *     Wearable Data Layer messages are scoped per package name. A mismatch means the watch
 *     silently never gets a reply.
 *  2. Release is intentionally left unsigned, mirroring `:app`. CI signs it with the repo
 *     keystore the same way the phone APK is signed.
 */

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

val applicationIdOverride = System.getenv("METROLIST_APPLICATION_ID")?.takeIf { it.isNotBlank() }
val appNameOverride = System.getenv("METROLIST_APP_NAME")?.takeIf { it.isNotBlank() }

// Keep the wear APK in lock-step with the phone APK instead of duplicating the numbers here.
val appBuildFile = rootProject.file("app/build.gradle.kts").takeIf { it.exists() }?.readText().orEmpty()
val wearVersionCode = Regex("versionCode = (\\d+)").find(appBuildFile)?.groupValues?.get(1)?.toIntOrNull() ?: 1
val wearVersionName = Regex("versionName = \"([^\"]+)\"").find(appBuildFile)?.groupValues?.get(1) ?: "0.0.0"

plugins {
    id("com.android.application")
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.metrolist.music.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = applicationIdOverride ?: "com.meld.app"
        // Wear OS 3 (API 28) and above. Galaxy Watch 7 ships Wear OS 5.
        minSdk = 28
        targetSdk = 36
        versionCode = wearVersionCode
        versionName = wearVersionName
        resValue("string", "app_name", appNameOverride ?: "Meld")

        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            if (applicationIdOverride == null) {
                applicationIdSuffix = ".debug"
            }
            isDebuggable = true
        }
    }

    // Phone <-> watch bridge protocol. Shared with `:app` from a single source of truth
    // (see wearbridge/), so the two APKs cannot drift apart on paths or payload keys.
    // AGP 9's built-in Kotlin support keeps its own source roots, so `java.srcDir` alone would
    // leave the package invisible to the compiler: register the root as a Kotlin one too.
    sourceSets {
        getByName("main") {
            java.srcDir(rootProject.file("wearbridge/src/main/kotlin"))
            kotlin.srcDir(rootProject.file("wearbridge/src/main/kotlin"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    lint {
        lintConfig = file("../lint.xml")
        warningsAsErrors = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.datastore)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)

    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.material3)
    implementation(libs.material.icons)

    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)

    // Phone <-> watch transport. `:app` carries the other half of the bridge.
    implementation(libs.play.services.wearable)

    debugImplementation(libs.compose.ui.tooling)
}
