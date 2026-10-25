package com.bitchat.android.spam

import android.content.Context
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide spam shield, the single content gate for public and channel messages.
 *
 * Every public/channel path funnels through [AppStateStore], so admission is checked there once per
 * message id. Verdicts are memoized by id: a message that arrives twice over mesh, Wi-Fi Aware, BLE
 * and a Nostr relay is scored once, and its rate-limit counts cannot be inflated by replays.
 *
 * The gate fails open. If the shield itself throws, the message is admitted, because dropping a
 * real message because of a shield bug is worse than showing spam.
 */
object SpamShieldService {
    private const val MAX_LEDGER = 150
    private const val MAX_MEMOIZED_VERDICTS = 600
    private const val MAX_ALLOWED_IDS = 200
    private const val MAX_FLAGGED = 300
    private const val SPAMMER_MUTE_MINUTES = 60
    private const val SPAMMER_REPUTATION_PENALTY = 8

    private val shield = SpamShield()
    private val verdicts = LinkedHashMap<String, ShieldVerdict>(64, 0.75f, true)
    private val allowedIds = LinkedHashSet<String>()
    private val _hidden = MutableStateFlow<List<SpamHit>>(emptyList())
    private val _flaggedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _hiddenTotal = MutableStateFlow(0)

    /** Hidden messages kept in memory only, newest first, for the review sheet. */
    val hidden: StateFlow<List<SpamHit>> = _hidden.asStateFlow()

    /**
     * Count of every message this app instance has hidden, not the size of the review list.
     *
     * The ledger is capped, so a count taken from the list stops growing during a flood and reads
     * as if the shield went quiet. The flood usually keeps going, and the reader needs to see that.
     */
    val hiddenCount: StateFlow<Int> = _hiddenTotal.asStateFlow()

    /** Message ids currently marked as suspicious in the timeline. */
    val flaggedIds: StateFlow<Set<String>> = _flaggedIds.asStateFlow()

    /**
     * Record a hide decided by the rate and repetition filter, so those messages are countable,
     * reviewable and translatable like the shield's own. The filter has no ledger of its own, and
     * a silent hide leaves the reader with no way to tell a flood from a quiet channel.
     */
    @Synchronized
    fun recordFilterHide(message: BitchatMessage, verdict: SpamVerdict) {
        val signal = when (verdict) {
            SpamVerdict.ALLOWED -> return
            SpamVerdict.MESSAGE_RATE, SpamVerdict.CHARACTER_RATE -> SpamSignal.RateFlood
            SpamVerdict.LINK_RATE -> SpamSignal.LinkFlood
            SpamVerdict.REPEATED_MESSAGE -> SpamSignal.ExactRepeat
            SpamVerdict.REPEATED_ACROSS_USERS -> SpamSignal.CrossSenderRepeat
            SpamVerdict.REPEATED_CHARACTERS -> SpamSignal.CharRun
        }
        recordHidden(message, message.channel ?: MESH_SCOPE, keyOf(message), message.sender, setOf(signal), 60)
    }
    fun init(context: Context) {
        SpamShieldPreferences.init(context)
        shield.restoreMutes(SpamShieldPreferences.loadMutes(), System.currentTimeMillis())
    }

    /**
     * Decide whether [message] may enter the timeline.
     *
     * Private, encrypted, media, system and self-sent messages are never scored: the shield reads
     * message content, so there is nothing to inspect in an encrypted payload, and reading a
     * private chat is not the shield's job.
     */
    @Synchronized
    fun admit(message: BitchatMessage, myNickname: String?): Boolean {
        return try {
            val policy = SpamShieldPreferences.policy()
            if (policy.mode == SpamMode.Off) return true
            if (message.isPrivate) return true
            if (message.isEncrypted) return true
            if (message.type != BitchatMessageType.Message) return true
            if (message.sender == "system") return true
            if (myNickname != null && message.sender == myNickname) return true

            val id = message.id
            if (id in allowedIds) return true
            val cached = verdicts[id]
            if (cached != null) return cached is ShieldVerdict.Clean

            val nowMs = System.currentTimeMillis()
            val scopeKey = message.channel ?: MESH_SCOPE
            val senderKey = keyOf(message)
            val senderLabel = message.sender

            val cachedMuteExpiry = shield.muteExpiry(senderKey, senderLabel, nowMs)
            if (cachedMuteExpiry != null) {
                recordHidden(message, scopeKey, senderKey, senderLabel, setOf(SpamSignal.MutedSender), 100)
                return false
            }

            val verdict = shield.evaluate(
                SpamCandidate(
                    senderKey = senderKey,
                    senderLabel = senderLabel,
                    content = message.content,
                    scopeKey = scopeKey,
                    timestampMs = message.timestamp.time,
                    powDifficulty = message.powDifficulty,
                    fromSelf = false,
                    system = false,
                    inspectable = true
                ),
                policy,
                nowMs
            )
            memoize(id, verdict)
            when (verdict) {
                is ShieldVerdict.Hidden -> {
                    recordHidden(
                        message,
                        scopeKey,
                        senderKey,
                        senderLabel,
                        verdict.signals,
                        verdict.score
                    )
                    SpamShieldPreferences.saveMutes(shield.mutedSenders(nowMs))
                    false
                }

                is ShieldVerdict.Flagged -> {
                    recordFlag(id)
                    true
                }

                ShieldVerdict.Clean -> true
            }
        } catch (_: Exception) {
            true
        }
    }

    /** Verdict recorded for a message, so the UI can mark a flagged row. */
    fun verdictFor(messageId: String): ShieldVerdict? = synchronized(this) { verdicts[messageId] }

    /**
     * Re-admit a hidden message and return it for the caller to replay into the timeline.
     *
     * Revealing is per message, so a user who trusts one sender can recover their messages without
     * turning the whole shield off.
     */
    @Synchronized
    fun reveal(messageId: String): BitchatMessage? {
        val hit = _hidden.value.firstOrNull { it.message.id == messageId } ?: return null
        allowId(messageId)
        verdicts[messageId] = ShieldVerdict.Clean
        _hidden.value = _hidden.value.filterNot { it.message.id == messageId }
        return hit.message
    }

    /** Reveal every hidden message in one scope, newest kept for replay. */
    @Synchronized
    fun revealScope(scopeKey: String): List<BitchatMessage> {
        val hits = _hidden.value.filter { it.scopeKey == scopeKey }
        hits.forEach { allowId(it.message.id) }
        hits.forEach { verdicts[it.message.id] = ShieldVerdict.Clean }
        _hidden.value = _hidden.value.filterNot { it.scopeKey == scopeKey }
        return hits.map(SpamHit::message)
    }

    @Synchronized
    fun hiddenCount(scopeKey: String): Int = _hidden.value.count { it.scopeKey == scopeKey }

    /** Mute a sender from the review sheet, persisting the expiry. */
    @Synchronized
    fun muteSender(senderKey: String, senderLabel: String, minutes: Int): Long {
        val nowMs = System.currentTimeMillis()
        val expiry = shield.mute(senderKey, senderLabel, nowMs, minutes)
        SpamShieldPreferences.saveMutes(shield.mutedSenders(nowMs))
        return expiry
    }

    /**
     * Mark a sender as a spammer from the message action sheet.
     *
     * This is the user's explicit judgement, so the mute extends (manual mutes are deliberate, unlike
     * auto-mutes, which never renew) and the sender takes a reputation hit. Nothing here whitelists
     * or blacklists permanently: the sender keeps being scored, and the mute ends.
     */
    @Synchronized
    fun markSpammer(senderKey: String, senderLabel: String, minutes: Int = SPAMMER_MUTE_MINUTES): Long {
        val nowMs = System.currentTimeMillis()
        shield.penalize(senderKey, SPAMMER_REPUTATION_PENALTY, nowMs)
        val expiry = shield.mute(senderKey, senderLabel, nowMs, minutes)
        SpamShieldPreferences.saveMutes(shield.mutedSenders(nowMs))
        return expiry
    }

    /**
     * Return a sender to default trust: mute, reputation, strikes and repeat observations drop.
     *
     * Revealing is per message; restoring trust is per sender, for the case where the review sheet
     * shows a person who is clearly just chatting. Hidden messages from that sender leave the ledger,
     * and content rules are untouched, so their next spam is judged exactly like anyone else's.
     */
    @Synchronized
    fun restoreSender(senderKey: String, senderLabel: String): Int {
        val key = senderKey.lowercase()
        val dropped = shield.restoreTrust(senderKey, senderLabel)
        val hits = _hidden.value.filter { it.senderKey == key }
        hits.forEach { it.message.id.let { id -> allowId(id); verdicts[id] = ShieldVerdict.Clean } }
        _hidden.value = _hidden.value.filterNot { it.senderKey == key }
        _flaggedIds.update { ids -> ids - hits.map { it.message.id }.toSet() }
        SpamShieldPreferences.saveMutes(shield.mutedSenders(System.currentTimeMillis()))
        return dropped
    }

    /** Drop the ledger and window state. Panic clear uses this, so spam scores survive no wipe. */
    @Synchronized
    fun reset() {
        shield.reset()
        verdicts.clear()
        allowedIds.clear()
        _hidden.value = emptyList()
        _hiddenTotal.value = 0
        _flaggedIds.value = emptySet()
        SpamShieldPreferences.clearMutes()
    }

    /** Stable sender key: the Nostr pubkey when present, then the peer id, then the nickname. */
    fun keyOf(message: BitchatMessage): String =
        (message.senderNostrPubkey ?: message.senderPeerID ?: message.sender).lowercase()

    /**
     * Token worth blocking for a hidden message: the word repeated inside it.
     *
     * Empty string when nothing repeats. The review sheet offers this as a one-tap block, because a
     * wall of one repeated word is the case where a reader wants to block the word itself, not the
     * whole message text.
     */
    fun suggestion(message: BitchatMessage): String = shield.suggestionPhrase(message.content)

    private fun recordHidden(
        message: BitchatMessage,
        scopeKey: String,
        senderKey: String,
        senderLabel: String,
        signals: Set<SpamSignal>,
        score: Int
    ) {
        val hit = SpamHit(
            message = message,
            scopeKey = scopeKey,
            senderKey = senderKey,
            senderLabel = senderLabel,
            signals = signals,
            score = score,
            hiddenAtMs = System.currentTimeMillis()
        )
        _hidden.value = (listOf(hit) + _hidden.value).take(MAX_LEDGER)
        _hiddenTotal.value += 1
        _flaggedIds.update { it - message.id }
    }

    private fun allowId(id: String) {
        allowedIds.remove(id)
        allowedIds.add(id)
        while (allowedIds.size > MAX_ALLOWED_IDS) allowedIds.remove(allowedIds.first())
    }

    private fun recordFlag(id: String) {
        val next = _flaggedIds.value + id
        _flaggedIds.value =
            if (next.size > MAX_FLAGGED) next.drop(next.size - MAX_FLAGGED).toSet() else next
    }

    private fun memoize(id: String, verdict: ShieldVerdict) {
        verdicts[id] = verdict
        while (verdicts.size > MAX_MEMOIZED_VERDICTS) verdicts.remove(verdicts.keys.first())
    }

    private const val MESH_SCOPE = "mesh"
}

/** One hidden message, with the reason it was hidden, for the review sheet. */
data class SpamHit(
    val message: BitchatMessage,
    val scopeKey: String,
    val senderKey: String,
    val senderLabel: String,
    val signals: Set<SpamSignal>,
    val score: Int,
    val hiddenAtMs: Long
)
