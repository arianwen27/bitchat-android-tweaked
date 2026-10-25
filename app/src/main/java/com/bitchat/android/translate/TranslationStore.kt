package com.bitchat.android.translate

import android.content.Context
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Progress of one message translation. */
sealed class TranslationState {
    object Running : TranslationState()
    data class Ready(val text: String) : TranslationState()
    data class Failed(val reason: String) : TranslationState()

    /** Nothing to do: the message is already in the target language, so no model was asked for it. */
    object Skipped : TranslationState()
}

/** Translation for one message, tagged with the language it was produced for. */
data class TranslationRecord(val targetLang: String, val state: TranslationState)

/**
 * Process-wide translation cache.
 *
 * Translations are keyed by message id, so a message that arrives repeatedly over mesh, BLE, Wi-Fi
 * Aware, a relay, and a Nostr event is translated once. Records live in memory only: translated text
 * is never written to disk, which keeps a user's reading history off the device. Requests run with
 * limited parallelism so a channel flood cannot spawn hundreds of engine calls at once.
 */
object TranslationStore {
    private const val MAX_RECORDS = 400
    private const val MAX_BATCH = 40
    private const val TRANSLATION_TIMEOUT_MS = 45_000L
    private const val TAG = "TranslationStore"

    /** A mention as the UI writes it: `@nickname`, and a peer mention carrying its id fragment. */
    private val MENTION_PATTERN = Regex("""@[^\s@]+""")

    // One engine call at a time. On-device models are fetched by Google Play, and concurrent
    // requests for several language pairs are what makes a download stall: serialising them means a
    // stalled pair blocks nothing else, and the timeout below releases the queue.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val records = MutableStateFlow<Map<String, TranslationRecord>>(emptyMap())
    val snapshot: StateFlow<Map<String, TranslationRecord>> = records.asStateFlow()

    private val engine = MutableStateFlow<TranslationEngine?>(null)
    private val inFlight = mutableSetOf<String>()

    fun init(context: Context) {
        TranslationPreferences.init(context)
        syncEngine()
    }

    /** Rebuilds the engine from persisted settings; safe to call after every setting change. */
    fun syncEngine() {
        setEngine(
            TranslationEngineFactory.create(
                TranslationPreferences.engineMode.value,
                TranslationPreferences.onlineBaseUrl.value
            )
        )
    }

    /** Swaps the engine and closes the previous one. Tests inject a fake engine through this. */
    fun setEngine(next: TranslationEngine?) {
        val current = engine.value
        engine.value = next
        if (current !== next) runCatching { current?.close() }
    }

    /**
     * Queue a translation for one message.
     *
     * Encrypted payloads and non-text rows are never sent to an engine, and a private chat is only
     * translated when the user explicitly allows it, because the online engine ships text to the
     * endpoint the user configured.
     */
    @Synchronized
    fun request(message: BitchatMessage, targetLang: String, conversationKey: String) {
        val activeEngine = engine.value ?: return
        if (!TranslationPreferences.enabled.value) return
        if (targetLang.isBlank()) return
        if (message.isEncrypted) return
        if (message.type != BitchatMessageType.Message) return
        if (message.isPrivate && !TranslationPreferences.allowPrivateTranslation.value) return
        val content = message.content
        if (content.isBlank()) return

        // Handles are kept out of the engine: models mangle them (`@ntm` came back as `@pute`, a
        // peer id came back as `9Bear`). The mention tokens are lifted out, the sentence is
        // translated, and the handles are put back in front of the result.
        val mentions = MENTION_PATTERN.findAll(content).map(MatchResult::value).toList()
        val body = content.replace(MENTION_PATTERN, " ").trim()
        if (body.isEmpty()) return

        val id = message.id

        // A message that is only a link, an id, or punctuation has nothing for a model to translate,
        // so it is recorded as skipped instead of pushing a URL through a language pack.
        if (!body.any { it.isLetter() }) {
            publish(id, targetLang, TranslationState.Skipped)
            return
        }

        val existing = records.value[id]
        if (existing?.targetLang == targetLang &&
            (existing.state is TranslationState.Running ||
                existing.state is TranslationState.Ready ||
                existing.state is TranslationState.Skipped)
        ) {
            return
        }
        if (id in inFlight) return

        val sourceLang = TranslationPreferences.sourceFor(conversationKey)
            ?: LanguageProbe.guessLanguage(body)
        if (sourceLang == targetLang) {
            // Both ends are known to be the same language, so a model would only echo the text back.
            // An unknown source is not treated as English: the engine falls back to its own default,
            // and short chat lines that a probe cannot classify are sent for translation.
            publish(id, targetLang, TranslationState.Skipped)
            return
        }

        inFlight.add(id)
        publish(id, targetLang, TranslationState.Running)

        scope.launch {
            val outcome = runCatching {
                withTimeout(TRANSLATION_TIMEOUT_MS) {
                    activeEngine.translate(body, targetLang, sourceLang)
                }
            }.getOrElse { failure ->
                TranslationOutcome.Failed(
                    if (failure is TimeoutCancellationException) {
                        "timed out after ${TRANSLATION_TIMEOUT_MS / 1000}s"
                    } else {
                        failure.message ?: "translation failed"
                    }
                )
            }
            val state = when (outcome) {
                is TranslationOutcome.Ready -> TranslationState.Ready(joinMentions(mentions, outcome.text))
                is TranslationOutcome.Failed -> TranslationState.Failed(outcome.reason)
            }
            synchronized(this) {
                inFlight.remove(id)
                publish(id, targetLang, state)
            }
            if (state is TranslationState.Failed) {
                // A failed translation is invisible on screen, so the language pair is worth logging:
                // it names the model the user needs, which is what a stalled download is missing.
                Log.i(TAG, "no translation for this message ($sourceLang -> $targetLang): ${state.reason}")
            }
        }
    }

    /** Handles first, then the translated sentence, so nicknames stay exactly as written. */
    private fun joinMentions(mentions: List<String>, translated: String): String =
        if (mentions.isEmpty()) translated else mentions.joinToString(" ") + " " + translated

    /** Queue the newest rows of a conversation, newest first, so reading is never starved. */
    fun requestAll(messages: List<BitchatMessage>, targetLang: String, conversationKey: String) {
        if (!TranslationPreferences.enabled.value) return
        messages.takeLast(MAX_BATCH).forEach { request(it, targetLang, conversationKey) }
    }

    /**
     * Source language for a message: the conversation's explicit language if the user picked one,
     * otherwise the local probe.
     *
     * The key is the same string the UI uses for the conversation (`geo:<hash>`, `channel:<name>`,
     * `mesh`), so a language chosen in settings is the language the engine is given.
     */
    /** Ready translation text for [messageId] in [targetLang], or null when none exists. */
    fun translatedText(messageId: String, targetLang: String): String? {
        val record = records.value[messageId] ?: return null
        if (record.targetLang != targetLang) return null
        return (record.state as? TranslationState.Ready)?.text
    }

    /** Failure reason for [messageId], shown in the UI so a model-download problem is not silent. */
    fun failureReason(messageId: String, targetLang: String): String? {
        val record = records.value[messageId] ?: return null
        if (record.targetLang != targetLang) return null
        return (record.state as? TranslationState.Failed)?.reason
    }

    /** Drop the cache. Called on panic clear and by tests. */
    @Synchronized
    fun clear() {
        inFlight.clear()
        records.value = emptyMap()
    }

    private fun publish(id: String, targetLang: String, state: TranslationState) {
        records.update { current ->
            val next = LinkedHashMap<String, TranslationRecord>()
            current.forEach { (key, value) ->
                if (key != id) next[key] = value
            }
            next[id] = TranslationRecord(targetLang, state)
            while (next.size > MAX_RECORDS) next.remove(next.keys.first())
            next
        }
    }
}
