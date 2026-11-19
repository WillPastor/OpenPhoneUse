package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class DictationWindowTest {
    @Test fun openingTheMicGetsTheFullWaitButEarlyEmptyRestartsDoNotExtendIt() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.ready(500)
        window.complete(null, 1_250)
        window.ready(1_500)
        window.endOfSpeech(2_000)
        window.complete(" ", 2_100)

        assertEquals(4_400L, window.remaining(2_100))
        assertEquals(0L, window.remaining(6_500))
        assertEquals("", window.text)
    }

    @Test fun partialsAreReplacedWithinAnUtteranceAndPhrasesAreJoinedOnce() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("turn of", 700)
        window.partial("turn of", 1_200)
        window.endOfSpeech(1_500)
        window.complete("turn off", 1_600)
        assertEquals("turn off", window.text)
        assertEquals("turn off the", window.partial("the", 3_000))
        assertEquals("turn off the lights", window.partial("the lights", 3_300))
        window.endOfSpeech(4_000)
        window.complete(null, 4_200)
        window.complete(null, 6_500)
        assertEquals("turn off the lights", window.text)
        assertEquals(2_800L, window.remaining(6_500))
    }

    @Test fun aServiceThatAlreadyWaitedForSilenceDoesNotAddTheSamePauseAgain() {
        val window = DictationWindow(3_000, startedAt = 0)
        window.partial("lights", 1_000)
        window.endOfSpeech(4_000)
        window.complete("lights", 4_200)
        assertEquals(0L, window.remaining(4_200))
    }

    @Test fun servicesWithoutPartialsUseTheEndOfSpeechOrFinalTime() {
        val window = DictationWindow(10_000, startedAt = 0)
        window.endOfSpeech(12_000)
        window.complete("first", 14_000)
        assertEquals(8_000L, window.remaining(14_000))
        window.complete("another phrase", 18_000)
        assertEquals(10_000L, window.remaining(18_000))
        assertEquals("first another phrase", window.text)
    }

    @Test fun anEarlyPartialMustNotUseUpThePauseWhileTheUserIsStillSpeaking() {
        val window = DictationWindow(6_000, startedAt = 0)
        window.partial("please", 1_000)
        window.endOfSpeech(12_000)
        window.complete("please switch off the kitchen lights", 12_200)
        assertEquals(5_800L, window.remaining(12_200))
    }
}
