package com.phoneuse.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.phoneuse.security.KeystoreSecretBox
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {
    @Test
    fun voiceLanguageFollowsTheDeviceUnlessASelectionIsSaved() = runBlocking {
        val key = stringPreferencesKey("voice_language")
        val prefs = object : DataStore<Preferences> {
            override val data = flowOf(
                emptyPreferences(),
                preferencesOf(key to "KOREAN"),
                preferencesOf(key to "ENGLISH"),
            )
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Reading the voice language should not write a preference")
        }
        val settings = SettingsStore(prefs, KeystoreSecretBox())
        val expected = listOf(VoiceLanguage.SYSTEM, VoiceLanguage.KOREAN, VoiceLanguage.ENGLISH)
        assertEquals(expected, settings.voiceLanguage.toList())
    }

    @Test
    fun defaultModelIsTerra() {
        assertEquals("gpt-6.1-terra", SettingsStore.DEFAULT_MODEL)
        assertEquals(SettingsStore.DEFAULT_MODEL, MODEL_PRESETS.first())
    }

    @Test
    fun dailySummaryDoesNotLandOnTheSpendingNudge() {
        // Two proactive notifications in the same minute; the summary keeps an hour away by default.
        val spending = com.phoneuse.proactive.ProactiveEngine.SPENDING_AT.let { it.hour * 60 + it.minute }
        org.junit.Assert.assertTrue(kotlin.math.abs(SettingsStore.DEFAULT_SUMMARY_MINUTE - spending) >= 60)
        org.junit.Assert.assertTrue(SettingsStore.DEFAULT_SUMMARY_MINUTE in com.phoneuse.ui.settings.SUMMARY_OPTIONS)
    }
}
