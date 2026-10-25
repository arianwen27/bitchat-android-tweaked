package com.bitchat.android.translate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cache behavior with an injected engine, so no model download and no network are involved.
 *
 * The important guarantees: text that should never reach an engine is skipped before the engine is
 * called, records are tagged with the language they were produced for, and a message that arrives
 * repeatedly over mesh, BLE, Wi-Fi Aware, a relay and a Nostr event is translated once.
 */
@RunWith(RobolectricTestRunner::class)
class TranslationStoreTest {
    private lateinit var context: Context
    private val calls = AtomicInteger(0)
    private var lastText = ""

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TranslationStore.clear()
        TranslationStore.setEngine(null)
        TranslationPreferences.resetForTesting()
        TranslationPreferences.init(context)
        // The store is a process singleton, so pin every setting the cache reads before each test.
        TranslationPreferences.setEnabled(false)
        TranslationPreferences.setAllowPrivateTranslation(false)
        TranslationPreferences.setEngineMode(TranslationEngineMode.OnDevice)
        TranslationPreferences.setTargetLang("en")
        TranslationPreferences.setOnlineBaseUrl("")
        calls.set(0)
    }

    @After
    fun tearDown() {
        TranslationStore.setEngine(null)
        TranslationStore.clear()
        TranslationPreferences.resetForTesting()
    }

    @Test
    fun noEngineMeansNothingIsQueued() {
        TranslationStore.setEngine(null)
        TranslationPreferences.setEnabled(true)
        TranslationStore.request(message("t-1", "hello"), "de", "mesh")
        assertTrue(TranslationStore.snapshot.value.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun aDisabledStoreQueuesNothing() {
        enable(fakeEngine(false))
        TranslationPreferences.setEnabled(false)
        TranslationStore.request(message("t-2", "hello"), "de", "mesh")
        assertTrue(TranslationStore.snapshot.value.isEmpty())
        assertEquals(0, calls.get())
    }

    @Test
    fun encryptedPrivateNonTextAndBlankRowsNeverReachTheEngine() {
        enable(fakeEngine(false))
        val base = message("t-3", "hello there")
        TranslationStore.request(base.copy(id = "t-3e", isEncrypted = true), "de", "mesh")
        TranslationStore.request(base.copy(id = "t-3p", isPrivate = true), "de", "mesh")
        TranslationStore.request(base.copy(id = "t-3a", type = BitchatMessageType.Audio), "de", "mesh")
        TranslationStore.request(base.copy(id = "t-3b", content = "   "), "de", "mesh")
        assertEquals(0, calls.get())
        assertTrue(TranslationStore.snapshot.value.isEmpty())
    }

    @Test
    fun privateTranslationHappensOnlyWhenExplicitlyAllowed() {
        enable(fakeEngine(false))
        val private = message("t-4", "geheimes").copy(isPrivate = true)
        TranslationStore.request(private, "de", "mesh")
        assertEquals(0, calls.get())
        assertTrue(TranslationStore.snapshot.value.isEmpty())

        TranslationPreferences.setAllowPrivateTranslation(true)
        TranslationStore.request(private.copy(id = "t-5"), "de", "mesh")
        awaitState("t-5", "de")
        assertEquals(1, calls.get())
    }

    @Test
    fun aRequestBecomesReadyThroughTheSnapshot() {
        enable(fakeEngine(false))
        TranslationStore.request(message("t-6", "les amis des gens"), "de", "mesh")
        val state = awaitState("t-6", "de")
        assertTrue(state is TranslationState.Ready)
        assertEquals("[de:fr] les amis des gens", (state as TranslationState.Ready).text)
        assertEquals("[de:fr] les amis des gens", TranslationStore.translatedText("t-6", "de"))
    }

    @Test
    fun aFailureIsRecordedAsAReasonTheUiCanShow() {
        enable(fakeEngine(true))
        TranslationStore.request(message("t-7", "hello"), "de", "mesh")
        val state = awaitState("t-7", "de")
        assertTrue(state is TranslationState.Failed)
        assertEquals("model unavailable", TranslationStore.failureReason("t-7", "de"))
        assertNull(TranslationStore.translatedText("t-7", "de"))
    }

    @Test
    fun aRecordIsTaggedWithItsLanguageSoATargetSwitchDoesNotLeak() {
        enable(fakeEngine(false))
        TranslationStore.request(message("t-8", "hello mesh"), "de", "mesh")
        awaitState("t-8", "de")
        assertNull(TranslationStore.translatedText("t-8", "fr"))
        assertEquals("[de:en] hello mesh", TranslationStore.translatedText("t-8", "de"))
    }

    @Test
    fun aRepeatArrivalIsTranslatedOnce() {
        enable(fakeEngine(false))
        val message = message("t-9", "hello mesh")
        TranslationStore.request(message, "de", "mesh")
        TranslationStore.request(message, "de", "mesh")
        TranslationStore.requestAll(listOf(message, message.copy(), message.copy()), "de", "mesh")
        awaitState("t-9", "de")
        assertEquals(1, calls.get())
        assertEquals(1, TranslationStore.snapshot.value.size)
    }

    @Test
    fun aBatchIsCappedAtTheNewestRows() {
        enable(fakeEngine(false))
        val many = (1..60).map { message("batch-$it", "hello $it") }
        TranslationStore.requestAll(many, "de", "mesh")
        awaitSettled(40)
        assertEquals(40, calls.get())
        assertEquals(40, TranslationStore.snapshot.value.size)
        assertNull(TranslationStore.snapshot.value["batch-1"])
        assertNotNull(TranslationStore.snapshot.value["batch-60"])
    }

    @Test
    fun clearDropsTheCache() {
        enable(fakeEngine(false))
        TranslationStore.request(message("t-10", "hello"), "de", "mesh")
        awaitState("t-10", "de")
        assertFalse(TranslationStore.snapshot.value.isEmpty())
        TranslationStore.clear()
        assertTrue(TranslationStore.snapshot.value.isEmpty())
        assertNull(TranslationStore.translatedText("t-10", "de"))
    }

    @Test
    fun aChannelSourceOverrideWinsOverTheProbe() {
        enable(fakeEngine(false))
        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", "fr")
        TranslationStore.request(message("t-11", "bonjour tout le monde"), "de", "geo:synthetic")
        val state = awaitState("t-11", "de")
        assertTrue(state is TranslationState.Ready)
        assertEquals("[de:fr] bonjour tout le monde", (state as TranslationState.Ready).text)
    }

    @Test
    fun sameLanguageOnBothEndsAsksTheEngineForNothing() {
        enable(fakeEngine(false))
        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", "fr")
        TranslationStore.request(message("t-12", "bonjour tout le monde"), "fr", "geo:synthetic")
        val state = TranslationStore.snapshot.value["t-12"]?.state
        assertTrue(state is TranslationState.Skipped)
        assertEquals(0, calls.get())
        assertNull(TranslationStore.translatedText("t-12", "fr"))
    }

    @Test
    fun anUnknownLatinMessageFallsBackToTheUiLanguage() {
        enable(fakeEngine(false))
        TranslationStore.request(message("t-13", "xyz qqq zzz"), "de", "mesh")
        val state = awaitState("t-13", "de")
        assertTrue(state is TranslationState.Ready)
        assertEquals("[de:en] xyz qqq zzz", (state as TranslationState.Ready).text)
    }

    @Test
    fun handlesAreKeptOutOfTheEngineAndPutBackInFrontOfTheTranslation() {
        enable(fakeEngine(false))
        TranslationPreferences.setConversationSourceLanguage("geo:synthetic", "fr")
        TranslationStore.request(message("t-14", "@stella vous me saouler bye"), "en", "geo:synthetic")
        val state = awaitState("t-14", "en")
        assertTrue(state is TranslationState.Ready)
        // The handle comes back exactly as written, and the engine never saw a `@` at all.
        assertTrue((state as TranslationState.Ready).text.startsWith("@stella "))
        assertFalse(lastText.contains("@"))
        assertEquals("vous me saouler bye", lastText)
    }

    @Test
    fun aMessageThatIsOnlyHandlesIsNeverTranslated() {
        enable(fakeEngine(false))
        TranslationStore.request(message("t-15", "@stella @bitchat"), "en", "mesh")
        assertTrue(TranslationStore.snapshot.value.isEmpty())
        assertEquals(0, calls.get())
    }

    private fun enable(engine: TranslationEngine) {
        TranslationPreferences.setEnabled(true)
        TranslationStore.setEngine(engine)
    }

    private fun fakeEngine(fail: Boolean) = object : TranslationEngine {
        override val kind = TranslationEngineKind.OnDevice

        override suspend fun translate(
            text: String,
            targetLang: String,
            sourceLang: String?
        ): TranslationOutcome {
            calls.incrementAndGet()
            lastText = text
            return if (fail) {
                TranslationOutcome.Failed("model unavailable")
            } else {
                TranslationOutcome.Ready("[$targetLang:$sourceLang] $text")
            }
        }

        override fun close() = Unit
    }

    /**
     * Waits for the record to leave the running state.
     *
     * Completion is driven by the observed state, not by a fixed sleep, so the test finishes as soon
     * as the engine settles and fails fast when it never does.
     */
    private fun awaitState(id: String, targetLang: String): TranslationState {
        var state = TranslationStore.snapshot.value[id]?.state ?: TranslationState.Running
        var spins = 0
        while (state is TranslationState.Running && spins < 400) {
            runBlocking { delay(10) }
            state = TranslationStore.snapshot.value[id]?.state ?: TranslationState.Running
            spins++
        }
        return state
    }

    /** Waits until [count] records have left the running state, driven by the observed snapshot. */
    private fun awaitSettled(count: Int) {
        var spins = 0
        while (spins < 800) {
            val current = TranslationStore.snapshot.value
            val settled = current.values.count { it.state !is TranslationState.Running }
            if (current.size == count && settled == count) return
            runBlocking { delay(10) }
            spins++
        }
        fail("batch never settled: ${TranslationStore.snapshot.value.size} records")
    }

    private fun message(id: String, content: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = content,
        timestamp = Date(1L)
    )
}
