package com.phoneuse.voice

/**
 * One mic tap can span several short utterances returned by an Android recognition service. The mic waits
 * [waitMs] for the first words, and [pauseMs] after the last ones.
 */
internal class DictationWindow(private val waitMs: Long, private val pauseMs: Long = waitMs, startedAt: Long) {
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
            deadline = (lastSpeech ?: endOfSpeechAt ?: now) + pauseMs
        }
        partial = ""
        lastWordsAt = null
        endOfSpeechAt = null
        firstReady = false
    }

    val text: String get() = join(partial)
    val hasConfirmedWords: Boolean get() = confirmed.isNotEmpty()

    /** Words of the utterance in flight that the service has not confirmed with a final. */
    val hasUnconfirmedWords: Boolean get() = partial.isNotEmpty()

    /** The utterance in flight failed: its words are not part of the text. */
    fun dropUnconfirmed() {
        partial = ""
        lastWordsAt = null
        endOfSpeechAt = null
    }

    private fun join(words: String): String = joinPhrases(confirmed, words)
}

/**
 * Two phrases of one dictation as one text. A space goes between them, except where either side of the join is
 * Chinese or Japanese writing, which does not put spaces between words.
 */
internal fun joinPhrases(first: String, second: String): String = when {
    second.isEmpty() -> first
    first.isEmpty() -> second
    writtenWithoutSpaces(first.codePointBefore(first.length)) || writtenWithoutSpaces(second.codePointAt(0)) -> first + second
    else -> "$first $second"
}

private fun writtenWithoutSpaces(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> true
    // Their punctuation: the ideographic full stop and comma, and the fullwidth forms.
    else -> codePoint in 0x3000..0x303F || codePoint in 0xFF00..0xFFEF
}
