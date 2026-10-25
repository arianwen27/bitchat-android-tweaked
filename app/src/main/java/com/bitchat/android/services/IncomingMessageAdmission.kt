package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamShieldService
import kotlinx.coroutines.runBlocking

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public and channel messages retain their existing best-effort behavior if state reflection fails,
 * and are refused (no timeline row, no UI callback) when a spam gate hides them.
 *
 * Two gates run, in this order. The project's [SpamFilter] is authoritative: its token budgets and
 * cross-user copy checks decide first, and its verdicts are memoized by message id so a message
 * relayed over several transports is judged once. The content shield then adds the signals the
 * rate filter cannot see - a block phrase, a scam phrase, a shouted flood, a forward chain, a
 * missing proof of work, and a sender muted for prior spam. A message hidden by either gate is
 * never added, never persisted, and never reaches the UI delegate, so it cannot notify, count as
 * unread, or trigger haptics. Both gates read only plaintext, so an encrypted payload is never
 * gated.
 */
internal object IncomingMessageAdmission {
    fun admitToAppState(message: BitchatMessage): Boolean = try {
        when {
            message.isPrivate -> {
                val peerID = message.senderPeerID?.takeIf(String::isNotBlank)
                    ?: return false
                // Mesh transport callbacks run on their background service workers. Wait for the
                // serialized SQLite transaction so a notification can never advertise a message
                // that an immediate process death would lose.
                runBlocking {
                    AppStateStore.addPrivateMessageDurably(peerID, message)
                }
            }

            isHiddenAsSpam(message) -> false

            message.channel != null -> {
                if (SpamShieldService.admit(message, AppStateStore.nickname.value)) {
                    AppStateStore.addChannelMessage(message.channel, message)
                    true
                } else {
                    false
                }
            }

            else -> {
                if (SpamShieldService.admit(message, AppStateStore.nickname.value)) {
                    AppStateStore.addPublicMessage(message)
                    true
                } else {
                    false
                }
            }
        }
    } catch (_: Exception) {
        // Preserve the pre-existing best-effort dispatch for public/channel messages, but never
        // bypass private-message admission when persistence or canonicalization fails.
        !message.isPrivate
    }

    private fun isHiddenAsSpam(message: BitchatMessage): Boolean {
        if (SpamFilter.isLocalNotice(message)) return false
        val key = SpamFilter.meshSenderKey(message)
    val hide = SpamFilter.shared.shouldHide(message, key)
    if (hide) SpamShieldService.recordFilterHide(message, SpamFilter.shared.check(message, key))
    return hide
    }
}
