package com.bitchat.android.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
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
        assertEquals(ShieldVerdict.Clean, SpamShieldService.verdictFor("id-clean"))
        assertTrue(SpamShieldService.hidden.value.isEmpty())
    }

    @Test
    fun aWordWallIsHiddenAndRecordedInTheLedger() {
        val first = publicMessage("id-a", "alice", "a plain sentence about the mesh")
        // A word pasted inside one message is the shape a flood takes when it is posted as a single
        // line, so it is scored by repetition and by the scam phrase, not by comparison with history.
        val wall = publicMessage("id-b", "alice", "free airdrop sale sale sale sale sale sale")

        assertTrue(SpamShieldService.admit(first, "me"))
        assertFalse(SpamShieldService.admit(wall, "me"))

        assertEquals(1, SpamShieldService.hidden.value.size)
        assertEquals("id-b", SpamShieldService.hidden.value.first().message.id)
        assertTrue(SpamShieldService.hidden.value.first().signals.contains(SpamSignal.RepetitiveContent))
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
        val scam = "send 1 get 2 guaranteed profit"
        assertTrue(SpamShieldService.admit(publicMessage("rev-1", "alice", "hello from alice"), "me"))
        assertFalse(SpamShieldService.admit(publicMessage("rev-2", "alice", scam), "me"))
        assertEquals(1, SpamShieldService.hidden.value.size)

        val revealed = SpamShieldService.reveal("rev-2")
        assertNotNull(revealed)
        assertEquals(0, SpamShieldService.hidden.value.size)
        assertTrue(SpamShieldService.admit(revealed!!, "me"))
        // Revealing one message does not whitelist the sender, so the scam text is hidden again.
        assertFalse(SpamShieldService.admit(publicMessage("rev-3", "alice", scam), "me"))
    }

    @Test
    fun revealingAScopeClearsThatScopeOnly() {
        val scam = "send 1 get 2 guaranteed profit"
        repeat(3) { index -> SpamShieldService.admit(publicMessage("scope-$index", "alice", scam), "me") }
        repeat(3) { index -> SpamShieldService.admit(geoMessage("scope-geo-$index", "bob", scam), "me") }

        val meshHidden = SpamShieldService.hidden.value.count { it.scopeKey == "mesh" }
        val geoHidden = SpamShieldService.hidden.value.count { it.scopeKey == "geo:synthetic" }
        assertEquals(3, meshHidden)
        assertEquals(3, geoHidden)

        val revealed = SpamShieldService.revealScope("geo:synthetic")
        assertEquals(3, revealed.size)
        assertEquals(0, SpamShieldService.hidden.value.count { it.scopeKey == "geo:synthetic" })
        assertEquals(3, SpamShieldService.hidden.value.count { it.scopeKey == "mesh" })
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
        val scam = "send 1 get 2 guaranteed profit"
        repeat(3) { index -> SpamShieldService.admit(publicMessage("reset-$index", "bob", scam), "me") }
        assertTrue(SpamShieldService.hidden.value.isNotEmpty())

        SpamShieldService.reset()

        assertTrue(SpamShieldService.hidden.value.isEmpty())
        assertTrue(SpamShieldService.flaggedIds.value.isEmpty())
        // The mute is gone with the ledger, so a sender the user never blocked is readable again.
        assertTrue(SpamShieldService.admit(publicMessage("reset-end", "alice", "hello again"), "me"))
    }

    @Test
    fun aFlaggedMessageStaysAdmittedAndIsMarked() {
        val flagged = publicMessage("flag-1", "alice", "limited airdrop for verified users, join fast")
        val admitted = SpamShieldService.admit(flagged, "me")
        assertTrue(admitted)
        val verdict = SpamShieldService.verdictFor("flag-1")
        if (verdict is ShieldVerdict.Flagged) {
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

    @Test
    fun theHiddenCountReflectsCurrentlyHiddenMessages() {
        SpamShieldPreferences.addBlockedPhrase("blockme")

        for (index in 0 until 5) {
            val message = publicMessage("id-cap-$index", "flood_speaker", "blockme line $index")
            assertFalse(SpamShieldService.admit(message, "me"))
        }

        // The count is how many messages are hidden right now, so reveal, restore and purge make
        // it go down, and the bar cannot drift away from what the review sheet shows.
        assertEquals(5, SpamShieldService.hidden.value.size)
        SpamShieldService.reveal("id-cap-0")
        SpamShieldService.reveal("id-cap-1")
        assertEquals(3, SpamShieldService.hidden.value.size)
    }

    @Test
    fun aRepeatedWordInsideAMessageIsOfferedAsABlockWord() {
        val message = geoMessage("wall-1", "flood_speaker", "sale sale sale sale")

        assertEquals("sale", SpamShieldService.suggestion(message))
        assertEquals("", SpamShieldService.suggestion(geoMessage("plain-1", "carol", "a plain question about the mesh")))
    }

    @Test
    fun mutingASenderClearsTheirVisibleMessagesAndRestoreBringsThemBack() {
        val first = publicMessage("pg-1", "bot_p", "bonjour tout le monde").copy(channel = "geo:pg1")
        val second = publicMessage("pg-2", "bot_p", "oui je suis ici").copy(channel = "geo:pg1")
        AppStateStore.addChannelMessage("geo:pg1", first)
        AppStateStore.addChannelMessage("geo:pg1", second)
        assertEquals(2, AppStateStore.channelMessages.value.getValue("geo:pg1").size)

        // A mute stops future messages. Purging is what clears the spam already on screen, which is
        // what a user expects when they judge a sender to be a spammer.
        SpamShieldService.muteSender("bot_p", "bot_p", 30)
        assertEquals(0, AppStateStore.channelMessages.value.getValue("geo:pg1").size)
        assertFalse(SpamShieldService.admit(publicMessage("pg-3", "bot_p", "new line from the bot"), "me"))

        SpamShieldService.restoreSender("bot_p", "bot_p")
        val restored = AppStateStore.channelMessages.value.getValue("geo:pg1")
        assertEquals(2, restored.size)
        assertEquals("pg-1", restored[0].id)
        assertTrue(SpamShieldService.admit(second, "me"))
    }

    @Test
    fun purgeIsKeyedToTheSenderSoOrdinaryChatterSurvives() {
        val kept = publicMessage("pg-4", "innocent", "a plain question about the mesh").copy(channel = "geo:pg2")
        // Scam text is hidden by content rules in Balanced mode, so the mute follows a real hide.
        val spam = publicMessage("pg-5", "bot_q", "send 1 get 2 guaranteed profit").copy(channel = "geo:pg2")
        AppStateStore.addChannelMessage("geo:pg2", kept)
        assertFalse(SpamShieldService.admit(spam, "me"))

        SpamShieldService.muteSender("bot_q", "bot_q", 30)
        val channel = AppStateStore.channelMessages.value.getValue("geo:pg2")
        assertEquals(1, channel.size)
        assertEquals("pg-4", channel[0].id)

        SpamShieldService.restoreSender("bot_q", "bot_q")
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:pg2").size)
    }

    @Test
    fun aRateFilterHideAppliesAutoMuteAndPurgeOnSecondStrike() {
        SpamShieldPreferences.setAutoMuteMinutes(10)
        val visible = publicMessage("st-1", "bot_r", "bonjour tout le monde").copy(channel = "geo:st1")
        AppStateStore.addChannelMessage("geo:st1", visible)
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:st1").size)

        // Two hides recorded from the upstream rate filter. Auto-mute is second-strike, so the
        // sender is muted and their visible messages are cleared only on the second one.
        SpamShieldService.recordFilterHide(publicMessage("st-2", "bot_r", "free coins click here"),
            SpamVerdict.REPEATED_MESSAGE)
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:st1").size)
        SpamShieldService.recordFilterHide(publicMessage("st-3", "bot_r", "free coins click here"),
            SpamVerdict.REPEATED_MESSAGE)

        assertEquals(0, AppStateStore.channelMessages.value.getValue("geo:st1").size)
        assertFalse(SpamShieldService.admit(publicMessage("st-4", "bot_r", "a plain question"), "me"))
        SpamShieldService.restoreSender("bot_r", "bot_r")
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:st1").size)
        assertTrue(SpamShieldService.admit(publicMessage("st-5", "bot_r", "a plain question"), "me"))
    }

    @Test
    fun markingASpammerLeavesARowSoTrustCanBeRestored() {
        val visible = publicMessage("pt-1", "bot_p", "hello everyone").copy(channel = "geo:pt1")
        AppStateStore.addChannelMessage("geo:pt1", visible)
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:pt1").size)

        // Purging a sender must not make them unreachable: the review sheet has to keep a row,
        // otherwise un-marking requires waiting for the sender to post something new.
        SpamShieldService.markSpammer("bot_p", "bot_p")
        assertTrue(SpamShieldService.hidden.value.any { it.senderKey == "bot_p" })
        assertEquals(0, AppStateStore.channelMessages.value.getValue("geo:pt1").size)

        SpamShieldService.restoreSender("bot_p", "bot_p")
        assertEquals(1, AppStateStore.channelMessages.value.getValue("geo:pt1").size)
        assertTrue(SpamShieldService.admit(publicMessage("pt-2", "bot_p", "a plain question"), "me"))
    }

    @Test
    fun restoredMessagesGoBackToTheSlotsTheyOccupied() {
        val channel = "geo:pos"
        val base = System.currentTimeMillis()
        fun stamped(id: String, sender: String, content: String, offsetMs: Long) =
            publicMessage(id, sender, content).copy(channel = channel, timestamp = Date(base + offsetMs))

        listOf(
            stamped("pos-1", "p1", "first from one", 0L),
            stamped("pos-2", "p2", "first from two", 1_000L),
            stamped("pos-3", "p3", "first from three", 2_000L),
            stamped("pos-4", "p1", "second from one", 3_000L),
            stamped("pos-5", "p3", "second from three", 4_000L),
            stamped("pos-6", "p2", "second from two", 5_000L),
        ).forEach { AppStateStore.addChannelMessage(channel, it) }
        assertEquals(6, AppStateStore.channelMessages.value.getValue(channel).size)

        // Purging a sender must leave gaps where their messages were, not at the end.
        SpamShieldService.markSpammer("p2", "p2")
        assertEquals(
            listOf("pos-1", "pos-3", "pos-4", "pos-5"),
            AppStateStore.channelMessages.value.getValue(channel).map { it.id },
        )

        // Restoring must put them back in those slots, interleaved, not in one clump at the newest.
        SpamShieldService.restoreSender("p2", "p2")
        assertEquals(
            listOf("pos-1", "pos-2", "pos-3", "pos-4", "pos-5", "pos-6"),
            AppStateStore.channelMessages.value.getValue(channel).map { it.id },
        )
    }

    @Test
    fun sheetLabelsSayWhichEngineHiddenTheMessage() {
        val contentHide = geoMessage("sr-1", "flood_speaker", "send 1 get 2 guaranteed profit")
        assertFalse(SpamShieldService.admit(contentHide, "me"))
        assertEquals(SpamSource.ContentRules, SpamShieldService.hidden.value.first().source)

        val rateHide = publicMessage("sr-2", "bot_src", "free coins click here").copy(channel = "geo:sr")
        SpamShieldService.recordFilterHide(rateHide, SpamVerdict.REPEATED_MESSAGE)
        assertEquals(SpamSource.Official, SpamShieldService.hidden.value.first().source)

        val visible = publicMessage("sr-3", "bot_src2", "hello everyone").copy(channel = "geo:sr2")
        AppStateStore.addChannelMessage("geo:sr2", visible)
        SpamShieldService.markSpammer("bot_src2", "bot_src2")
        assertEquals(SpamSource.UserJudgement, SpamShieldService.hidden.value.first().source)
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
