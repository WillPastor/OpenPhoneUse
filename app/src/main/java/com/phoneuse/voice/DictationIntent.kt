package com.phoneuse.voice

import android.content.Intent
import android.speech.RecognizerIntent

/** Recognition services, including the system speech dialog, may choose to ignore these hints. */
internal fun Intent.withDictationWait(waitSeconds: Int): Intent = apply {
    val waitMillis = waitSeconds * 1_000
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, waitMillis)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, waitMillis)
    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, waitMillis)
}
