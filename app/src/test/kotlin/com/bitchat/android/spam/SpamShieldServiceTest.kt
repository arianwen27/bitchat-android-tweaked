package com.bitchat.android.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * Gate behavior at the admission point the whole app funnels through.
 *
 * Synthetic senders, channels and text only. The gate must never hide a private, encrypted, system
 * or self-sent message, must memoize a verdict so relay copies cannot inflate counts, and must let
 * the user reveal a message they trust.
 */
@RunWith(RobolectricTestRunner::class)
class SpamShieldServiceTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        SpamShieldService.reset()
        SpamShieldPreferences.resetForTesting()
        SpamShieldService.init(context)
        SpamShieldPreferences.setEnabled(true)
        SpamShieldPreferences.setMode(SpamMode.Balanced)
        SpamShieldPreferences.setAutoMuteMinutes(0)
    }

    @Test
    fun aCleanPublicMessageIsAdmittedOnceAndMemoized() {
        val message = publicMessage("id-clean", "alice", "hello mesh, channel is up")

        assertTrue(SpamShieldService.admit(message, "me"))
        assertTrue(SpamShieldService.admit(message, "me"))
        assertEquals(SpamVerdict.Clean, SpamShieldService.verdictFor("id-clean"))
        assertTrue(SpamShieldService.hidden.value.isEmpty())
    }

    @Test
    fun aSecondCopyOfTheSameTextFromTheSameSenderIsHidden() {
        val first = publicMessage("id-a", "alice", "free airdrop claim your tokens today")
        val second = publicMessage("id-b", "alice", "free airdrop claim your tokens today")

        val firstVerdict = SpamShieldService.admit(first, "me")
        assertTrue(firstVerdict)
        assertFalse(SpamShieldService.admit(second, "me"))

        assertEquals(1, SpamShieldService.hidden.value.size)
        assertEquals("id-b", SpamShieldService.hidden.value.first().message.id)
    }

    @Test
    fun privateEncryptedSystemAndSelfMessagesAreNeverGated() {
        val text = "free airdrop claim your tokens today"
        // Repeat the same text several times so only a shield that inspected it could hide it.
        repeat(6) { index ->
            val base = publicMessage("gate-$index", "alice", text)
            val privateMessage = base.copy(isPrivate = true, senderPeerID = "peer-a")
            val encrypted = base.copy(isEncrypted = true)
            val system = base.copy(sender = "system")
            val self = base.copy(sender = "me")
            val media = base.copy(type = BitchatMessageType.Image)

            assertTrue(SpamShieldService.admit(privateMessage, "me"))
            assertTrue(SpamShieldService.admit(encrypted, "me"))
            assertTrue(SpamShieldService.admit(system, "me"))
            assertTrue(SpamShieldService.admit(self, "me"))
            assertTrue(SpamShieldService.admit(media, "me"))
        }
        assertTrue(SpamShieldService.hidden.value.isEmpty())
    }

    @Test
    fun aDisabledShieldAdmitsEverything() {
        SpamShieldPreferences.setEnabled(false)
        repeat(5) { index ->
            assertTrue(SpamShieldService.admit(publicMessage("off-$index", "alice", "buy followers cheap now"), "me"))
        }
        assertTrue(SpamShieldService.hidden.value.isEmpty())
    }

    @Test
    fun channelsAreGatedIndependentlyOfMesh() {
        val mesh = publicMessage("chan-mesh", "alice", "link farm http://a http://b http://c http://d http://e")
        assertTrue(SpamShieldService.admit(mesh, "me"))
        // The same text in a geohash channel is a new scope, so the cross-sender window stays empty.
        val geo = mesh.copy(id = "chan-geo", sender = "bob", channel = "geo:synthetic")
        assertTrue(SpamShieldService.admit(geo, "me"))
        assertTrue(SpamShieldService.hidden.value.isEmpty())
    }

    @Test
    fun aRevealedMessageIsAdmittedAgainAndRemovedFromTheLedger() {
        val text = "ping for the relay"
        SpamShieldService.admit(publicMessage("rev-1", "alice", text), "me")
        SpamShieldService.admit(publicMessage("rev-2", "alice", text), "me")
        SpamShieldService.admit(publicMessage("rev-3", "alice", text), "me")
        assertEquals(1, SpamShieldService.hidden.value.size)

        val revealed = SpamShieldService.reveal("rev-3")
        assertNotNull(revealed)
        assertEquals(0, SpamShieldService.hidden.value.size)
        assertTrue(SpamShieldService.admit(revealed!!, "me"))
        // Revealing one message does not whitelist the sender, so a fresh copy is hidden again.
        assertFalse(SpamShieldService.admit(publicMessage("rev-4", "alice", text), "me"))
    }

    @Test
    fun revealingAScopeClearsThatScopeOnly() {
        val text = "check the new channel"
        SpamShieldService.admit(publicMessage("scope-1", "alice", text), "me")
        SpamShieldService.admit(publicMessage("scope-2", "alice", text), "me")
        SpamShieldService.admit(publicMessage("scope-3", "alice", text), "me")
        SpamShieldService.admit(geoMessage("scope-4", "bob", text), "me")
        SpamShieldService.admit(geoMessage("scope-5", "bob", text), "me")
        SpamShieldService.admit(geoMessage("scope-6", "bob", text), "me")

        val meshHidden = SpamShieldService.hidden.value.count { it.scopeKey == "mesh" }
        val geoHidden = SpamShieldService.hidden.value.count { it.scopeKey == "geo:synthetic" }
        assertEquals(2, meshHidden + geoHidden)

        val revealed = SpamShieldService.revealScope("geo:synthetic")
        assertEquals(geoHidden, revealed.size)
        assertEquals(0, SpamShieldService.hidden.value.count { it.scopeKey == "geo:synthetic" })
        assertEquals(1, SpamShieldService.hidden.value.count { it.scopeKey == "mesh" })
    }

    @Test
    fun aMutedSenderIsHiddenWithoutReScoring() {
        SpamShieldService.muteSender("alice", "alice", 30)
        val muted = publicMessage("muted-1", "alice", "hello there")
        assertFalse(SpamShieldService.admit(muted, "me"))
        assertEquals(SpamSignal.MutedSender, SpamShieldService.hidden.value.first().signals.first())
    }

    @Test
    fun aPersistedMuteSurvivesReinitialisation() {
        SpamShieldService.muteSender("alice", "alice", 30)
        SpamShieldService.init(context)
        assertFalse(SpamShieldService.admit(publicMessage("muted-2", "alice", "still talking"), "me"))
    }

    @Test
    fun panicResetClearsLedgerMutesAndFlags() {
        SpamShieldService.muteSender("alice", "alice", 30)
        val text = "hello there"
        SpamShieldService.admit(publicMessage("reset-1", "bob", text), "me")
        SpamShieldService.admit(publicMessage("reset-2", "bob", text), "me")
        SpamShieldService.admit(publicMessage("reset-3", "bob", text), "me")
        assertTrue(SpamShieldService.hidden.value.isNotEmpty())

        SpamShieldService.reset()

        assertTrue(SpamShieldService.hidden.value.isEmpty())
        assertTrue(SpamShieldService.flaggedIds.value.isEmpty())
        // The mute is gone with the ledger, so a sender the user never blocked is readable again.
        assertTrue(SpamShieldService.admit(publicMessage("reset-4", "alice", "hello again"), "me"))
    }

    @Test
    fun aFlaggedMessageStaysAdmittedAndIsMarked() {
        val flagged = publicMessage("flag-1", "alice", "limited airdrop for verified users, join fast")
        val admitted = SpamShieldService.admit(flagged, "me")
        assertTrue(admitted)
        val verdict = SpamShieldService.verdictFor("flag-1")
        if (verdict is SpamVerdict.Flagged) {
            assertTrue(SpamShieldService.flaggedIds.value.contains("flag-1"))
        }
    }

    @Test
    fun markSpammerSilencesTheSenderAndRestoreTrustBringsThemBack() {
        assertTrue(SpamShieldService.admit(geoMessage("mark-1", "carol", "hello from carol"), "me"))
        SpamShieldService.markSpammer("carol", "carol")
        assertFalse(SpamShieldService.admit(geoMessage("mark-2", "carol", "just chatting"), "me"))
        assertTrue(SpamShieldService.hidden.value.any { it.senderKey == "carol" })

        SpamShieldService.restoreSender("carol", "carol")

        assertTrue(SpamShieldService.admit(geoMessage("mark-3", "carol", "hello again"), "me"))
        assertTrue(SpamShieldService.hidden.value.none { it.senderKey == "carol" })
    }

    @Test
    fun restoringTrustIsNotAWhitelist() {
        // The scam text is hidden by content rules on its first arrival, and the sender is judged fine.
        assertFalse(SpamShieldService.admit(geoMessage("wl-1", "dave", "send 1 get 2 guaranteed profit"), "me"))
        SpamShieldService.restoreSender("dave", "dave")

        // Restoring trust admits their ordinary chatter, which a mute would have hidden.
        assertTrue(SpamShieldService.admit(geoMessage("wl-2", "dave", "hello from dave"), "me"))
        // Content rules survive the restore: the same scam text is hidden by the same rules.
        assertFalse(SpamShieldService.admit(geoMessage("wl-3", "dave", "send 1 get 2 guaranteed profit"), "me"))
    }

    private fun publicMessage(id: String, sender: String, content: String) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        timestamp = Date(System.currentTimeMillis())
    )

    private fun geoMessage(id: String, sender: String, content: String) =
        publicMessage(id, sender, content).copy(channel = "geo:synthetic")
}
