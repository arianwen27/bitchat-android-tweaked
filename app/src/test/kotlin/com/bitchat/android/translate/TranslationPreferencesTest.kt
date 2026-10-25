package com.bitchat.android.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Per-conversation targets must be able to override the global target, because a user who joins a
 * foreign geohash channel wants that channel alone translated into the local language.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationPreferencesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TranslationPreferences.resetForTesting()
        TranslationPreferences.init(context)
    }

    @After
    fun tearDown() {
        TranslationPreferences.resetForTesting()
    }

    @Test
    fun defaultsAreOffOnDeviceAndBelow() {
        assertFalse(TranslationPreferences.enabled.value)
        assertEquals(TranslationEngineMode.OnDevice, TranslationPreferences.engineMode.value)
        assertEquals(TranslationDisplayMode.Below, TranslationPreferences.displayMode.value)
        assertEquals("en", TranslationPreferences.targetLang.value)
        assertFalse(TranslationPreferences.allowPrivateTranslation.value)
    }

    @Test
    fun settingsAreReadBackAfterReinitialisation() {
        TranslationPreferences.setEnabled(true)
        TranslationPreferences.setEngineMode(TranslationEngineMode.Online)
        TranslationPreferences.setDisplayMode(TranslationDisplayMode.Replace)
        TranslationPreferences.setTargetLang("DE")
        TranslationPreferences.setAllowPrivateTranslation(true)

        TranslationPreferences.init(context)

        assertTrue(TranslationPreferences.enabled.value)
        assertEquals(TranslationEngineMode.Online, TranslationPreferences.engineMode.value)
        assertEquals(TranslationDisplayMode.Replace, TranslationPreferences.displayMode.value)
        assertEquals("de", TranslationPreferences.targetLang.value)
        assertTrue(TranslationPreferences.allowPrivateTranslation.value)
    }

    @Test
    fun aChannelTargetOverridesTheGlobalTarget() {
        TranslationPreferences.setTargetLang("de")

        assertEquals("de", TranslationPreferences.languageFor("channel:#global"))
        TranslationPreferences.setConversationLanguage("geo:synthetic", "fr")
        assertEquals("fr", TranslationPreferences.languageFor("geo:synthetic"))
        assertEquals("de", TranslationPreferences.languageFor("channel:#global"))

        TranslationPreferences.setConversationLanguage("geo:synthetic", null)
        assertEquals("de", TranslationPreferences.languageFor("geo:synthetic"))
    }

    @Test
    fun perChannelTargetsSurviveReinitialisation() {
        TranslationPreferences.setConversationLanguage("geo:synthetic", "pl")
        TranslationPreferences.init(context)

        assertEquals("pl", TranslationPreferences.languageFor("geo:synthetic"))
    }

    @Test
    fun settingsLandInSharedPreferencesNotJustInMemory() {
        TranslationPreferences.setEnabled(true)
        TranslationPreferences.setTargetLang("es")

        val store = context.getSharedPreferences("translation_v1", Context.MODE_PRIVATE)
        assertTrue(store.getBoolean("enabled", false))
        assertEquals("es", store.getString("target_lang", null))
    }

    @Test
    fun aChannelSourceLanguageIsStoredAndSurvivesReinitialisation() {
        assertNull(TranslationPreferences.sourceFor("geo:synthetic"))
        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", "fr")
        assertEquals("fr", TranslationPreferences.sourceFor("geo:synthetic"))
        assertNull(TranslationPreferences.sourceFor("channel:#global"))

        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", null)
        assertNull(TranslationPreferences.sourceFor("geo:synthetic"))

        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", "PL")
        TranslationPreferences.init(context)
        assertEquals("pl", TranslationPreferences.sourceFor("geo:synthetic"))

        val store = context.getSharedPreferences("translation_v1", Context.MODE_PRIVATE)
        val json = JSONObject(store.getString("conversation_source_langs_v1", "{}"))
        assertEquals("pl", json.getString("geo:synthetic"))
    }

    @Test
    fun catalogueLabelsAreAvailableForTheTargetChips() {
        assertEquals("German", TranslationLanguageCatalog.labelFor("de"))
        assertEquals("zz", TranslationLanguageCatalog.labelFor("zz"))
    }
}
