package com.bitchat.android.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Local language detection, using synthetic sample sentences.
 *
 * Detection is a hint for the translation engine, so the tests check the script families that matter
 * in practice for foreign global channels, plus the Latin word-hint tiebreaks. Every non-ASCII sample
 * is written as escapes, so the source stays plain ASCII and cannot be damaged by encoding conversion.
 */
class LanguageProbeTest {
    @Test
    fun japaneseIsDetectedByKana() {
        assertEquals("ja", LanguageProbe.guessLanguage("\u3053\u3093\u306B\u3061\u306F\u3001mesh \u306E\u8ABF\u5B50\u306F\u5982\u4F55\u3067\u3059"))
    }

    @Test
    fun chineseIsDetectedByHanAlone() {
        assertEquals("zh", LanguageProbe.guessLanguage("\u4F60\u597D\uFF0Cmesh \u72B6\u6001\u5F88\u597D"))
    }

    @Test
    fun koreanIsDetectedByHangul() {
        assertEquals("ko", LanguageProbe.guessLanguage("\uC548\uB155\uD558\uC138\uC694 mesh \uC0C1\uD0DC \uB8E8\uC288 \uAD00\uB9AC"))
    }

    @Test
    fun arabicAndHebrewAreDetectedByScript() {
        assertEquals("ar", LanguageProbe.guessLanguage("\u0645\u0631\u062D\u0628\u0627 mesh \u0627\u0644\u0634\u0628\u0643\u0629 \u062A\u0639\u0645\u0644"))
        assertEquals("he", LanguageProbe.guessLanguage("\u05E9\u05DC\u05D5\u05DD mesh \u05D4\u05E8\u05E9\u05EA \u05E2\u05D5\u05D1\u05D3\u05EA"))
    }

    @Test
    fun thaiAndGreekAreDetectedByScript() {
        assertEquals("th", LanguageProbe.guessLanguage("\u0E2A\u0E27\u0E31\u0E2D\u0E22\u0E48 mesh \u0E04\u0E30\u0E41\u0E2D\u0E07\u0E04\u0E2D\u0E22 \u0E17\u0E33\u0E07\u0E01\u0E32\u0E23"))
        assertEquals("el", LanguageProbe.guessLanguage("\u03B3\u03B5\u03AF\u03B1 \u03C3\u03B1\u03C2 mesh \u03C4\u03BF \u03B4\u03AF\u03C1\u03C4\u03C5\u03BF \u03B2\u03B1\u03AF\u03BD\u03B5\u03B9 \u03BA\u03B1\u03BB\u03AC"))
    }

    @Test
    fun cyrillicIsDisambiguatedByLetterMarks() {
        assertEquals("ru", LanguageProbe.guessLanguage("\u043F\u0440\u0438\u0432\u0435\u0442 \u0441\u0435\u0442\u044C \u0440\u0430\u0431\u043E\u0442\u0430\u0435\u0442 \u0445\u043E\u0440\u043E\u0448\u043E"))
        assertEquals("uk", LanguageProbe.guessLanguage("\u043F\u0440\u0438\u0432\u0456\u0442 \u043C\u0435\u0440\u0435\u0436\u0430 \u043F\u0440\u0430\u0446\u044E\u0454 \u0434\u043E\u0431\u0440\u0435"))
        assertEquals("sr", LanguageProbe.guessLanguage("\u0455\u043F\u0435\u0446\u0438\u0458\u0430\u043B\u043D\u0430 \u0440\u0435\u0447 \u045B\u0438\u0442\u0430 \u0452\u0443\u0440\u0438\u0447"))
        assertEquals("bg", LanguageProbe.guessLanguage("\u043C\u044A\u0433\u043B\u0435\u043D\u0438\u0446\u0430 \u043C\u0440\u0430\u0437\u0430\u0432\u0430 \u043F\u044A\u0442\u043A"))
        assertEquals("be", LanguageProbe.guessLanguage("\u0456 \u0441\u0435\u0442\u0430\u043A\u0430 \u0433\u0430\u0432\u0430\u0440\u044B \u0434\u043E\u0431\u0440\u0430"))
    }

    @Test
    fun latinLanguagesAreChosenByCommonWords() {
        assertEquals("de", LanguageProbe.guessLanguage("ich bin hier und der die das mit nicht"))
        assertEquals("fr", LanguageProbe.guessLanguage("je vous dans les des une est que"))
        assertEquals("es", LanguageProbe.guessLanguage("los con para est\u00E1 y que"))
        assertEquals("pl", LanguageProbe.guessLanguage("nie sie ze jest bardzo grz\u0119\u017C"))
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
        assertEquals(Script.Cyrillic, LanguageProbe.dominantScript("\u043F\u0438\u0432\u0435\u0442, \u0441\u0435\u0442\u044C!"))
        assertEquals(Script.Han, LanguageProbe.dominantScript("\u4F55\u5927: \u7F51\u7EDC"))
        assertEquals(Script.Unknown, LanguageProbe.dominantScript("--- 123 ---"))
    }

    @Test
    fun mixedHanAndKanaResolvesToTheMajorityScript() {
        assertEquals("ja", LanguageProbe.guessLanguage("\u4F55\u5927 mesh \u3053\u3093\u306B\u3061\u306F\u306F\u3053\u3093\u306B\u3061\u306F"))
    }

    @Test
    fun shortChatGreetingsAreDetectedByFrequentWords() {
        assertEquals("fr", LanguageProbe.guessLanguage("bonjour tout le monde \u00E7a va"))
        assertEquals("en", LanguageProbe.guessLanguage("hello mesh channel thanks"))
        assertEquals("es", LanguageProbe.guessLanguage("hola como estas amigo"))
        assertEquals("de", LanguageProbe.guessLanguage("guten tag wie geht es dir"))
        assertEquals("pl", LanguageProbe.guessLanguage("co to jest dobre"))
    }
}
