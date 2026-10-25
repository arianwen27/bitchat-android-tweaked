package com.bitchat.android.translate

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Which engine performs translation. [Off] means no translation is requested at all. */
enum class TranslationEngineMode {
    Off,
    OnDevice,
    Online
}

/** How a translated body is shown relative to the original. */
enum class TranslationDisplayMode {
    /** Show the original only. */
    Off,

    /** Show the original, with the translation in a second row beneath it. */
    Below,

    /** Show the translation as the message body; the original stays available in the user sheet. */
    Replace
}

/**
 * A language the user can pick as a translation target.
 *
 * ML Kit validates codes at runtime, so an entry that its model set does not support is reported by
 * the engine as unavailable rather than silently translated into something else.
 */
data class TranslationLanguage(val tag: String, val label: String)

object TranslationLanguageCatalog {
    /** Curated targets for the global-channel reading case: broad coverage, common languages first. */
    val targets = listOf(
        TranslationLanguage("en", "English"),
        TranslationLanguage("es", "Spanish"),
        TranslationLanguage("fr", "French"),
        TranslationLanguage("de", "German"),
        TranslationLanguage("it", "Italian"),
        TranslationLanguage("pt", "Portuguese"),
        TranslationLanguage("nl", "Dutch"),
        TranslationLanguage("pl", "Polish"),
        TranslationLanguage("ru", "Russian"),
        TranslationLanguage("uk", "Ukrainian"),
        TranslationLanguage("sr", "Serbian"),
        TranslationLanguage("bg", "Bulgarian"),
        TranslationLanguage("tr", "Turkish"),
        TranslationLanguage("ar", "Arabic"),
        TranslationLanguage("fa", "Persian"),
        TranslationLanguage("he", "Hebrew"),
        TranslationLanguage("hi", "Hindi"),
        TranslationLanguage("bn", "Bengali"),
        TranslationLanguage("ja", "Japanese"),
        TranslationLanguage("ko", "Korean"),
        TranslationLanguage("zh", "Chinese"),
        TranslationLanguage("th", "Thai"),
        TranslationLanguage("vi", "Vietnamese"),
        TranslationLanguage("id", "Indonesian"),
        TranslationLanguage("ms", "Malay"),
        TranslationLanguage("sv", "Swedish"),
        TranslationLanguage("da", "Danish"),
        TranslationLanguage("fi", "Finnish"),
        TranslationLanguage("el", "Greek"),
        TranslationLanguage("cs", "Czech"),
        TranslationLanguage("ro", "Romanian"),
        TranslationLanguage("hu", "Hungarian")
    )

    fun labelFor(tag: String): String = targets.firstOrNull { it.tag == tag }?.label ?: tag
}

/**
 * Persisted auto-translation settings.
 *
 * Nothing here leaves the device: the target language, the engine choice, the display mode and the
 * per-channel language map are all local preferences. The only network destinations are the ones the
 * user configures themselves.
 */
object TranslationPreferences {
    private const val PREFS_NAME = "translation_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ENGINE = "engine_mode"
    private const val KEY_TARGET = "target_lang"
    private const val KEY_DISPLAY = "display_mode"
    private const val KEY_ALLOW_PRIVATE = "allow_private"
    private const val KEY_ONLINE_URL = "online_base_url"
    private const val KEY_CONVERSATION_LANGS = "conversation_langs_v1"
    private const val KEY_CONVERSATION_SOURCE_LANGS = "conversation_source_langs_v1"

    private const val DEFAULT_ENABLED = false
    private val DEFAULT_ENGINE = TranslationEngineMode.OnDevice
    private const val DEFAULT_TARGET = "en"
    private val DEFAULT_DISPLAY = TranslationDisplayMode.Below
    private const val DEFAULT_ALLOW_PRIVATE = false

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _engineMode = MutableStateFlow(DEFAULT_ENGINE)
    val engineMode: StateFlow<TranslationEngineMode> = _engineMode.asStateFlow()

    private val _targetLang = MutableStateFlow(DEFAULT_TARGET)
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    private val _displayMode = MutableStateFlow(DEFAULT_DISPLAY)
    val displayMode: StateFlow<TranslationDisplayMode> = _displayMode.asStateFlow()

    private val _allowPrivate = MutableStateFlow(DEFAULT_ALLOW_PRIVATE)
    val allowPrivateTranslation: StateFlow<Boolean> = _allowPrivate.asStateFlow()

    private val _onlineBaseUrl = MutableStateFlow("")
    val onlineBaseUrl: StateFlow<String> = _onlineBaseUrl.asStateFlow()

    private val _conversationLangs = MutableStateFlow<Map<String, String>>(emptyMap())
    val conversationLangs: StateFlow<Map<String, String>> = _conversationLangs.asStateFlow()

    /** Per-conversation source language. A missing entry means detect per message. */
    private val _conversationSourceLangs = MutableStateFlow<Map<String, String>>(emptyMap())
    val conversationSourceLangs: StateFlow<Map<String, String>> = _conversationSourceLangs.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val store = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        _enabled.value = store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        _engineMode.value = runCatching {
            TranslationEngineMode.valueOf(
                store.getString(KEY_ENGINE, DEFAULT_ENGINE.name) ?: DEFAULT_ENGINE.name
            )
        }.getOrDefault(DEFAULT_ENGINE)
        _targetLang.value = store.getString(KEY_TARGET, DEFAULT_TARGET) ?: DEFAULT_TARGET
        _displayMode.value = runCatching {
            TranslationDisplayMode.valueOf(
                store.getString(KEY_DISPLAY, DEFAULT_DISPLAY.name) ?: DEFAULT_DISPLAY.name
            )
        }.getOrDefault(DEFAULT_DISPLAY)
        _allowPrivate.value = store.getBoolean(KEY_ALLOW_PRIVATE, DEFAULT_ALLOW_PRIVATE)
        _onlineBaseUrl.value = store.getString(KEY_ONLINE_URL, "") ?: ""
        _conversationLangs.value = loadConversationLangs(store, KEY_CONVERSATION_LANGS)
        _conversationSourceLangs.value = loadConversationLangs(store, KEY_CONVERSATION_SOURCE_LANGS)
    }

    /**
     * Target language for one conversation.
     *
     * A per-channel override wins, because the realistic case is reading a channel for a foreign
     * country: the user picks that language once for that channel and every message in it is
     * translated the same way.
     */
    fun languageFor(conversationKey: String): String =
        lookup(_conversationLangs.value, conversationKey) ?: _targetLang.value

    /**
     * Looks up a conversation setting, following geohash nesting.
     *
     * Location channels nest by geohash prefix, so a language chosen for a coarse area (a country)
     * applies inside it, and a finer channel can override it. A coarse key covers any key that
     * starts with it, and an explicit setting on the finer key wins.
     * entry on the finer key wins. Non-location keys (`channel:<name>`, `mesh`) are matched exactly.
     */
    private fun lookup(map: Map<String, String>, key: String): String? {
        if (!key.startsWith("geo:")) return map[key]
        for (end in key.length downTo "geo:".length + 1) {
            map[key.substring(0, end)]?.let { return it }
        }
        return null
    }

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        write { it.edit().putBoolean(KEY_ENABLED, value).apply()}
    }

    fun setEngineMode(mode: TranslationEngineMode) {
        _engineMode.value = mode
        write { it.edit().putString(KEY_ENGINE, mode.name).apply()}
    }

    fun setTargetLang(tag: String) {
        val cleaned = tag.trim().lowercase()
        if (cleaned.isEmpty()) return
        _targetLang.value = cleaned
        write { it.edit().putString(KEY_TARGET, cleaned).apply()}
    }

    fun setDisplayMode(mode: TranslationDisplayMode) {
        _displayMode.value = mode
        write { it.edit().putString(KEY_DISPLAY, mode.name).apply()}
    }

    fun setAllowPrivateTranslation(value: Boolean) {
        _allowPrivate.value = value
        write { it.edit().putBoolean(KEY_ALLOW_PRIVATE, value).apply()}
    }

    /** Endpoint for the self-hosted engine. It is required to be https, enforced by the engine. */
    fun setOnlineBaseUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        _onlineBaseUrl.value = cleaned
        write { it.edit().putString(KEY_ONLINE_URL, cleaned).apply()}
    }

    /** A null tag removes the override, so the conversation follows the global target again. */
    fun setConversationLanguage(conversationKey: String, tag: String?) {
        val current = _conversationLangs.value.toMutableMap()
        if (tag == null) current.remove(conversationKey) else current[conversationKey] = tag.trim().lowercase()
        _conversationLangs.value = current
        val json = JSONObject()
        current.forEach { (key, value) -> json.put(key, value) }
        write { it.edit().putString(KEY_CONVERSATION_LANGS, json.toString()).apply()}
    }

    /**
     * Source language for one conversation, or null to detect per message.
     *
     * The user knows what language a channel speaks far better than a word-hint probe does, and short
     * chat lines are where a probe is weakest. An explicit source also selects the right ML Kit model,
     * so a French channel uses the French model instead of an English one.
     */
    fun sourceFor(conversationKey: String): String? = lookup(_conversationSourceLangs.value, conversationKey)

    /** A null tag returns the conversation to per-message detection. */
    fun setConversationSourceLanguage(conversationKey: String, tag: String?) {
        val current = _conversationSourceLangs.value.toMutableMap()
        if (tag == null) current.remove(conversationKey) else current[conversationKey] = tag.trim().lowercase()
        _conversationSourceLangs.value = current
        val json = JSONObject()
        current.forEach { (key, value) -> json.put(key, value) }
        write { it.edit().putString(KEY_CONVERSATION_SOURCE_LANGS, json.toString()).apply()}
    }

    fun resetForTesting() {
        prefs = null
        _enabled.value = DEFAULT_ENABLED
        _engineMode.value = DEFAULT_ENGINE
        _targetLang.value = DEFAULT_TARGET
        _displayMode.value = DEFAULT_DISPLAY
        _allowPrivate.value = DEFAULT_ALLOW_PRIVATE
        _onlineBaseUrl.value = ""
        _conversationLangs.value = emptyMap()
        _conversationSourceLangs.value = emptyMap()
    }

    private fun loadConversationLangs(store: SharedPreferences, key: String): Map<String, String> = runCatching {
        val json = JSONObject(store.getString(key, null) ?: "{}")
        buildMap {
            json.keys().forEach { key -> put(key, json.getString(key)) }
        }
    }.getOrDefault(emptyMap())

    private fun write(block: (SharedPreferences) -> Unit) {
        val store = prefs ?: return
        block(store)
    }
}
