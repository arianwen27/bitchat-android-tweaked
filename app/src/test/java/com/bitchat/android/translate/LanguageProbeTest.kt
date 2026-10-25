package com.bitchat.android.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local language detection, using synthetic sample sentences.
 *
 * Detection is a hint for the translation engine, so the tests check the script families that matter
 * in practice for foreign global channels, plus the Latin word-hint tiebreaks.
 */
class LanguageProbeTest {
    @Test
    fun japaneseIsDetectedByKana() {
        assertEquals("ja", LanguageProbe.guessLanguage("こんにちは、mesh の 調子は如何ですか"))
    }

    @Test
    fun chineseIsDetectedByHanAlone() {
        assertEquals("zh", LanguageProbe.guessLanguage("网络状态很好，大家好"))
    }

    @Test
    fun koreanIsDetectedByHangul() {
        assertEquals("ko", LanguageProbe.guessLanguage("안녕하세요 메시 상태가 좋아요"))
    }

    @Test
    fun arabicAndHebrewAreDetectedByScript() {
        assertEquals("ar", LanguageProbe.guessLanguage("مرحبا بالشبكة حالة جيدة"))
        assertEquals("he", LanguageProbe.guessLanguage("שלום במשה המצב טוב"))
    }

    @Test
    fun thaiAndGreekAreDetectedByScript() {
        assertEquals("th", LanguageProbe.guessLanguage("สวัสดี สถานetwork ดี"))
        assertEquals("el", LanguageProbe.guessLanguage("γεια σου το δίκτυο είναι καλό"))
    }

    @Test
    fun cyrillicIsDisambiguatedByLetterMarks() {
        assertEquals("ru", LanguageProbe.guessLanguage("привет сеть работает хорошо"))
        assertEquals("uk", LanguageProbe.guessLanguage("привіт мережа працює добре"))
        assertEquals("sr", LanguageProbe.guessLanguage("привљен мрежа ради добро"))
    }

    @Test
    fun latinLanguagesAreChosenByCommonWords() {
        assertEquals("de", LanguageProbe.guessLanguage("ich bin hier und der die das mit nicht"))
        assertEquals("fr", LanguageProbe.guessLanguage("je vous dans les des une est que"))
        assertEquals("es", LanguageProbe.guessLanguage("los con para está y que"))
        assertEquals("pl", LanguageProbe.guessLanguage("nie się że jest bardzo"))
    }

    @Test
    fun unmatchedLatinTextFallsBackToTheDefaultTag() {
        assertEquals("en", LanguageProbe.guessLanguage("this is a test of the mesh"))
    }

    @Test
    fun tooLittleTextIsNotGuessed() {
        assertNull(LanguageProbe.guessLanguage("ok"))
        assertNull(LanguageProbe.guessLanguage("123456789"))
        assertNull(LanguageProbe.guessLanguage(""))
    }

    @Test
    fun dominantScriptIgnoresPunctuationAndSpacing() {
        assertEquals(Script.Cyrillic, LanguageProbe.dominantScript("привет, сеть!"))
        assertEquals(Script.Han, LanguageProbe.dominantScript("网络: 状态很好"))
        assertEquals(Script.Unknown, LanguageProbe.dominantScript("--- 123 ---"))
    }

    @Test
    fun mixedHanAndKanaResolvesToTheMajorityScript() {
        assertEquals("ja", LanguageProbe.guessLanguage("メシの状態は良い、ネットワーク"))
    }

    @Test
    fun shortChatGreetingsAreDetectedByFrequentWords() {
        assertEquals("fr", LanguageProbe.guessLanguage("bonjour tout le monde ça va"))
        assertEquals("en", LanguageProbe.guessLanguage("hello mesh channel thanks"))
        assertEquals("es", LanguageProbe.guessLanguage("hola como estas amigo"))
        assertEquals("de", LanguageProbe.guessLanguage("guten tag wie geht es dir"))
        assertEquals("pl", LanguageProbe.guessLanguage("co to jest dobre"))
    }
}
