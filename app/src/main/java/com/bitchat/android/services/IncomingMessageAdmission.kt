package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.spam.SpamShieldService
import kotlinx.coroutines.runBlocking

/**
 * Reflects an incoming transport message into process-wide state before any downstream effects.
 *
 * Private-message admission is authoritative: a duplicate or a message rejected while panic mode
 * is wiping state must not continue to UI delegates, unread tracking, haptics, or notifications.
 * Public and channel messages retain their existing best-effort behavior if state reflection fails.
 *
 * Content spam is decided before state reflection. A message the local spam shield hides is never
 * added, never persisted, and never reaches the UI delegate, so it cannot notify, count as unread,
 * or trigger haptics. The shield only reads plaintext, so an encrypted payload is never gated.
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
}
