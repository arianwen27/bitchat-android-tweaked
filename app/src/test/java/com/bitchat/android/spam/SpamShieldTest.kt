package com.bitchat.android.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of the local content spam shield, with synthetic messages only.
 *
 * Assertions describe observable outcomes (clean, flagged, hidden, muted), not internals. A candidate's
 * own timestamp drives window pruning, so [evaluate] passes the matching clock time: a replayed backlog
 * of old events is judged as it arrived, not as a live flood.
 */
class SpamShieldTest {
    private companion object {
        const val BASE = 1_700_000_000_000L
        const val SCOPE = "mesh"
    }

    private val balanced = SpamPolicy(mode = SpamMode.Balanced, autoMuteMinutes = 30)

    /** Sensitivity without auto-mute, so mute side effects do not mask the scoring behaviour under test. */
    private val noMute = SpamPolicy(mode = SpamMode.Balanced, autoMuteMinutes = 0)

    private fun shield() = SpamShield()

    @Test
    fun restoreTrustReturnsASenderToDefaultPoints() {
        val shield = shield()
        // Two content hides mute the sender under the second-strike rule.
        evaluate(shield, candidate("send 1 get 2 guaranteed profit"), balanced)
        evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 20_000), balanced)
        assertTrue(shield.isMuted("sender_a", "sender_a", BASE + 20_000))

        val dropped = shield.restoreTrust("sender_a", "sender_a")

        assertTrue(dropped > 0)
        assertFalse(shield.isMuted("sender_a", "sender_a", BASE + 20_000))
        // Strikes reset to zero, so the next content hide counts again instead of muting.
        val firstAfterRestore =
            evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 30_000), balanced)
        assertTrue(firstAfterRestore is ShieldVerdict.Hidden)
        assertEquals(0L, (firstAfterRestore as ShieldVerdict.Hidden).mutedUntilMs)
    }

    private fun candidate(
        content: String,
        sender: String = "sender_a",
        offsetMs: Long = 0,
        scope: String = SCOPE
    ) = SpamCandidate(
        senderKey = sender,
        senderLabel = sender,
        content = content,
        scopeKey = scope,
        timestampMs = BASE + offsetMs
    )

    private fun evaluate(shield: SpamShield, candidate: SpamCandidate, policy: SpamPolicy) =
        shield.evaluate(candidate, policy, candidate.timestampMs)

    @Test
    fun ordinaryMessageIsNotScored() {
        val verdict = evaluate(shield(), candidate("how is the mesh behaving today"), balanced)
        assertEquals(ShieldVerdict.Clean, verdict)
    }





    @Test
    fun blockedPhraseMatchesThroughInvisibleSeparators() {
        val shield = shield()
        val policy = noMute.copy(blocklist = listOf("free airdrop"))
        val verdict = evaluate(shield, candidate("fr\u200Bee ai\u200Brdrop now"), policy)
        val hidden = verdict as ShieldVerdict.Hidden
        assertTrue(hidden.signals.contains(SpamSignal.BlockedPhrase))
    }

    @Test
    fun forwardedChainIsFlagged() {
        val shield = shield()
        val verdict = evaluate(
            shield,
            candidate("Fwd: hello\nFwd: hello\nforward this to all your peers"),
            balanced
        )
        assertTrue(verdict is ShieldVerdict.Flagged)
        assertTrue((verdict as ShieldVerdict.Flagged).signals.contains(SpamSignal.ForwardChain))
    }

    @Test
    fun aSecondContentHideMutesTheSenderForAWhile() {
        val shield = shield()
        val first = evaluate(shield, candidate("send 1 get 2 guaranteed profit"), balanced)
        val second = evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 20_000), balanced)
        val later = evaluate(shield, candidate("a normal sentence from the same sender", offsetMs = 60_000), balanced)

        // First strike hides the message and does not silence the sender: a person who posted one
        // scam line once is not the same as a flood account.
        assertTrue(first is ShieldVerdict.Hidden)
        assertEquals(0L, (first as ShieldVerdict.Hidden).mutedUntilMs)
        assertTrue(second is ShieldVerdict.Hidden)
        assertTrue((second as ShieldVerdict.Hidden).mutedUntilMs > 0L)

        val muted = later as ShieldVerdict.Hidden
        assertTrue(muted.signals.contains(SpamSignal.MutedSender))
        assertTrue(shield.isMuted("sender_a", "sender_a", BASE + 60_000))
    }

    @Test
    fun muteExpiryAdmitsTheSenderAgain() {
        val shield = shield()
        val policy = balanced.copy(autoMuteMinutes = 1)
        evaluate(shield, candidate("send 1 get 2 guaranteed profit"), policy)
        evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 10_000), policy)
        val afterExpiry = evaluate(
            shield,
            candidate("a normal sentence from the same sender", offsetMs = 5 * 60_000L),
            policy
        )

        assertFalse(shield.isMuted("sender_a", "sender_a", BASE + 5 * 60_000L))
        assertEquals(ShieldVerdict.Clean, afterExpiry)
    }

    @Test
    fun zeroAutoMuteNeverMutes() {
        val shield = shield()
        val verdict = evaluate(shield, candidate("send 1 get 2 guaranteed profit"), noMute)
        assertTrue(verdict is ShieldVerdict.Hidden)
        assertEquals(0L, (verdict as ShieldVerdict.Hidden).mutedUntilMs)
        assertFalse(shield.isMuted("sender_a", "sender_a", BASE))
    }

    @Test
    fun disabledShieldObservesNothing() {
        val shield = shield()
        val policy = SpamPolicy(mode = SpamMode.Off, autoMuteMinutes = 30)
        repeat(5) { index ->
            val verdict = evaluate(
                shield,
                candidate("send 1 get 2 guaranteed profit https://example.com", offsetMs = index * 1_000L),
                policy
            )
            assertEquals(ShieldVerdict.Clean, verdict)
        }
        assertFalse(shield.isMuted("sender_a", "sender_a", BASE + 5_000))
    }

    @Test
    fun ownSystemAndUnreadableMessagesAreNeverScored() {
        val shield = shield()
        val policy = noMute.copy(blocklist = listOf("free airdrop"))
        listOf(
            candidate("free airdrop", offsetMs = 0).copy(fromSelf = true),
            candidate("free airdrop", offsetMs = 10_000).copy(system = true),
            candidate("free airdrop", offsetMs = 20_000).copy(inspectable = false),
            candidate("", offsetMs = 30_000),
            candidate("   ", offsetMs = 40_000)
        ).forEach { candidate ->
            assertEquals(ShieldVerdict.Clean, evaluate(shield, candidate, policy))
        }
    }

    @Test
    fun aggressiveModeHidesABlockedPhraseAndGentleModeOnlyFlagsAScamPhrase() {
        val shield = shield()
        val aggressive = SpamPolicy(
            mode = SpamMode.Aggressive,
            autoMuteMinutes = 30,
            blocklist = listOf("free airdrop")
        )
        val aggressiveVerdict = evaluate(shield, candidate("free airdrop claim your tokens today"), aggressive)
        // A user-saved phrase is decisive at every sensitivity, so aggressive mode hides it.
        assertTrue(aggressiveVerdict is ShieldVerdict.Hidden)

        val gentleVerdict = evaluate(
            shield,
            candidate("send 1 get 2 guaranteed profit"),
            SpamPolicy(mode = SpamMode.Gentle, autoMuteMinutes = 30)
        )
        // One scam phrase on a sender with no history is worth a marker at gentle sensitivity,
        // not a hidden message.
        assertTrue(gentleVerdict is ShieldVerdict.Flagged)
    }

    @Test
    fun channelsAreScoredSeparately() {
        val shield = shield()
        val global = evaluate(shield, candidate("channel message text"), balanced)
        val other = evaluate(
            shield,
            candidate("channel message text", offsetMs = 10_000, scope = "geo:synthetic"),
            balanced
        )

        assertEquals(ShieldVerdict.Clean, global)
        assertEquals(ShieldVerdict.Clean, other)
    }

    @Test
    fun weakProofOfWorkIsFlaggedOnlyInLocationChannels() {
        val shield = shield()
        val policy = balanced.copy(mode = SpamMode.Aggressive, requireProofOfWorkInGeohash = true)
        val withPow = evaluate(
            shield,
            candidate("fresh geohash sentence", scope = "geo:synthetic").copy(powDifficulty = 12),
            policy
        )
        val withoutPow = evaluate(
            shield,
            candidate("second fresh geohash sentence", scope = "geo:synthetic", offsetMs = 10_000),
            policy
        )
        val meshWithoutPow = evaluate(shield, candidate("mesh sentence without proof of work"), policy)

        assertEquals(ShieldVerdict.Clean, withPow)
        assertTrue(withoutPow is ShieldVerdict.Flagged)
        assertEquals(ShieldVerdict.Clean, meshWithoutPow)
    }

    @Test
    fun aQuietSenderReturnsToNeutralAfterDecay() {
        val shield = shield()
        val spam = evaluate(shield, candidate("guaranteed returns 100% profit"), noMute)
        assertTrue(spam is ShieldVerdict.Hidden)
        val later = evaluate(shield, candidate("a plain question about the mesh", offsetMs = 3 * 3_600_000L), noMute)
        assertEquals(ShieldVerdict.Clean, later)
    }

    @Test
    fun resetClearsWindowsAndMutes() {
        val shield = shield()
        evaluate(shield, candidate("send 1 get 2 guaranteed profit"), balanced)
        evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 20_000), balanced)
        assertTrue(shield.isMuted("sender_a", "sender_a", BASE))
        shield.reset()
        assertFalse(shield.isMuted("sender_a", "sender_a", BASE))
        // Reset drops windows, strikes and mutes, not the content rules: the same scam text is still
        // hidden, and a reset sender starts from zero strikes.
        val afterReset = evaluate(shield, candidate("send 1 get 2 guaranteed profit"), balanced)
        assertTrue(afterReset is ShieldVerdict.Hidden)
        assertEquals(0L, (afterReset as ShieldVerdict.Hidden).mutedUntilMs)
    }

    @Test
    fun aWordWallInOneMessageIsHiddenByRepetition() {
        val shield = shield()
        val wall = (1..30).joinToString("\n") { "sale sale sale sale sale" }

        val verdict = evaluate(shield, candidate(wall, sender = "flood_speaker", scope = "geo:synthetic"), noMute)

        assertTrue(verdict is ShieldVerdict.Hidden)
        assertTrue((verdict as ShieldVerdict.Hidden).signals.contains(SpamSignal.RepetitiveContent))
    }

    @Test
    fun aRotatingShoutFloodIsHiddenEvenWhenEveryWordDiffers() {
        val shield = shield()
        val aggressive = noMute.copy(mode = SpamMode.Aggressive)
        val words = listOf("ALPHA", "BETA", "GAMMA", "DELTA", "EPSILON", "ZETA", "ETHA", "THETA", "IOTA", "KAPPA")

        val verdicts = words.mapIndexed { index, word ->
            evaluate(shield, candidate(word, sender = "shout_bot", offsetMs = index * 2_000L, scope = "geo:synthetic"), aggressive)
        }

        // Exact-repeat detection finds nothing, because no two messages share content. The shape
        // (one shouted token, one after another) is what gets counted, and only once it sustains.
        assertEquals(ShieldVerdict.Clean, verdicts[0])
        assertTrue(verdicts[3] is ShieldVerdict.Flagged)
        assertTrue(verdicts[5] is ShieldVerdict.Hidden)
        assertTrue(verdicts[9] is ShieldVerdict.Hidden)
        assertTrue((verdicts[9] as ShieldVerdict.Hidden).signals.contains(SpamSignal.ShoutFlood))
    }

    @Test
    fun aLoneShoutedWordIsNotHidden() {
        val shield = shield()
        val shout = evaluate(shield, candidate("SALE", sender = "chatter"), noMute)
        val ordinary = evaluate(shield, candidate("ok", sender = "chatter", offsetMs = 4_000L), noMute)

        assertEquals(ShieldVerdict.Clean, shout)
        assertEquals(ShieldVerdict.Clean, ordinary)
    }

    @Test
    fun theRepeatedWordIsOfferedForBlocking() {
        val shield = shield()

        assertEquals("sale", shield.suggestionPhrase("sale sale sale end"))
        assertEquals("", shield.suggestionPhrase("a plain sentence about the mesh"))
    }
}
