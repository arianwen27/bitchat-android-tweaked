package com.bitchat.android.translate

import com.bitchat.android.net.OkHttpProvider
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** Result of one translation pass. Failure carries a reason the UI can show. */
sealed class TranslationOutcome {
    data class Ready(val text: String) : TranslationOutcome()
    data class Failed(val reason: String) : TranslationOutcome()
}

enum class TranslationEngineKind {
    None,
    OnDevice,
    Online
}

/**
 * Translates one message body. Implementations must be safe to call from any coroutine dispatcher
 * and must never send text to a destination the user has not configured.
 */
interface TranslationEngine {
    val kind: TranslationEngineKind

    suspend fun translate(text: String, targetLang: String, sourceLang: String?): TranslationOutcome

    fun close() = Unit
}

const val MAX_CHUNK_CHARS = 480

/** ML Kit model input is capped, so long messages are split at whitespace before translation. */
fun splitTranslationChunks(text: String, limit: Int = MAX_CHUNK_CHARS): List<String> {
    if (text.length <= limit) return listOf(text)
    val parts = mutableListOf<String>()
    var remaining = text
    while (remaining.length > limit) {
        val cut = remaining.lastIndexOf(' ', limit)
        if (cut <= 0) {
            parts.add(remaining.take(limit))
            remaining = remaining.substring(limit)
        } else {
            parts.add(remaining.take(cut))
            remaining = remaining.substring(cut + 1).trim()
        }
    }
    if (remaining.isNotEmpty()) parts.add(remaining)
    return parts
}

/**
 * On-device engine: message text never leaves the phone.
 *
 * ML Kit downloads a language pack per language pair over Google Play, and caches it on the device.
 * The pack download reveals which languages are used; it never carries message text. Because a
 * device without Google Play cannot fetch packs, the self-hosted engine below is the path for
 * de-Googled phones, and the target language list is validated against the codes ML Kit supports.
 */
class MlKitTranslationEngine : TranslationEngine {
    private val translators = LinkedHashMap<String, Translator>()

    override val kind: TranslationEngineKind = TranslationEngineKind.OnDevice

    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): TranslationOutcome {
        val targetCode = canonicalTag(targetLang)
            ?: return TranslationOutcome.Failed("target language is not available on device")
        val sourceCode = canonicalTag(sourceLang ?: LanguageProbe.DEFAULT_TAG)
            ?: LanguageProbe.DEFAULT_TAG
        val builder = StringBuilder()
        for (chunk in splitTranslationChunks(text)) {
            val outcome = translateChunk(chunk, sourceCode, targetCode)
            if (outcome is TranslationOutcome.Failed) return outcome
            builder.append((outcome as TranslationOutcome.Ready).text)
        }
        return TranslationOutcome.Ready(builder.toString())
    }

    /** Maps a UI language tag to an ML Kit language code; null when ML Kit has no pack for it. */
    private fun canonicalTag(tag: String): String? {
        val normalized = tag.trim().lowercase().split('-').first()
        if (normalized.isEmpty()) return null
        val code = runCatching { TranslateLanguage.fromLanguageTag(normalized) }.getOrNull()
            ?: return null
        return code.takeIf { TranslateLanguage.getAllLanguages().contains(code) }
    }

    private fun translatorFor(sourceCode: String, targetCode: String): Translator = synchronized(this) {
        val key = "$sourceCode->$targetCode"
        translators[key] ?: TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
            .let { Translation.getClient(it) }
            .also { translators[key] = it }
    }

    private suspend fun translateChunk(chunk: String, sourceCode: String, targetCode: String): TranslationOutcome =
        suspendCancellableCoroutine { continuation ->
            val translator = translatorFor(sourceCode, targetCode)
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    translator.translate(chunk)
                        .addOnSuccessListener { translated ->
                            continuation.resume(TranslationOutcome.Ready(translated), null)
                        }
                        .addOnFailureListener { error ->
                            continuation.resume(
                                TranslationOutcome.Failed(error.message ?: "translation failed"),
                                null
                            )
                        }
                }
                .addOnFailureListener { error ->
                    continuation.resume(
                        TranslationOutcome.Failed(
                            "language pack not downloaded: ${error.message ?: "needs Google Play and a network"}"
                        ),
                        null
                    )
                }
        }

    override fun close() {
        synchronized(this) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
        }
    }
}

/**
 * Self-hosted engine: text goes only to the endpoint the user typed.
 *
 * The request reuses [OkHttpProvider], so when Tor is on, the endpoint call is routed through Tor.
 * The endpoint must be https: a plain-text translation endpoint would expose message content in
 * transit, which is not acceptable for any of these transports.
 */
class LibreTranslateEngine(
    private val baseUrl: () -> String,
    private val client: () -> OkHttpClient = { OkHttpProvider.httpClient() }
) : TranslationEngine {
    override val kind: TranslationEngineKind = TranslationEngineKind.Online

    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): TranslationOutcome =
        withContext(Dispatchers.IO) {
            val endpoint = baseUrl().trim().trimEnd('/')
            if (endpoint.isBlank()) return@withContext TranslationOutcome.Failed("no translation endpoint set")
            if (!endpoint.startsWith("https://")) {
                return@withContext TranslationOutcome.Failed("endpoint must be https")
            }
            val payload = JSONObject()
                .put("q", JSONArray().put(text))
                .put("source", sourceLang ?: "auto")
                .put("target", targetLang)
                .put("format", "text")
            val request = Request.Builder()
                .url("$endpoint/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            try {
                client().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext TranslationOutcome.Failed("endpoint returned ${response.code}")
                    }
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        return@withContext TranslationOutcome.Failed("endpoint returned no text")
                    }
                    parseTranslated(body)
                }
            } catch (error: Exception) {
                TranslationOutcome.Failed(error.message ?: "translation request failed")
            }
        }

    private fun parseTranslated(body: String): TranslationOutcome {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return TranslationOutcome.Failed("endpoint response was not JSON")
        if (!json.optBoolean("success", true)) return TranslationOutcome.Failed("endpoint reported a failure")
        val translated = json.optJSONArray("translatedText")?.let { array ->
            buildString {
                for (index in 0 until array.length()) append(array.optString(index))
            }
        } ?: json.optString("translatedText")
        return if (translated.isBlank()) {
            TranslationOutcome.Failed("endpoint returned no text")
        } else {
            TranslationOutcome.Ready(translated)
        }
    }
}

/** Engine that does nothing, used when translation is switched off. */
object NoTranslationEngine : TranslationEngine {
    override val kind: TranslationEngineKind = TranslationEngineKind.None

    override suspend fun translate(text: String, targetLang: String, sourceLang: String?): TranslationOutcome =
        TranslationOutcome.Failed("translation is off")
}

/** Chooses the engine implementation for the persisted mode. */
object TranslationEngineFactory {
    fun create(mode: TranslationEngineMode, onlineBaseUrl: String): TranslationEngine = when (mode) {
        TranslationEngineMode.Off -> NoTranslationEngine
        TranslationEngineMode.OnDevice -> MlKitTranslationEngine()
        TranslationEngineMode.Online -> LibreTranslateEngine(baseUrl = { onlineBaseUrl })
    }
}
