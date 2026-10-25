package com.jassam.smsgate.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSentFcmTokensTest {
    private var now = 1_800_000_000_000L
    private val tokens = PrefsSentFcmTokens(RuntimeEnvironment.getApplication()) { now }

    @Test
    fun remembersPerDeviceAndToken() {
        assertFalse(tokens.matches("dev-1", "t1"))
        tokens.remember("dev-1", "t1")
        assertTrue(tokens.matches("dev-1", "t1"))
        assertFalse(tokens.matches("dev-1", "t2"))
        assertFalse(tokens.matches("dev-2", "t1"))
    }

    @Test
    fun theMemoryExpiresSoAnUnchangedTokenIsSentAgain() {
        tokens.remember("dev-1", "t1")
        now += PrefsSentFcmTokens.MAX_AGE_MS - 1
        assertTrue(tokens.matches("dev-1", "t1"))
        now += 1
        assertFalse(tokens.matches("dev-1", "t1"))
    }

    @Test
    fun aClockMovedBackwardsAlsoResends() {
        tokens.remember("dev-1", "t1")
        now -= 1
        assertFalse(tokens.matches("dev-1", "t1"))
    }
}
