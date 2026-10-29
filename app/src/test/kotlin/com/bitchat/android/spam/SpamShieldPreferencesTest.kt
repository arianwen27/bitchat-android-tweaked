package com.bitchat.android.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Settings are the user's own, so they must survive restarts. The persisted mutes round-trip
 * matters because a restart in the middle of a flood should not re-admit the sender.
 */
@RunWith(RobolectricTestRunner::class)
class SpamShieldPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SpamShieldPreferences.resetForTesting()
        SpamShieldPreferences.init(context)
    }

    @After
    fun tearDown() {
        SpamShieldPreferences.resetForTesting()
    }

    @Test
    fun sensitivityAndAutoMuteAreReadBackAfterReinitialisation() {
        SpamShieldPreferences.setEnabled(true)
        SpamShieldPreferences.setMode(SpamMode.Aggressive)
        SpamShieldPreferences.setAutoMuteMinutes(45)

        SpamShieldPreferences.init(context)

        assertTrue(SpamShieldPreferences.enabled.value)
        assertEquals(SpamMode.Aggressive, SpamShieldPreferences.mode.value)
        assertEquals(45, SpamShieldPreferences.autoMuteMinutes.value)
    }

    @Test
    fun switchesAreWrittenToSharedPreferencesNotJustToMemory() {
        SpamShieldPreferences.setFlagVisible(false)
        SpamShieldPreferences.setRequireProofOfWork(true)

        val store = context.getSharedPreferences("spam_shield_v1", Context.MODE_PRIVATE)
        assertFalse(store.getBoolean("flag_visible", true))
        assertTrue(store.getBoolean("require_pow_geohash", false))
    }

    /**
     * The shipped default is what makes live detection clear a sender's history: a purge runs only
     * when a mute begins, and contentStrike returns before it counts strikes when auto-mute is off.
     * A default of zero meant a fresh install detected spammers without ever purging their past
     * messages, so the default is asserted rather than left to the constant.
     */
    @Test
    fun autoMuteShipsAtTenMinutesSoDetectionCanPurge() {
        assertEquals(10, SpamShieldPreferences.autoMuteMinutes.value)
    }

    @Test
    fun autoMuteIsClampedToTheAllowedRange() {
        SpamShieldPreferences.setAutoMuteMinutes(5_000)
        assertEquals(720, SpamShieldPreferences.autoMuteMinutes.value)

        SpamShieldPreferences.setAutoMuteMinutes(-1)
        assertEquals(0, SpamShieldPreferences.autoMuteMinutes.value)
    }

    @Test
    fun masterSwitchWinsOverTheChosenSensitivity() {
        SpamShieldPreferences.setMode(SpamMode.Aggressive)
        SpamShieldPreferences.setEnabled(false)
        assertEquals(SpamMode.Off, SpamShieldPreferences.policy().mode)

        SpamShieldPreferences.setEnabled(true)
        assertEquals(SpamMode.Aggressive, SpamShieldPreferences.policy().mode)
    }

    @Test
    fun blocklistIgnoresDuplicatesBlanksAndIsCaseInsensitive() {
        assertTrue(SpamShieldPreferences.addBlockedPhrase("send coins to win bitcoin"))
        assertFalse(SpamShieldPreferences.addBlockedPhrase("SEND  COINS TO WIN  BITCOIN"))
        assertFalse(SpamShieldPreferences.addBlockedPhrase("   "))
        assertEquals(1, SpamShieldPreferences.blocklist.value.size)

        SpamShieldPreferences.removeBlockedPhrase("Send Coins To Win Bitcoin")
        assertTrue(SpamShieldPreferences.blocklist.value.isEmpty())
    }

    @Test
    fun aBlockedPhraseSurvivesReinitialisation() {
        SpamShieldPreferences.addBlockedPhrase("free airdrop now")
        SpamShieldPreferences.init(context)
        assertEquals(listOf("free airdrop now"), SpamShieldPreferences.blocklist.value)
    }

    @Test
    fun mutesRoundTripThroughSharedPreferences() {
        SpamShieldPreferences.saveMutes(mapOf("sender-a" to 1_700_000_000_000L))
        assertEquals(mapOf("sender-a" to 1_700_000_000_000L), SpamShieldPreferences.loadMutes())

        SpamShieldPreferences.clearMutes()
        assertTrue(SpamShieldPreferences.loadMutes().isEmpty())
    }

    @Test
    fun settingsAreUntouchedByPanicClear() {
        SpamShieldPreferences.setEnabled(true)
        SpamShieldPreferences.clearMutes()

        SpamShieldPreferences.init(context)
        assertTrue(SpamShieldPreferences.enabled.value)
        assertTrue(SpamShieldPreferences.loadMutes().isEmpty())
    }
}
