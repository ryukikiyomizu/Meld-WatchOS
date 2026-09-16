plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.meld.app.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        missingDimensionStrategy("variant", "foss")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.killProcessDelayMillis"] = "10000"
    }

    buildTypes {
        create("preview") {
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"

    // The generator runs as its own instrumentation APK.
    experimentalProperties["android.experimental.self-instrumenting"] = true

    testOptions {
        managedDevices {
            devices {
                create("pixel6Api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
            }
        }
    }
}

baselineProfile {
    managedDevices += "pixel6Api33"
    useConnectedDevices = false
    enableEmulatorDisplay = false
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
