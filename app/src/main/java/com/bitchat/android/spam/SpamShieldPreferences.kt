package com.bitchat.android.spam

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Persisted spam-shield settings.
 *
 * Only the user's own settings and phrases are stored: the observation windows, sender scores and
 * message ledger stay in memory for the process lifetime, so no spammy text is written to disk.
 * Short auto-mutes are persisted so a restart mid-flood does not re-admit the sender.
 */
object SpamShieldPreferences {
    private const val PREFS_NAME = "spam_shield_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODE = "mode"
    private const val KEY_AUTO_MUTE = "auto_mute_minutes"
    private const val KEY_FLAG_VISIBLE = "flag_visible"
    private const val KEY_REQUIRE_POW = "require_pow_geohash"
    private const val KEY_BLOCKLIST = "blocklist_v1"
    private const val KEY_MUTES = "mutes_v1"

    private const val DEFAULT_ENABLED = false
    private val DEFAULT_MODE = SpamMode.Balanced
    private const val DEFAULT_AUTO_MUTE_MINUTES = 0
    private const val DEFAULT_FLAG_VISIBLE = true
    private const val DEFAULT_REQUIRE_POW = false
    private const val MAX_AUTO_MUTE_MINUTES = 720
    private const val MAX_PHRASES = 40
    private const val MAX_PHRASE_CHARS = 60

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _mode = MutableStateFlow(DEFAULT_MODE)
    val mode: StateFlow<SpamMode> = _mode.asStateFlow()

    private val _autoMuteMinutes = MutableStateFlow(DEFAULT_AUTO_MUTE_MINUTES)
    val autoMuteMinutes: StateFlow<Int> = _autoMuteMinutes.asStateFlow()

    private val _flagVisible = MutableStateFlow(DEFAULT_FLAG_VISIBLE)
    val flagVisible: StateFlow<Boolean> = _flagVisible.asStateFlow()

    private val _requireProofOfWork = MutableStateFlow(DEFAULT_REQUIRE_POW)
    val requireProofOfWork: StateFlow<Boolean> = _requireProofOfWork.asStateFlow()

    private val _blocklist = MutableStateFlow<List<String>>(emptyList())
    val blocklist: StateFlow<List<String>> = _blocklist.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val store = prefs ?: context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { prefs = it }
        _enabled.value = store.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        _mode.value = runCatching {
            SpamMode.valueOf(store.getString(KEY_MODE, DEFAULT_MODE.name) ?: DEFAULT_MODE.name)
        }.getOrDefault(DEFAULT_MODE)
        _autoMuteMinutes.value = store.getInt(KEY_AUTO_MUTE, DEFAULT_AUTO_MUTE_MINUTES)
            .coerceIn(0, MAX_AUTO_MUTE_MINUTES)
        _flagVisible.value = store.getBoolean(KEY_FLAG_VISIBLE, DEFAULT_FLAG_VISIBLE)
        _requireProofOfWork.value = store.getBoolean(KEY_REQUIRE_POW, DEFAULT_REQUIRE_POW)
        _blocklist.value = loadBlocklist(store)
    }

    /** Settings folded into a policy. The master switch wins over the chosen sensitivity. */
    fun policy(): SpamPolicy = SpamPolicy(
        mode = if (SpamFilterPreferences.enabled.value) _mode.value else SpamMode.Off,
        blocklist = _blocklist.value,
        autoMuteMinutes = _autoMuteMinutes.value,
        requireProofOfWorkInGeohash = _requireProofOfWork.value
    )

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        SpamFilterPreferences.setEnabled(value)
        write { it.edit().putBoolean(KEY_ENABLED, value).apply()}
    }

    fun setMode(mode: SpamMode) {
        _mode.value = mode
        write { it.edit().putString(KEY_MODE, mode.name).apply()}
    }

    fun setAutoMuteMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(0, MAX_AUTO_MUTE_MINUTES)
        _autoMuteMinutes.value = clamped
        write { it.edit().putInt(KEY_AUTO_MUTE, clamped).apply()}
    }

    fun setFlagVisible(value: Boolean) {
        _flagVisible.value = value
        write { it.edit().putBoolean(KEY_FLAG_VISIBLE, value).apply()}
    }

    fun setRequireProofOfWork(value: Boolean) {
        _requireProofOfWork.value = value
        write { it.edit().putBoolean(KEY_REQUIRE_POW, value).apply()}
    }

    /**
     * Returns false when the phrase is blank, too long, already present, or the list is full.
     *
     * Phrases are stored whitespace-collapsed to match how the shield normalizes message text, so a
     * phrase pasted from a chat message with odd spacing still matches and cannot be duplicated.
     */
    fun addBlockedPhrase(phrase: String): Boolean {
        val cleaned = phrase.trim().replace(Regex("\\s+"), " ").take(MAX_PHRASE_CHARS)
        if (cleaned.isBlank()) return false
        val current = _blocklist.value
        if (current.any { it.equals(cleaned, ignoreCase = true) }) return false
        if (current.size >= MAX_PHRASES) return false
        val updated = current + cleaned
        _blocklist.value = updated
        write { it.edit().putString(KEY_BLOCKLIST, JSONArray(updated).toString()).apply()}
        return true
    }

    fun removeBlockedPhrase(phrase: String) {
        val updated = _blocklist.value.filterNot { it.equals(phrase, ignoreCase = true) }
        if (updated.size == _blocklist.value.size) return
        _blocklist.value = updated
        write { it.edit().putString(KEY_BLOCKLIST, JSONArray(updated).toString()).apply()}
    }

    fun clearBlockedPhrases() {
        _blocklist.value = emptyList()
        write { it.edit().remove(KEY_BLOCKLIST).apply()}
    }

    fun saveMutes(mutes: Map<String, Long>) {
        val json = org.json.JSONObject()
        mutes.forEach { (key, expiry) -> json.put(key, expiry) }
        write { it.edit().putString(KEY_MUTES, json.toString()).apply()}
    }

    fun loadMutes(): Map<String, Long> {
        val raw = prefs?.getString(KEY_MUTES, null) ?: return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            buildMap {
                json.keys().forEach { key -> put(key, json.getLong(key)) }
            }
        }.getOrDefault(emptyMap())
    }

    /** Panic clear: settings are the user's own, so only the transient mutes are dropped. */
    fun clearMutes() {
        write { it.edit().remove(KEY_MUTES).apply()}
    }

    /** Test hook: drop the cached store and restore the shipped defaults. */
    fun resetForTesting() {
        prefs = null
        _enabled.value = DEFAULT_ENABLED
        _mode.value = DEFAULT_MODE
        _autoMuteMinutes.value = DEFAULT_AUTO_MUTE_MINUTES
        _flagVisible.value = DEFAULT_FLAG_VISIBLE
        _requireProofOfWork.value = DEFAULT_REQUIRE_POW
        _blocklist.value = emptyList()
    }

    private fun loadBlocklist(store: SharedPreferences): List<String> = runCatching {
        val array = JSONArray(store.getString(KEY_BLOCKLIST, null) ?: "[]")
        buildList {
            for (index in 0 until array.length()) {
                array.getString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }.take(MAX_PHRASES)
    }.getOrDefault(emptyList())

    private fun write(block: (SharedPreferences) -> Unit) {
        val store = prefs ?: return
        block(store)
    }
}
