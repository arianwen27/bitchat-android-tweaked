package com.bitchat.android.spam

import java.util.Locale

/**
 * Local content-spam shield.
 *
 * Everything in this package is process-local: observation windows, sender scores, mutes and the
 * user's phrase list are held in memory and in the app's private preference file. No message
 * content is transmitted, hashed to a remote service, or persisted outside the device.
 *
 * The engine is deliberately Android-free so its behaviour is fully covered by JVM unit tests, and
 * every rule fails open: an unreadable, empty, self-sent, system, or encrypted message is never
 * scored.
 */

/** Shield aggressiveness. [Off] observes nothing, so behaviour is exactly the pre-shield client. */
enum class SpamMode {
    Off,
    Gentle,
    Balanced,
    Aggressive
}

/** Reason a message was scored. Names stay stable so they can be shown in the review sheet. */
enum class SpamSignal {
    MutedSender,
    BlockedPhrase,
    ExactRepeat,
    CrossSenderRepeat,
    SenderBurst,
    ChannelFlood,
    CharRun,
    LinkFlood,
    ScamPhrase,
    ForwardChain,
    RepetitiveContent,
    ShoutFlood,
    PurgedSender,
    RateFlood,
    NoProofOfWork,
    LowReputation
}

/**
 * One scored message.
 *
 * [senderKey] is the stable identity used for mutes (a mesh peer id, a 
ostr:` alias, or a
 * nickname). [senderLabel] is the display identity used for repeat detection, so the same message
 * relayed through several peers is recognised as one message, not several.
 */
data class SpamCandidate(
    val senderKey: String,
    val senderLabel: String,
    val content: String,
    val scopeKey: String,
    val timestampMs: Long,
    val powDifficulty: Int? = null,
    val fromSelf: Boolean = false,
    val system: Boolean = false,
    val inspectable: Boolean = true
)

/** Thresholds and user phrases for one evaluation pass. */
data class SpamPolicy(
    val mode: SpamMode = SpamMode.Off,
    val blocklist: List<String> = emptyList(),
    val autoMuteMinutes: Int = 30,
    val requireProofOfWorkInGeohash: Boolean = false
)

sealed class ShieldVerdict {
    /** Nothing crossed the flag threshold; the message is shown normally. */
    object Clean : ShieldVerdict()

    /** Visible, but marked as suspicious in the timeline. */
    data class Flagged(val score: Int, val signals: Set<SpamSignal>) : ShieldVerdict()

    /** Removed from the timeline. The sender may also be temporarily muted. */
    data class Hidden(val score: Int, val signals: Set<SpamSignal>, val mutedUntilMs: Long) : ShieldVerdict()
}

/**
 * Pure scoring engine: content fingerprints plus per-scope observation windows.
 *
 * Rules are additive. Each signal contributes a weight; the sum is compared with the mode's flag
 * and hide thresholds. A hidden verdict also records sender reputation and, when auto-mute is
 * enabled, mutes that sender for a bounded time so a spammer cannot keep flooding the channel.
 */
class SpamShield {
    private data class Observation(
        val senderLabel: String,
        val contentKey: String,
        val timestampMs: Long,
        val shout: Boolean = false
    )

    private companion object {
        const val OBSERVATION_WINDOW_MS = 600_000L // 10 minutes of repeat memory
        const val BURST_WINDOW_MS = 30_000L
        const val FLOOD_WINDOW_MS = 60_000L
        const val MAX_OBSERVATIONS_PER_SCOPE = 300
        const val MAX_SCOPES = 64
        const val MAX_TRACKED_SENDERS = 500
        const val CONTENT_KEY_MAX_CHARS = 400
        const val SCORE_MUTE = 100
        const val MAX_SCAM_SCORE = 60
        const val MAX_CROSS_SENDER_SCORE = 60

        /** Phrases that repeatedly appear in coordinated flood spam, in several scripts. */
                val SCAM_PHRASES = listOf(
            "free airdrop",
            "airdrop bot",
            "usdt giveaway",
            "eth giveaway",
            "crypto giveaway",
            "send 1 get 2",
            "send 1 eth",
            "double your eth",
            "double your crypto",
            "100% profit",
            "guaranteed profit",
            "guaranteed returns",
            "make money fast",
            "earn money online",
            "passive income",
            "bitcoin investment",
            "casino bonus",
            "sports betting",
            "click here to",
            "visit my profile",
            "check my profile",
            "my telegram",
            "dm me for",
            "\u043f\u0435\u0440\u0435\u0432\u0435\u0434\u0438",
            "\u043e\u0442\u0434\u0430\u043c",
            "\u0437\u0430\u0440\u0430\u0431\u043e\u0442\u043e\u043a"
        )

        /** Chain-letter markers: `Fwd:`/`FW:`/`Re:` prefixes, counted per line. */
        val FORWARD_MARKER = Regex("""(?m)^\s*(fwd?w?|fw|re)\s*[:.]""")

                val CHAIN_PHRASES = listOf(
            "forward this",
            "forwarding this",
            "forward it to",
            "send this to",
            "chain mail",
            "chain letter",
            "\u043f\u0435\u0440\u0435\u0448\u043b\u0438",
            "\u043e\u0442\u043f\u0440\u0430\u0432\u044c"
        )

        val ZERO_WIDTH = intArrayOf(0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0xFEFF, 0x00AD)

        /** URLs, counted on the canonical form: whitespace is already collapsed to single spaces. */
        val URL_PATTERN = Regex("""https?://\S+|(?<!\w)www\.\S+""")
    }

    // Access-order LRU: a busy client switching between many geohash channels keeps the channels it
    // is actually reading, and drops the ones it left.
    private val scopes = LinkedHashMap<String, ArrayDeque<Observation>>(16, 0.75f, true)
    private val reputation = LinkedHashMap<String, Int>()
    private val lastSpamAt = LinkedHashMap<String, Long>()
    private val mutes = LinkedHashMap<String, Long>()
    private val hideCount = LinkedHashMap<String, Int>()

    /**
     * Score one message and, when it is admitted, record it in its scope window.
     *
     * [candidate] carries the message's own timestamp so a replayed backlog of old events cannot be
     * judged as a live flood: window pruning is done against the message time, not wall time.
     */
    @Synchronized
    fun evaluate(candidate: SpamCandidate, policy: SpamPolicy, nowMs: Long): ShieldVerdict {
        if (policy.mode == SpamMode.Off) return ShieldVerdict.Clean
        if (candidate.fromSelf || candidate.system) return ShieldVerdict.Clean

        val senderKey = candidate.senderKey.lowercase()
        val senderLabel = candidate.senderLabel.lowercase()
        val mutedUntil = muteExpiry(senderKey, senderLabel, nowMs)
        if (mutedUntil != null) {
            return ShieldVerdict.Hidden(SCORE_MUTE, setOf(SpamSignal.MutedSender), mutedUntil)
        }

        if (!candidate.inspectable) return ShieldVerdict.Clean
        val normalized = normalize(candidate.content)
        if (normalized.isEmpty()) return ShieldVerdict.Clean

        val window = scopes.getOrPut(candidate.scopeKey) { ArrayDeque() }
        prune(window, candidate.timestampMs)
        val prior = window.filter { it.timestampMs <= candidate.timestampMs }

        val signals = linkedSetOf<SpamSignal>()
        var score = 0

        // User blocklist: decisive, so a saved phrase alone hides a message at any sensitivity.
        val blocked = policy.blocklist.any { phrase ->
            val key = normalize(phrase)
            key.isNotEmpty() && normalized.contains(key)
        }
        if (blocked) {
            signals += SpamSignal.BlockedPhrase
            score += 60
        }

        val repeats = prior.filter { it.contentKey == normalized }
        when {
            repeats.size >= 2 -> {
                signals += SpamSignal.ExactRepeat
                score += 55
            }
            repeats.size == 1 -> {
                signals += SpamSignal.ExactRepeat
                score += 45
            }
        }
        val distinctRepeatSenders = repeats.map(Observation::senderLabel).toSet()
            .filter { it != senderLabel }
            .size
        if (distinctRepeatSenders >= 1) {
            signals += SpamSignal.CrossSenderRepeat
            score += (30 + 10 * (distinctRepeatSenders - 1)).coerceAtMost(MAX_CROSS_SENDER_SCORE)
        }

        // Rate alone is never decisive: a lively channel posts four lines in half a minute, so the
        // burst signal only contributes, and only at a rate no real conversation sustains.
        val senderBurst = prior.count {
            it.senderLabel == senderLabel && it.timestampMs >= candidate.timestampMs - BURST_WINDOW_MS
        }
        if (senderBurst >= 10) {
            signals += SpamSignal.SenderBurst
            score += 25
        } else if (senderBurst >= 6) {
            signals += SpamSignal.SenderBurst
            score += 12
        }

        val channelLoad = prior.count { it.timestampMs >= candidate.timestampMs - FLOOD_WINDOW_MS }
        if (channelLoad >= 25) {
            signals += SpamSignal.ChannelFlood
            score += 25
        } else if (channelLoad >= 15) {
            signals += SpamSignal.ChannelFlood
            score += 15
        }

        if (longestRun(normalized) >= 12) {
            signals += SpamSignal.CharRun
            score += 15
        }

        // In-message repetition: the shape of a flood posted as one message, e.g. one word pasted
        // five times, or the same line pasted thirty times. Content matching finds nothing here,
        // because the whole message is one message, and the repeat is inside it.
        val lineRepeat = maxLineRepeat(normalized)
        if (lineRepeat >= 3) {
            signals += SpamSignal.RepetitiveContent
            score += 35
        }
        val tokenRepeat = maxTokenRepeat(normalized)
        when {
            tokenRepeat >= 8 -> {
                signals += SpamSignal.RepetitiveContent
                score += 40
            }
            tokenRepeat >= 5 -> {
                signals += SpamSignal.RepetitiveContent
                score += 30
            }
            tokenRepeat >= 4 -> {
                signals += SpamSignal.RepetitiveContent
                score += 20
            }
        }

        // Shouty single-token messages, one all-caps word per message. A spammer rotating words
        // defeats exact-repeat detection, so the shape is counted: how many messages in the last
        // minute were one shouted token. Rate alone stays harmless; a shout plus a shout flood
        // is a pattern no conversation has.
        val candidateShout = isShout(candidate.content)
        val shoutLoad = prior.count {
            it.shout && it.timestampMs >= candidate.timestampMs - FLOOD_WINDOW_MS
        } + (if (candidateShout) 1 else 0)
        if (shoutLoad >= 6) {
            signals += SpamSignal.ShoutFlood
            score += 35
        } else if (shoutLoad >= 4) {
            signals += SpamSignal.ShoutFlood
            score += 20
        } else if (candidateShout) {
            signals += SpamSignal.ShoutFlood
            score += 10
        }

        val urlCount = countUrls(normalized)
        if (urlCount >= 3) {
            signals += SpamSignal.LinkFlood
            score += 35
        } else if (urlCount >= 2) {
            // Two links in one message is the shape a spam bot posts; a person pasting a link pair
            // gets a visible marker, not a hidden message.
            signals += SpamSignal.LinkFlood
            score += 30
        } else if (urlCount == 1 && urlChars(normalized) * 2 > normalized.length) {
            signals += SpamSignal.LinkFlood
            score += 12
        }

        val scamHits = SCAM_PHRASES.count { normalized.contains(it) }
        if (scamHits > 0) {
            signals += SpamSignal.ScamPhrase
            score += (scamHits * 30).coerceAtMost(MAX_SCAM_SCORE)
        }

        val forwardMarkers = FORWARD_MARKER.findAll(normalized).count()
        if (forwardMarkers >= 2 || CHAIN_PHRASES.any { normalized.contains(it) }) {
            signals += SpamSignal.ForwardChain
            score += 30
        }

        if (policy.requireProofOfWorkInGeohash &&
            candidate.scopeKey.startsWith("geo:") &&
            (candidate.powDifficulty ?: 0) <= 0
        ) {
            signals += SpamSignal.NoProofOfWork
            score += 22
        }

        val reputationScore = reputation[senderKey] ?: 0
        if (reputationScore >= 6) {
            signals += SpamSignal.LowReputation
            score += 30
        } else if (reputationScore >= 3) {
            signals += SpamSignal.LowReputation
            score += 15
        }

        if (signals.isEmpty()) {
            record(candidate, normalized, senderKey, spam = false)
            return ShieldVerdict.Clean
        }

        val (flagThreshold, hideThreshold) = thresholds(policy.mode)
        if (score >= hideThreshold) {
            record(candidate, normalized, senderKey, spam = true)
            val mutedUntil = contentStrike(signals, senderKey, senderLabel, candidate.timestampMs, policy)
            return ShieldVerdict.Hidden(score, signals.toSet(), mutedUntil)
        }
        if (score >= flagThreshold) {
            record(candidate, normalized, senderKey, spam = true)
            return ShieldVerdict.Flagged(score, signals.toSet())
        }

        record(candidate, normalized, senderKey, spam = false)
        return ShieldVerdict.Clean
    }

    @Synchronized
    fun isMuted(senderKey: String, senderLabel: String, nowMs: Long): Boolean =
        muteExpiry(senderKey.lowercase(), senderLabel.lowercase(), nowMs) != null

    /** Expiry for a sender's mute, checked against both its stable key and its display label. */
    @Synchronized
    fun muteExpiry(senderKey: String, senderLabel: String, nowMs: Long): Long? {
        val keyExpiry = mutes[senderKey.lowercase()]
        val labelExpiry = mutes[senderLabel.lowercase()]
        val best = maxOf(keyExpiry ?: 0L, labelExpiry ?: 0L)
        return best.takeIf { it > nowMs }
    }

    /** Live mutes, already expired ones dropped. */
    @Synchronized
    fun mutedSenders(nowMs: Long): Map<String, Long> {
        mutes.entries.removeAll { (_, expiry) -> expiry <= nowMs }
        return mutes.toMap()
    }

    /** Persisted mutes restored at startup. Entries are keyed exactly as evaluation uses them. */
    @Synchronized
    fun restoreMutes(restored: Map<String, Long>, nowMs: Long) {
        restored.forEach { (key, expiry) ->
            if (expiry > nowMs) mutes[key.lowercase()] = expiry
        }
    }

    @Synchronized
    fun reputationOf(senderKey: String): Int = reputation[senderKey.lowercase()] ?: 0

    @Synchronized
    fun reset() {
        scopes.clear()
        reputation.clear()
        lastSpamAt.clear()
        mutes.clear()
        hideCount.clear()
    }

    /** Manual mute from the review sheet, keyed by both identities. */
    @Synchronized
    fun mute(senderKey: String, senderLabel: String, nowMs: Long, minutes: Int): Long {
        val expiry = nowMs + minutes.coerceIn(0, 24 * 60) * 60_000L
        if (minutes > 0) {
            mutes[senderKey.lowercase()] = maxOf(mutes[senderKey.lowercase()] ?: 0, expiry)
            mutes[senderLabel.lowercase()] = maxOf(mutes[senderLabel.lowercase()] ?: 0, expiry)
        }
        return expiry
    }

    private fun record(candidate: SpamCandidate, normalized: String, senderKey: String, spam: Boolean) {
        val window = scopes.getOrPut(candidate.scopeKey) { ArrayDeque() }
        window.addLast(
            Observation(
                senderLabel = candidate.senderLabel.lowercase(),
                contentKey = normalized.take(CONTENT_KEY_MAX_CHARS),
                timestampMs = candidate.timestampMs,
                shout = isShout(candidate.content)
            )
        )
        while (window.size > MAX_OBSERVATIONS_PER_SCOPE) window.removeFirst()
        while (scopes.size > MAX_SCOPES) scopes.remove(scopes.keys.first())

        if (spam) {
            reputation[senderKey] = (reputation[senderKey] ?: 0) + 2
            lastSpamAt[senderKey] = candidate.timestampMs
            pruneReputation(candidate.timestampMs)
            while (reputation.size > MAX_TRACKED_SENDERS) {
                val oldest = reputation.keys.firstOrNull() ?: break
                reputation.remove(oldest)
                lastSpamAt.remove(oldest)
            }
        }
    }

    /**
     * Reputation decays on its own clock: a sender who spams once is remembered for a while, and a
     * quiet sender returns to neutral, so a single false positive never becomes permanent.
     */
    private fun pruneReputation(nowMs: Long) {
        val expired = lastSpamAt.filterValues { nowMs - it > 3_600_000L }.keys.toList()
        expired.forEach { key ->
            reputation[key] = (reputation[key] ?: 0) - 1
            if ((reputation[key] ?: 0) <= 0) {
                reputation.remove(key)
                lastSpamAt.remove(key)
            }
        }
    }

    /**
     * Return a sender to default trust, and report how many of their repeat observations were dropped.
     *
     * This is what "you seem fine, you're back to default" means: mute, reputation, second-strike
     * counts and repeat observations all go to zero. Content rules, thresholds and block phrases
     * stay exactly as they are, so a restored sender is scored by the same rules as anyone else and
     * can be hidden again by their own behaviour. It is not a whitelist.
     */
    @Synchronized
    fun restoreTrust(senderKey: String, senderLabel: String): Int {
        val key = senderKey.lowercase()
        val label = senderLabel.lowercase()
        mutes.remove(key)
        mutes.remove(label)
        reputation.remove(key)
        hideCount.remove(key)
        lastSpamAt.remove(key)
        var dropped = 0
        for (window in scopes.values) {
            val mine = window.filter { it.senderLabel == label }.toSet()
            if (mine.isNotEmpty()) {
                window.removeAll(mine)
                dropped += mine.size
            }
        }
        return dropped
    }

    /**
     * Manual judgement from the user action sheet: add reputation, starting the decay clock.
     *
     * The user saw a spammer and said so. The sender keeps being scored normally, and the mute that
     * usually accompanies this is the user's own choice, not the engine's guess.
     */
    @Synchronized
    fun penalize(senderKey: String, points: Int, nowMs: Long) {
        val key = senderKey.lowercase()
        reputation[key] = (reputation[key] ?: 0) + points
        lastSpamAt[key] = nowMs
    }

    /**
     * Auto-mute is a second-strike rule, never a first-strike one.
     *
     * The first content hide only counts. A mute lands when a sender has already been hidden for a
     * content signal, and never for a rate or reputation score alone: a chatty participant who posted
     * one spam-shaped line would otherwise lose every normal message they send for half an hour, and
     * each renewed mute makes that silence permanent. The expiry is not extended, so a mute ends.
     */
    fun contentStrike(
        signals: Set<SpamSignal>,
        senderKey: String,
        senderLabel: String,
        nowMs: Long,
        policy: SpamPolicy
    ): Long {
        val minutes = policy.autoMuteMinutes
        if (minutes <= 0) return 0L
        if (signals.none { it in decisiveForMute }) return 0L
        val priorContentHides = hideCount[senderKey.lowercase()] ?: 0
        hideCount[senderKey.lowercase()] = priorContentHides + 1
        if (priorContentHides < 1) return 0L
        val expiry = nowMs + minutes.coerceIn(1, 24 * 60) * 60_000L
        mutes[senderKey.lowercase()] = expiry
        mutes[senderLabel.lowercase()] = expiry
        return expiry
    }

    private val decisiveForMute = setOf(
        SpamSignal.BlockedPhrase,
        SpamSignal.ScamPhrase,
        SpamSignal.ExactRepeat,
        SpamSignal.CrossSenderRepeat,
        SpamSignal.ForwardChain,
        SpamSignal.LinkFlood,
        SpamSignal.RepetitiveContent,
        SpamSignal.ShoutFlood
    )

    private fun thresholds(mode: SpamMode): Pair<Int, Int> = when (mode) {
        SpamMode.Off -> Int.MAX_VALUE to Int.MAX_VALUE
        SpamMode.Gentle -> 45 to 95
        SpamMode.Balanced -> 30 to 55
        SpamMode.Aggressive -> 20 to 40
    }

    private fun prune(window: ArrayDeque<Observation>, nowMs: Long) {
        val cutoff = nowMs - OBSERVATION_WINDOW_MS
        while (window.isNotEmpty() && window.first().timestampMs < cutoff) window.removeFirst()
    }

    /** Highest count of one token inside one message, e.g. "buy buy buy" -> 3. */
    private fun maxTokenRepeat(text: String): Int {
        val tokens = text.split(' ', '\n').filter { it.isNotEmpty() }
        if (tokens.size < 4) return 0
        return tokens.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    }

    /** Highest count of one line inside one message: a pasted wall of the same line. */
    private fun maxLineRepeat(text: String): Int {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 3) return 0
        return lines.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
    }

    /**
     * One shouted token: a single all-caps word with letters in it, no spaces.
     *
     * This is the shape a flood bot posts, one word per message, and it is deliberately shape-based
     * so a spammer rotating words cannot escape by changing the word. Two-letter shouts ("ok") are
     * excluded: they are ordinary chat.
     */
    private fun isShout(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 3) return false
        if (trimmed.any { it.isWhitespace() }) return false
        if (!trimmed.any { it.isLetter() }) return false
        return trimmed.any { it.isUpperCase() } &&
            trimmed.all { it.isUpperCase() || it.isDigit() || it.code in 0x0300..0x036F }
    }

    /**
     * Token repeated at least three times in [content], for the one-tap block-word action.
     * Empty string when nothing repeats enough times to be worth blocking.
     */
    @Synchronized
    fun suggestionPhrase(content: String): String {
        val counts = content.lowercase().split(' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.length >= 3 && it.any { char -> char.isLetter() } }
            .groupingBy { it }.eachCount()
        return counts.filterValues { it >= 3 }.keys.maxByOrNull { counts[it] ?: 0 } ?: ""
    }

    private fun longestRun(text: String): Int {
        var best = 1
        var run = 1
        for (index in 1 until text.length) {
            run = if (text[index] == text[index - 1]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    private fun countUrls(text: String): Int = urlMatches(text).size

    private fun urlChars(text: String): Int = urlMatches(text).sumOf { it.length }

    private fun urlMatches(text: String): List<String> =
        URL_PATTERN.findAll(text).map { it.value }.toList()

    /**
     * Lowercase, strip zero-width and combining marks, and collapse whitespace runs.
     *
     * Spam routinely hides behind invisible separators, so comparison happens on a canonical form.
     * Nothing is sent anywhere: the canonical form is kept only in the in-memory window.
     */
    private fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val builder = StringBuilder(text.length)
        var lastWasSpace = true
        for (char in text) {
            if (char.code in ZERO_WIDTH) continue
            if (char.code in 0x0300..0x036F) continue
            if (char == '\n') {
                // Line starts are load-bearing: a forwarded chain is recognised by markers at the
                // beginning of lines, so newlines survive normalization while runs of spaces collapse.
                builder.append('\n')
                lastWasSpace = true
                continue
            }
            if (char.isWhitespace()) {
                if (!lastWasSpace) {
                    builder.append(' ')
                    lastWasSpace = true
                }
                continue
            }
            lastWasSpace = false
            builder.append(char.lowercase(Locale.ROOT))
        }
        return builder.toString().trim()
    }
}
