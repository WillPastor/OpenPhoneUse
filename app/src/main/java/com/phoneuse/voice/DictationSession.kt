package com.phoneuse.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer

/**
 * Keeps a single in-app mic session across providers' early no-input timeouts and final results.
 * Only starts the next utterance after the service has delivered the preceding terminal callback.
 */
internal class DictationSession(
    private val recognizer: SpeechRecognizer,
    private val request: Intent,
    waitSeconds: Int,
    private val listener: RecognitionListener,
) {
    private val main = Handler(Looper.getMainLooper())
    private val waitMs = waitSeconds * 1_000L
    private val window = DictationWindow(waitMs, SystemClock.uptimeMillis())
    private var current: Utterance? = null
    private var stopping = false
    private var destroyed = false

    fun listen() {
        val turn = Utterance()
        current = turn
        recognizer.setRecognitionListener(turn)
        try {
            recognizer.startListening(request)
        } catch (_: RuntimeException) {
            turn.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stopListening() {
        if (stopping) return
        stopping = true
        main.removeCallbacksAndMessages(null)
        val turn = current
        if (turn == null) {
            after(0L, ::deliver)
        } else if (!turn.ended) {
            runCatching { recognizer.stopListening() }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopping = true
        val stillOpen = current != null
        current = null
        main.removeCallbacksAndMessages(null)
        // The outer final-result watchdog can finish before a stalled service replies.
        if (stillOpen) runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
    }

    private fun expire() {
        listener.onEndOfSpeech()
        stopListening()
    }

    private fun after(delay: Long, action: () -> Unit) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed(action, delay)
    }

    private fun deliver() {
        destroy()
        listener.onResults(results(window.text))
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private inner class Utterance : RecognitionListener by listener {
        val started = SystemClock.uptimeMillis()
        var ended = false
        private var speaking = false

        override fun onReadyForSpeech(params: Bundle?) {
            if (current !== this || stopping) return
            val now = SystemClock.uptimeMillis()
            window.ready(now)
            if (!speaking && !ended) after(window.remaining(now), ::expire)
            listener.onReadyForSpeech(params)
        }

        override fun onBeginningOfSpeech() {
            if (current !== this || stopping || ended) return
            speaking = true
            main.removeCallbacksAndMessages(null)
            listener.onBeginningOfSpeech()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (current !== this) return
            val words = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (words.isNullOrBlank()) return
            if (!ended && !stopping) {
                speaking = true
                main.removeCallbacksAndMessages(null)
            }
            listener.onPartialResults(results(window.partial(words, SystemClock.uptimeMillis())))
        }

        override fun onEndOfSpeech() {
            if (current !== this || ended) return
            ended = true
            val now = SystemClock.uptimeMillis()
            window.endOfSpeech(now)
            // Await the final: it can contain words the service never sent as partial text.
            if (!stopping) after(if (speaking) waitMs else window.remaining(now), ::expire)
        }

        override fun onResults(results: Bundle?) {
            if (current !== this) return
            complete(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() })
        }

        override fun onError(error: Int) {
            if (current !== this) return
            if (voiceErrorOf(error) == VoiceError.NO_MATCH) {
                complete(null)
            } else {
                current = null
                // A failed continuation must not throw away previous successful utterances.
                if (window.hasConfirmedWords) {
                    deliver()
                    return
                }
                destroy()
                listener.onError(error)
            }
        }

        private fun complete(words: String?) {
            val now = SystemClock.uptimeMillis()
            current = null
            main.removeCallbacksAndMessages(null)
            window.complete(words, now)
            val remaining = window.remaining(now)
            if (stopping || remaining == 0L) {
                deliver()
                return
            }
            if (window.text.isNotEmpty()) listener.onPartialResults(results(window.text))
            // A misbehaving service can immediately return NO_MATCH. Bound the restart rate.
            val delay = (250L - (now - started)).coerceAtLeast(0L)
            after(minOf(remaining, delay), ::continueListening)
        }
    }

    private fun continueListening() {
        val remaining = window.remaining(SystemClock.uptimeMillis())
        if (remaining == 0L) {
            expire()
            return
        }
        after(remaining, ::expire)
        listen()
    }
}
