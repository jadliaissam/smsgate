package com.jassam.smsgate.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Records the startup path into app/src/release/generated/baselineProfiles. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
    }
}

internal const val TARGET_PACKAGE = "com.jassam.smsgate"
