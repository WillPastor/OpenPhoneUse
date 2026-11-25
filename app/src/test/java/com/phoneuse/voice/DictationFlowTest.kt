package com.phoneuse.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mic stays open through pauses for the chosen wait, and Done and Cancel never wait it out. */
class DictationFlowTest {
    /** A recognition service, a clock with one timer, and a record of what the mic UI was told. */
    private class FakeHost : DictationFlow.Host {
        var time = 0L
        var canStart = true
        var starts = 0
        val service = mutableListOf<String>()
        val ui = mutableListOf<String>()
        private var timer: Pair<Long, () -> Unit>? = null

        val timerSet: Boolean get() = timer != null

        override fun now(): Long = time
        override fun start(turn: Int): Boolean {
            starts = turn
            service += "start"
            return canStart
        }
        override fun stop() { service += "stop" }
        override fun cancel() { service += "cancel" }
        override fun after(delayMs: Long, action: () -> Unit) { timer = (time + delayMs) to action }
        override fun clearTimer() { timer = null }
        override fun ready() { ui += "ready" }
        override fun beginningOfSpeech() { ui += "speech" }
        override fun partial(text: String) { ui += "partial:$text" }
        override fun endOfSpeech() { ui += "end" }
        override fun results(text: String) { ui += "results:$text" }
        override fun error(code: Int) { ui += "error:$code" }

        /** Moves the clock to [to], running the timer each time it falls due on the way. */
        fun advanceTo(to: Long) {
            while (true) {
                val (due, action) = timer ?: break
                if (due > to) break
                timer = null
                time = due
                action()
            }
            time = to
        }
    }

    private val host = FakeHost()
    private val flow = DictationFlow(6_000, host)

    private val noMatch = 7
    private val network = 2
    private val audio = 3
    private val busy = 8

    /** Says [words] in utterance [turn]: heard at [at], and returned by the service one second later. */
    private fun say(turn: Int, words: String, at: Long) {
        host.advanceTo(at)
        flow.beginningOfSpeech(turn)
        flow.partial(turn, words)
        host.advanceTo(at + 900)
        flow.endOfSpeech(turn)
        host.advanceTo(at + 1_000)
        flow.results(turn, words)
        host.advanceTo(at + 1_000)
    }

    @Test fun aPauseKeepsTheMicOpenAndJoinsThePhrasesWithoutRepeatingAny() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "turn off", at = 800)
        assertEquals(2, host.starts)

        host.advanceTo(2_000)
        flow.ready(2)
        say(2, "the lights", at = 4_000)
        assertEquals(3, host.starts)
        host.advanceTo(5_200)
        flow.ready(3)

        // Six seconds after "the lights" was heard.
        host.advanceTo(9_999)
        assertFalse(host.ui.any { it.startsWith("results") })
        host.advanceTo(10_000)

        assertEquals(
            listOf(
                "partial:turn off", "partial:turn off",
                "partial:turn off the lights", "partial:turn off the lights",
                "end", "results:turn off the lights",
            ),
            host.ui.filter { it.startsWith("partial") || it == "end" || it.startsWith("results") },
        )
        assertEquals(3, host.starts)
    }

    @Test fun silenceLastsTheChosenWaitHoweverEarlyTheServiceGivesUp() {
        flow.listen()
        host.advanceTo(200)
        flow.ready(1)
        // The service stops after five seconds of silence. The wait began when the mic opened, at 200.
        host.advanceTo(5_200)
        flow.error(1, noMatch)
        host.advanceTo(5_200)
        assertEquals(2, host.starts)
        host.advanceTo(5_400)
        flow.ready(2)

        host.advanceTo(6_199)
        assertFalse("end" in host.ui)
        host.advanceTo(6_200)
        assertEquals(listOf("ready", "ready", "end", "results:"), host.ui)
        assertEquals("cancel", host.service.last())
    }

    @Test fun theMicIsNotOpenedAgainForTheLastMomentOfTheWait() {
        flow.listen()
        host.advanceTo(200)
        flow.ready(1)
        // The service gives up a fifth of a second before the wait ends.
        host.advanceTo(6_000)
        flow.error(1, noMatch)
        assertEquals(listOf("ready", "results:"), host.ui)
        assertEquals(1, host.starts)
        assertFalse(host.timerSet)
    }

    @Test fun doneDuringTheWaitEndsAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "hello", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)

        // Nothing is being said. Done must not wait for the rest of the six seconds, or for the service.
        host.advanceTo(2_000)
        flow.stop()
        assertEquals("results:hello", host.ui.last())
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        host.advanceTo(60_000)
        assertEquals(2, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun doneBetweenTwoUtterancesEndsAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(150)
        flow.error(1, noMatch)
        assertTrue(host.timerSet)

        flow.stop()
        assertEquals(listOf("ready", "results:"), host.ui)
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun doneWhileSpeakingWaitsOnlyForThoseWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(1_000)
        flow.partial(1, "send it")
        host.advanceTo(1_500)
        flow.stop()
        assertEquals("stop", host.service.last())
        assertFalse(host.ui.any { it.startsWith("results") })

        host.advanceTo(1_700)
        flow.results(1, "send it now")
        assertEquals("results:send it now", host.ui.last())
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun cancelDuringTheWaitDropsEverythingAtOnce() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "hello", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)
        val told = host.ui.toList()

        host.advanceTo(2_000)
        flow.close()
        assertEquals("cancel", host.service.last())
        assertFalse(host.timerSet)

        // Nothing the service still sends, and no timer, reaches the mic UI after a cancel.
        flow.partial(2, "more")
        flow.results(2, "more")
        flow.error(2, network)
        host.advanceTo(60_000)
        assertEquals(told, host.ui)
        assertEquals(2, host.starts)
    }

    @Test fun aServiceThatReturnsAtOnceIsNotRestartedInATightLoop() {
        flow.listen()
        flow.ready(1)
        host.advanceTo(10)
        flow.error(1, noMatch)
        host.advanceTo(249)
        assertEquals(1, host.starts)
        host.advanceTo(250)
        assertEquals(2, host.starts)

        host.advanceTo(260)
        flow.error(2, noMatch)
        host.advanceTo(499)
        assertEquals(2, host.starts)
        host.advanceTo(500)
        assertEquals(3, host.starts)
    }

    @Test fun aBusyServiceIsAskedAgainAndALaterFailureKeepsTheWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "note this", at = 500)
        assertEquals(2, host.starts)

        host.advanceTo(1_510)
        flow.error(2, busy)
        host.advanceTo(1_750)
        assertEquals(3, host.starts)

        host.advanceTo(2_000)
        flow.error(3, network)
        assertEquals("results:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
    }

    @Test fun aLaterFailureInMidPhraseDeliversOnlyTheConfirmedWords() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "note this", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)

        // A call comes in while the second phrase is being said. Its words were never confirmed.
        host.advanceTo(2_500)
        flow.beginningOfSpeech(2)
        flow.partial(2, "and send it to")
        assertEquals("partial:note this and send it to", host.ui.last())
        host.advanceTo(2_800)
        flow.error(2, audio)

        assertEquals("results:note this", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("error") })
        assertFalse(host.timerSet)
    }

    @Test fun aLaterFailureInMidPhraseWithNothingConfirmedIsAFailure() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        // Silence, and the service gives up. The second utterance fails while the first words are being said.
        host.advanceTo(3_000)
        flow.error(1, noMatch)
        host.advanceTo(3_300)
        flow.ready(2)
        host.advanceTo(4_000)
        flow.partial(2, "send it to")
        host.advanceTo(4_200)
        flow.error(2, audio)

        // Reported as a failure, so the half phrase goes to onInterrupted and is never sent as the text.
        assertEquals("error:$audio", host.ui.last())
        assertFalse(host.ui.any { it.startsWith("results") })
        assertFalse(host.timerSet)
    }

    @Test fun oneTapEndsAtTheSessionLimitHoweverMuchIsSaid() {
        flow.listen()
        // Someone, or a television, keeps talking: a phrase every two seconds, each restarting the wait.
        var turn = 1
        var at = 500L
        while (at < 58_000) {
            flow.ready(turn)
            say(turn, "more", at)
            turn = host.starts
            at += 2_000
        }
        flow.ready(turn)
        host.advanceTo(59_000)
        flow.beginningOfSpeech(turn)
        flow.partial(turn, "and more")

        host.advanceTo(59_999)
        assertFalse("end" in host.ui)
        host.advanceTo(DictationFlow.MAX_SESSION_MS)
        assertEquals("end", host.ui.last())
        assertEquals("stop", host.service.last())

        // Only the words in flight are waited for, as with Done.
        flow.results(turn, "and more")
        assertTrue(host.ui.last().startsWith("results:more more"))
        assertTrue(host.ui.last().endsWith("and more"))
        host.advanceTo(120_000)
        assertEquals(turn, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun silenceCannotOutlastTheSessionLimit() {
        val short = DictationFlow(6_000, host, maxMs = 4_000)
        short.listen()
        host.advanceTo(100)
        short.ready(1)
        host.advanceTo(3_999)
        assertFalse("end" in host.ui)
        host.advanceTo(4_000)
        assertEquals(listOf("ready", "end", "results:"), host.ui)
    }

    @Test fun withNoPauseTheWaitCoversOnlyTheFirstWord() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        // The service gives up on the silence early. The mic is opened again, because nothing was said yet.
        host.advanceTo(3_000)
        handsFree.error(1, noMatch)
        host.advanceTo(3_000)
        assertEquals(2, host.starts)
        host.advanceTo(3_200)
        handsFree.ready(2)

        host.advanceTo(5_000)
        handsFree.beginningOfSpeech(2)
        handsFree.partial(2, "what time is it")
        // The service decides the speaker has finished. Nothing more is waited for, and the mic is not reopened.
        host.advanceTo(6_400)
        handsFree.endOfSpeech(2)
        host.advanceTo(6_400)
        assertEquals("end", host.ui.last())
        host.advanceTo(6_500)
        handsFree.results(2, "what time is it")

        assertEquals("results:what time is it", host.ui.last())
        host.advanceTo(60_000)
        assertEquals(2, host.starts)
        assertEquals(1, host.ui.count { it.startsWith("results") })
    }

    @Test fun withNoPauseSilenceStillLastsTheChosenWait() {
        val handsFree = DictationFlow(6_000, host, pauseMs = 0)
        handsFree.listen()
        host.advanceTo(100)
        handsFree.ready(1)
        host.advanceTo(6_099)
        assertFalse("end" in host.ui)
        host.advanceTo(6_100)
        assertEquals(listOf("ready", "end", "results:"), host.ui)
    }

    @Test fun aFailureWithNothingHeardIsReported() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        host.advanceTo(300)
        flow.error(1, network)
        assertEquals(listOf("ready", "error:$network"), host.ui)
        host.advanceTo(60_000)
        assertEquals(1, host.starts)
    }

    @Test fun aStartThatFailsIsReportedOnce() {
        host.canStart = false
        flow.listen()
        assertEquals(listOf("error:5"), host.ui)
        assertFalse(host.timerSet)
    }

    @Test fun callbacksOfAnEarlierUtteranceAreIgnored() {
        flow.listen()
        host.advanceTo(100)
        flow.ready(1)
        say(1, "first", at = 500)
        host.advanceTo(1_700)
        flow.ready(2)
        val told = host.ui.toList()

        flow.partial(1, "stale")
        flow.results(1, "stale")
        flow.error(1, network)
        assertEquals(told, host.ui)
    }
}
