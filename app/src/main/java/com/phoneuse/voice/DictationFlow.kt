package com.phoneuse.voice

/**
 * One tap of the in-app mic, kept open until the user has been quiet for the chosen wait. A recognition service
 * ends an utterance after a pause of its own choosing, often a second or two, and may ignore the silence hints
 * in the request. So every ending inside the wait starts another utterance, and the words of all of them are
 * joined into one text by [DictationWindow], each said once.
 *
 * Done ([stop]) and Cancel ([close]) never wait out the rest of the pause.
 *
 * Plain JVM code. [Host] stands for the recognizer, the timer and the mic UI. Main thread only.
 */
internal class DictationFlow(private val waitMs: Long, private val host: Host) {
    interface Host {
        fun now(): Long

        /** Starts utterance [turn] on the service, whose callbacks carry [turn] back. False when it could not start. */
        fun start(turn: Int): Boolean

        /** Asks the service to finish the utterance in flight. Its final result or error follows. */
        fun stop()

        /** Drops the utterance in flight. */
        fun cancel()

        /** Runs [action] after [delayMs], in place of any action still waiting. */
        fun after(delayMs: Long, action: () -> Unit)
        fun clearTimer()

        // What the mic UI is told: one session, however many utterances it took.
        fun ready()
        fun beginningOfSpeech()

        /** Everything heard in this session so far. */
        fun partial(text: String)

        /** The wait ran out. The final text follows. */
        fun endOfSpeech()

        /** The session's text, empty when nothing was heard. Sent once, and nothing after it. */
        fun results(text: String)

        /** The session failed with nothing to keep. Sent once, and nothing after it. */
        fun error(code: Int)
    }

    private val window = DictationWindow(waitMs, host.now())

    /** The utterance whose callbacks count. Callbacks of older ones are ignored. */
    private var turn = 0

    /** The service still owes [turn] its final result or error. */
    private var open = false
    private var startedAt = 0L

    /** Words have been heard in [turn]. */
    private var speaking = false

    /** The service has stopped listening to [turn]. */
    private var ended = false
    private var stopping = false
    private var closed = false

    /** Opens the mic, or opens it again for the next utterance. */
    fun listen() {
        if (closed) return
        val id = ++turn
        open = true
        speaking = false
        ended = false
        startedAt = host.now()
        if (!host.start(id)) error(id, ERROR_CLIENT)
    }

    /**
     * Done: end with what was heard. While nothing is being said, which includes the whole wait, that is at
     * once. With words in flight it is as soon as the service returns them.
     */
    fun stop() {
        if (stopping || closed) return
        stopping = true
        host.clearTimer()
        if (open && speaking) {
            if (!ended) host.stop()
        } else {
            deliver()
        }
    }

    /** Cancel, or the mic UI went away: drop everything at once. Nothing is reported after this. */
    fun close() {
        if (closed) return
        closed = true
        stopping = true
        host.clearTimer()
        if (open) {
            open = false
            host.cancel()
        }
    }

    /** True while callbacks of utterance [id] still count. */
    fun hears(id: Int): Boolean = !closed && open && id == turn

    fun ready(id: Int) {
        if (!hears(id) || stopping) return
        val now = host.now()
        window.ready(now)
        if (!speaking && !ended) host.after(window.remaining(now), ::expire)
        host.ready()
    }

    fun beginningOfSpeech(id: Int) {
        if (!hears(id) || stopping || ended) return
        speaking = true
        host.clearTimer()
        host.beginningOfSpeech()
    }

    fun partial(id: Int, words: String) {
        if (!hears(id) || words.isBlank()) return
        if (!ended && !stopping) {
            speaking = true
            host.clearTimer()
        }
        host.partial(window.partial(words, host.now()))
    }

    fun endOfSpeech(id: Int) {
        if (!hears(id) || ended) return
        ended = true
        val now = host.now()
        window.endOfSpeech(now)
        // The final is still to come, and can hold words the service never sent as partial text.
        if (!stopping) host.after(if (speaking) waitMs else window.remaining(now), ::expire)
    }

    fun results(id: Int, words: String?) {
        if (hears(id)) complete(words?.takeIf { it.isNotBlank() })
    }

    fun error(id: Int, code: Int) {
        if (!hears(id)) return
        val kind = voiceErrorOf(code)
        when {
            kind == VoiceError.NO_MATCH -> complete(null)
            // The service was not ready for another utterance yet. Ask again while the wait lasts.
            kind == VoiceError.BUSY && turn > 1 -> complete(null)
            // A later utterance failing must not lose the earlier ones, or turn a quiet ending into an error.
            turn > 1 || window.hasConfirmedWords -> {
                open = false
                deliver()
            }
            else -> {
                open = false
                close()
                host.error(code)
            }
        }
    }

    /** The service returned utterance [turn], with [words] or with nothing. */
    private fun complete(words: String?) {
        val now = host.now()
        open = false
        host.clearTimer()
        window.complete(words, now)
        val remaining = window.remaining(now)
        // Opening the mic again for the last moment of the wait would only start it and stop it.
        if (stopping || remaining <= RESTART_GAP_MS) {
            deliver()
            return
        }
        if (window.text.isNotEmpty()) host.partial(window.text)
        // A service that returns at once every time must not be restarted in a tight loop.
        val gap = (RESTART_GAP_MS - (now - startedAt)).coerceAtLeast(0L)
        host.after(minOf(remaining, gap), ::continueListening)
    }

    private fun continueListening() {
        val remaining = window.remaining(host.now())
        if (remaining == 0L) {
            expire()
            return
        }
        host.after(remaining, ::expire)
        listen()
    }

    /** The user has been quiet for the whole wait. */
    private fun expire() {
        if (stopping || closed) return
        host.endOfSpeech()
        stop()
    }

    private fun deliver() {
        val text = window.text
        close()
        host.results(text)
    }

    companion object {
        /** The least time between two starts of the service. */
        const val RESTART_GAP_MS = 250L

        /** SpeechRecognizer.ERROR_CLIENT, kept literal so this stays plain JVM code. */
        private const val ERROR_CLIENT = 5
    }
}
