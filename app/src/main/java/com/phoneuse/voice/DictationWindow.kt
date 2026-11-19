package com.phoneuse.voice

/** One mic tap can span several short utterances returned by an Android recognition service. */
internal class DictationWindow(private val waitMs: Long, startedAt: Long) {
    private var deadline = startedAt + waitMs
    private var firstReady = true
    private var confirmed = ""
    private var partial = ""
    private var lastWordsAt: Long? = null
    private var endOfSpeechAt: Long? = null

    fun ready(now: Long) {
        if (firstReady) deadline = now + waitMs
        firstReady = false
    }

    fun remaining(now: Long): Long = (deadline - now).coerceAtLeast(0L)

    fun partial(words: String, now: Long): String {
        val text = words.trim()
        if (text.isNotEmpty() && text != partial) {
            partial = text
            lastWordsAt = endOfSpeechAt ?: now
        }
        return join(partial)
    }

    fun endOfSpeech(now: Long) {
        endOfSpeechAt = now
    }

    // A final replaces this utterance's partial. An empty retry must never buy another full wait.
    fun complete(words: String?, now: Long) {
        val text = words?.trim().orEmpty().ifEmpty { partial }
        if (text.isNotEmpty()) {
            confirmed = join(text)
            // A matching final confirms when we last heard the words. A revised or final-only
            // result can contain speech long after the last partial, so use the end callback.
            val lastSpeech = if (text == partial) lastWordsAt else null
            deadline = (lastSpeech ?: endOfSpeechAt ?: now) + waitMs
        }
        partial = ""
        lastWordsAt = null
        endOfSpeechAt = null
        firstReady = false
    }

    val text: String get() = join(partial)
    val hasConfirmedWords: Boolean get() = confirmed.isNotEmpty()

    private fun join(words: String): String = when {
        words.isEmpty() -> confirmed
        confirmed.isEmpty() -> words
        else -> "$confirmed $words"
    }
}
