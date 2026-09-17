package com.meld.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Baseline Profile for app startup: launches the app and lets
 * it settle, recording every hot method along the way. The resulting
 * baseline-prof.txt is packaged into the APK and AOT-compiled at install
 * time, which is what makes first frames and list scrolling smooth.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() =
        baselineProfileRule.collect(
            packageName = "com.meld.app",
            includeInStartupProfile = true,
        ) {
            startActivityAndWait()
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            // Give startup (and the initial home-screen composition) time to run hot.
            device.wait(Until.hasObject(By.pkg("com.meld.app").depth(0)), 10_000)
            Thread.sleep(6_000)
        }
}
