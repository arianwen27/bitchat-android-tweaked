package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.spam.SpamHit
import com.bitchat.android.spam.SpamShieldPreferences
import com.bitchat.android.spam.SpamShieldService
import com.bitchat.android.spam.SpamSignal
import com.bitchat.android.translate.TranslationPreferences
import com.bitchat.android.translate.TranslationRecord
import com.bitchat.android.translate.TranslationState
import com.bitchat.android.translate.TranslationStore
import com.bitchat.android.ui.theme.BitchatFontFamily
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Review sheet for messages the local spam shield hid.
 *
 * Hidden messages live in memory for this process only, so the sheet is the disclosure: the user sees
 * exactly what was hidden and why, and can reveal it. Reveal is per message, so trusting one sender
 * does not require switching the shield off. Settings live in the settings sheet, so this one is
 * read-plus-act rather than a settings page.
 */
/** Ceiling on engine requests the review sheet makes for one scope, so a flood cannot flood the queue. */
private const val MAX_TRANSLATED_PER_SCOPE = 25

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpamReviewSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isPresented) return
    val hidden by SpamShieldService.hidden.collectAsState()

    val records by TranslationStore.snapshot.collectAsState()
    val translationEnabled by TranslationPreferences.enabled.collectAsState()
    val globalTarget by TranslationPreferences.targetLang.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    // Hidden messages never reach the chat list, so nothing else asks for their translations. The
    // sheet requests them for its own scope, per channel, so a hidden message can be read in the
    // reader's language.
    //
    // Translation is capped per scope, and messages that are not language are skipped: a wall of one
    // repeated word, a shouty flood, a link dump, or a character run has nothing for a model to
    // translate, and pushing a flood through the engine spends model downloads and a serial queue on
    // content that a reader will never read. That is the denial-of-service shape, so it is refused
    // here. Private text is never sent, and nothing translated is stored.
    val untranslatableShapes = setOf(
        SpamSignal.RepetitiveContent,
        SpamSignal.ShoutFlood,
        SpamSignal.LinkFlood,
        SpamSignal.CharRun
    )
    LaunchedEffect(hidden, translationEnabled, globalTarget) {
        if (!translationEnabled) return@LaunchedEffect
        hidden.groupBy { it.scopeKey }.forEach { (scope, hits) ->
            val key = conversationKeyFor(scope)
            val worthReading = hits
                .filter { it.message.content.any { char -> char.isLetter() } }
                .filterNot { hit -> hit.signals.any { it in untranslatableShapes } }
                .take(MAX_TRANSLATED_PER_SCOPE)
            TranslationStore.requestAll(worthReading.map { it.message }, TranslationPreferences.languageFor(key), key)
        }
    }

    BitchatBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "spam_review_header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.spam_review_title),
                        fontFamily = BitchatFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.spam_review_summary, hidden.size),
                        fontFamily = BitchatFontFamily,
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            if (hidden.isEmpty()) {
                item(key = "spam_review_empty") {
                    Text(
                        text = stringResource(R.string.spam_review_empty),
                        fontFamily = BitchatFontFamily,
                        fontSize = 14.sp,
                        color = colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(hidden, key = { it.message.id }) { hit ->
                    SpamHitRow(
                        hit = hit,
                        timeFormatter = timeFormatter,
                        record = records[hit.message.id],
                        onReveal = { replay(SpamShieldService.reveal(hit.message.id)) },
                        onRevealAll = { SpamShieldService.revealScope(hit.scopeKey).forEach(::replay) },
                        onMute = { SpamShieldService.muteSender(hit.senderKey, hit.senderLabel, 30) },
                        // Restore trust also replays the sender's hidden messages: the user has judged
                        // them fine, so they should be on screen, not left in a ledger.
                        onRestoreTrust = {
                            val theirs = SpamShieldService.hidden.value.filter { it.senderKey == hit.senderKey }
                            SpamShieldService.restoreSender(hit.senderKey, hit.senderLabel)
                            theirs.forEach { replay(it.message) }
                        },
                        onBlockPhrase = {
                            SpamShieldPreferences.addBlockedPhrase(hit.message.content.trim().take(60))
                        },
                        blockWord = SpamShieldService.suggestion(hit.message),
                        onBlockWord = { word -> SpamShieldPreferences.addBlockedPhrase(word) }
                    )
                }
            }
        }
    }
}

/**
 * Puts a revealed message back into its timeline.
 *
 * Revealed ids are marked as allowed inside the shield, so this re-add passes the gate on purpose.
 */
private fun replay(message: BitchatMessage?) {
    if (message == null) return
    val channel = message.channel
    if (channel != null) AppStateStore.addChannelMessage(channel, message)
    else AppStateStore.addPublicMessage(message)
}

/**
 * Map a spam scope to the translation conversation key the reader configures.
 *
 * The shield scopes by channel string, translation keys by conversation, and the two formats differ
 * for plain channels. Without this mapping, a per-channel target language set in the settings sheet
 * would not apply to the hidden messages of that channel.
 */
private fun conversationKeyFor(scopeKey: String): String = when {
    scopeKey == "mesh" -> "mesh"
    scopeKey.startsWith("geo:") -> scopeKey
    else -> "channel:$scopeKey"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpamHitRow(
    hit: SpamHit,
    timeFormatter: SimpleDateFormat,
    record: TranslationRecord?,
    onReveal: () -> Unit,
    onRevealAll: () -> Unit,
    onMute: () -> Unit,
    onRestoreTrust: () -> Unit,
    onBlockPhrase: () -> Unit,
    blockWord: String,
    onBlockWord: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = hit.senderLabel.ifEmpty { hit.senderKey },
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeFormatter.format(hit.message.timestamp),
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
        // Each reason is its own label, so a hidden message explains itself in the reader's language.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hit.signals.forEach { signal ->
                Text(
                    text = stringResource(signalLabel(signal)),
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    color = colorScheme.primary,
                    lineHeight = 16.sp
                )
            }
        }
        Text(
            text = hit.message.content,
            fontFamily = BitchatFontFamily,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp
        )
        // Hidden spam is often unreadable by definition, so the sheet translates it for reading.
        val translated = (record?.state as? TranslationState.Ready)?.text
        val status = when (val state = record?.state) {
            is TranslationState.Running -> stringResource(R.string.translation_running_label)
            is TranslationState.Failed ->
                stringResource(R.string.translation_failed_label, state.reason)
            else -> null
        }
        if (!translated.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.spam_translated_label),
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                color = colorScheme.primary,
                lineHeight = 15.sp
            )
            Text(
                text = translated,
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                color = colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
        }
        if (!status.isNullOrBlank()) {
            Text(
                text = status,
                fontFamily = BitchatFontFamily,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
        // Five actions do not fit one row on a phone, so the actions wrap instead of being squeezed.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onReveal) {
                Text(stringResource(R.string.spam_reveal), fontSize = 13.sp)
            }
            TextButton(onClick = onRevealAll) {
                Text(stringResource(R.string.spam_reveal_all), fontSize = 13.sp)
            }
            TextButton(onClick = onRestoreTrust) {
                Text(stringResource(R.string.spam_restore_trust), fontSize = 13.sp)
            }
            TextButton(onClick = onMute) {
                Text(stringResource(R.string.spam_mute_thirty), fontSize = 13.sp)
            }
            TextButton(onClick = onBlockPhrase) {
                Text(stringResource(R.string.spam_block_phrase), fontSize = 13.sp)
            }
            // A wall of one repeated word is blocked by the word itself, so the repeated token is
            // offered separately from the whole message text.
            if (blockWord.isNotEmpty()) {
                TextButton(onClick = { onBlockWord(blockWord) }) {
                    Text(stringResource(R.string.spam_block_word, blockWord), fontSize = 13.sp)
                }
            }
        }
    }
}

private fun signalLabel(signal: SpamSignal): Int = when (signal) {
    SpamSignal.MutedSender -> R.string.spam_signal_muted
    SpamSignal.BlockedPhrase -> R.string.spam_signal_blocked
    SpamSignal.ExactRepeat -> R.string.spam_signal_repeat
    SpamSignal.CrossSenderRepeat -> R.string.spam_signal_copycat
    SpamSignal.SenderBurst -> R.string.spam_signal_burst
    SpamSignal.ChannelFlood -> R.string.spam_signal_flood
    SpamSignal.CharRun -> R.string.spam_signal_charrun
    SpamSignal.LinkFlood -> R.string.spam_signal_links
    SpamSignal.ScamPhrase -> R.string.spam_signal_scam
    SpamSignal.ForwardChain -> R.string.spam_signal_chain
    SpamSignal.RepetitiveContent -> R.string.spam_signal_repetitive_content
    SpamSignal.ShoutFlood -> R.string.spam_signal_shouty_flood
    SpamSignal.ShoutFlood -> R.string.spam_signal_shouty_flood
    SpamSignal.PurgedSender -> R.string.spam_signal_purged
    SpamSignal.RateFlood -> R.string.spam_signal_rate
    SpamSignal.NoProofOfWork -> R.string.spam_signal_pow
    SpamSignal.LowReputation -> R.string.spam_signal_reputation
}
