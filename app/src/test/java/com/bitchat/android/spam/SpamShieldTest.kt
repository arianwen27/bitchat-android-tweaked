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
        assertTrue(firstAfterRestore is SpamVerdict.Hidden)
        assertEquals(0L, (firstAfterRestore as SpamVerdict.Hidden).mutedUntilMs)
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
        assertEquals(SpamVerdict.Clean, verdict)
    }

    @Test
    fun firstRepeatIsFlaggedAndSecondRepeatIsHidden() {
        val shield = shield()
        val first = evaluate(shield, candidate("join my site for free chips"), noMute)
        val second = evaluate(shield, candidate("join my site for free chips", offsetMs = 20_000), noMute)
        val third = evaluate(shield, candidate("join my site for free chips", offsetMs = 40_000), noMute)

        assertEquals(SpamVerdict.Clean, first)
        assertTrue(second is SpamVerdict.Flagged)
        assertTrue(third is SpamVerdict.Hidden)
        assertTrue((second as SpamVerdict.Flagged).signals.contains(SpamSignal.ExactRepeat))
    }

    @Test
    fun coordinatedCopiesFromDifferentSendersAreHiddenAtOnce() {
        val shield = shield()
        val original = evaluate(shield, candidate("earn money fast with one click"), noMute)
        val copy = evaluate(
            shield,
            candidate("earn money fast with one click", sender = "sender_b", offsetMs = 60_000),
            noMute
        )

        assertEquals(SpamVerdict.Clean, original)
        val hidden = copy as SpamVerdict.Hidden
        assertTrue(hidden.signals.contains(SpamSignal.CrossSenderRepeat))
    }

    @Test
    fun aBusyChannelKeepsItsMessagesAndOnlyAFloodIsFlagged() {
        val shield = shield()
        val chatter = (0 until 9).map { index ->
            evaluate(shield, candidate("distinct line number $index", offsetMs = index * 2_000L), noMute)
        }
        // Rate alone is never decisive. Nine distinct lines in eighteen seconds is a lively channel,
        // and every one of them stays visible.
        assertTrue(chatter.all { it == SpamVerdict.Clean })

        val floodShield = shield()
        val flood = (0 until 16).map { index ->
            evaluate(floodShield, candidate("flood line number $index", offsetMs = index * 1_000L), noMute)
        }
        // Sixteen lines in sixteen seconds is a flood: the rate signal plus the channel load crosses
        // the flag threshold on the last one, and the sender's reputation is what pushes later
        // messages past the hide threshold.
        assertTrue(flood.take(15).all { it == SpamVerdict.Clean })
        assertTrue(flood[15] is SpamVerdict.Flagged)
    }

    @Test
    fun aBurstOfRepeatsIsHiddenByContentNotReputation() {
        val shield = shield()
        (0 until 8).forEach { index ->
            evaluate(shield, candidate("distinct line number $index", offsetMs = index * 2_000L), noMute)
        }
        val repeated = evaluate(shield, candidate("distinct line number 0", offsetMs = 18_000), noMute)
        val hidden = repeated as SpamVerdict.Hidden
        assertTrue(hidden.signals.contains(SpamSignal.ExactRepeat))
        assertTrue(hidden.signals.contains(SpamSignal.SenderBurst))
    }

    @Test
    fun blockedPhraseMatchesThroughInvisibleSeparators() {
        val shield = shield()
        val policy = noMute.copy(blocklist = listOf("free airdrop"))
        val verdict = evaluate(shield, candidate("fr\u200Bee ai\u200Brdrop now"), policy)
        val hidden = verdict as SpamVerdict.Hidden
        assertTrue(hidden.signals.contains(SpamSignal.BlockedPhrase))
    }

    @Test
    fun manyLinksAreFlagged() {
        val shield = shield()
        val verdict = evaluate(
            shield,
            candidate("read https://example.com/first and http://example.org/second today"),
            balanced
        )
        assertTrue(verdict is SpamVerdict.Flagged)
        assertTrue((verdict as SpamVerdict.Flagged).signals.contains(SpamSignal.LinkFlood))
    }

    @Test
    fun forwardedChainIsFlagged() {
        val shield = shield()
        val verdict = evaluate(
            shield,
            candidate("Fwd: hello\nFwd: hello\nforward this to all your peers"),
            balanced
        )
        assertTrue(verdict is SpamVerdict.Flagged)
        assertTrue((verdict as SpamVerdict.Flagged).signals.contains(SpamSignal.ForwardChain))
    }

    @Test
    fun aSecondContentHideMutesTheSenderForAWhile() {
        val shield = shield()
        val first = evaluate(shield, candidate("send 1 get 2 guaranteed profit"), balanced)
        val second = evaluate(shield, candidate("send 1 get 2 guaranteed profit", offsetMs = 20_000), balanced)
        val later = evaluate(shield, candidate("a normal sentence from the same sender", offsetMs = 60_000), balanced)

        // First strike hides the message and does not silence the sender: a person who posted one
        // scam line once is not the same as a flood account.
        assertTrue(first is SpamVerdict.Hidden)
        assertEquals(0L, (first as SpamVerdict.Hidden).mutedUntilMs)
        assertTrue(second is SpamVerdict.Hidden)
        assertTrue((second as SpamVerdict.Hidden).mutedUntilMs > 0L)

        val muted = later as SpamVerdict.Hidden
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
        assertEquals(SpamVerdict.Clean, afterExpiry)
    }

    @Test
    fun zeroAutoMuteNeverMutes() {
        val shield = shield()
        val verdict = evaluate(shield, candidate("send 1 get 2 guaranteed profit"), noMute)
        assertTrue(verdict is SpamVerdict.Hidden)
        assertEquals(0L, (verdict as SpamVerdict.Hidden).mutedUntilMs)
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
            assertEquals(SpamVerdict.Clean, verdict)
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
            assertEquals(SpamVerdict.Clean, evaluate(shield, candidate, policy))
        }
    }

    @Test
    fun aggressiveModeHidesABlockedPhraseAndGentleModeOnlyFlagsAFirstRepeat() {
        val aggressive = SpamShield()
        val aggressivePolicy = SpamPolicy(
            mode = SpamMode.Aggressive,
            blocklist = listOf("free airdrop"),
            autoMuteMinutes = 0
        )
        val aggressiveVerdict = aggressive.evaluate(
            candidate("free\u200B airdrop inside a long sentence"),
            aggressivePolicy,
            BASE
        )
        assertTrue(aggressiveVerdict is SpamVerdict.Hidden)

        val gentle = SpamShield()
        val gentlePolicy = SpamPolicy(mode = SpamMode.Gentle, autoMuteMinutes = 0)
        gentle.evaluate(candidate("repeat this text"), gentlePolicy, BASE)
        val gentleVerdict = gentle.evaluate(
            candidate("repeat this text", offsetMs = 5_000),
            gentlePolicy,
            BASE + 5_000
        )
        assertTrue(gentleVerdict is SpamVerdict.Flagged)
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

        assertEquals(SpamVerdict.Clean, global)
        assertEquals(SpamVerdict.Clean, other)
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

        assertEquals(SpamVerdict.Clean, withPow)
        assertTrue(withoutPow is SpamVerdict.Flagged)
        assertEquals(SpamVerdict.Clean, meshWithoutPow)
    }

    @Test
    fun aQuietSenderReturnsToNeutralAfterDecay() {
        val shield = shield()
        val spam = evaluate(shield, candidate("guaranteed returns 100% profit"), noMute)
        assertTrue(spam is SpamVerdict.Hidden)
        val later = evaluate(shield, candidate("a plain question about the mesh", offsetMs = 3 * 3_600_000L), noMute)
        assertEquals(SpamVerdict.Clean, later)
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
        assertTrue(afterReset is SpamVerdict.Hidden)
        assertEquals(0L, (afterReset as SpamVerdict.Hidden).mutedUntilMs)
    }

    @Test
    fun aWordWallInOneMessageIsHiddenByRepetition() {
        val shield = shield()
        val wall = (1..30).joinToString("\n") { "negro negro negro negro negro" }

        val verdict = evaluate(shield, candidate(wall, sender = "flood_speaker", scope = "geo:synthetic"), noMute)

        assertTrue(verdict is SpamVerdict.Hidden)
        assertTrue((verdict as SpamVerdict.Hidden).signals.contains(SpamSignal.RepetitiveContent))
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
        assertEquals(SpamVerdict.Clean, verdicts[0])
        assertTrue(verdicts[3] is SpamVerdict.Flagged)
        assertTrue(verdicts[5] is SpamVerdict.Hidden)
        assertTrue(verdicts[9] is SpamVerdict.Hidden)
        assertTrue((verdicts[9] as SpamVerdict.Hidden).signals.contains(SpamSignal.ShoutFlood))
    }

    @Test
    fun aLoneShoutedWordIsNotHidden() {
        val shield = shield()
        val shout = evaluate(shield, candidate("NEGRO", sender = "chatter"), noMute)
        val ordinary = evaluate(shield, candidate("ok", sender = "chatter", offsetMs = 4_000L), noMute)

        assertEquals(SpamVerdict.Clean, shout)
        assertEquals(SpamVerdict.Clean, ordinary)
    }

    @Test
    fun theRepeatedWordIsOfferedForBlocking() {
        val shield = shield()

        assertEquals("negro", shield.suggestionPhrase("negro negro negro end"))
        assertEquals("", shield.suggestionPhrase("a plain sentence about the mesh"))
    }
}
