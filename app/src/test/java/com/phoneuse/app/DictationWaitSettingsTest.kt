package com.phoneuse.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.phoneuse.security.KeystoreSecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DictationWaitSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.preferences(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope) {
        File(folder.root, "$name.preferences_pb")
    }

    @Test fun existingInstallsWithoutTheSettingGetSixSeconds() = runTest {
        val prefs = preferences("existing")
        val settings = SettingsStore(prefs, KeystoreSecretBox())
        assertEquals(6, settings.dictationWaitSeconds.first())
    }

    @Test fun eachChoiceSurvivesCreatingANewSettingsStore() = runTest {
        for (seconds in listOf(3, 6, 10)) {
            val prefs = preferences("saved-$seconds")
            SettingsStore(prefs, KeystoreSecretBox()).setDictationWaitSeconds(seconds)
            assertEquals(seconds, SettingsStore(prefs, KeystoreSecretBox()).dictationWaitSeconds.first())
        }
    }

    @Test fun unsupportedSavedWaitFallsBackToTheDefault() = runTest {
        val prefs = preferences("unsupported")
        prefs.edit { it[intPreferencesKey("dictation_wait_seconds")] = -1 }
        assertEquals(6, SettingsStore(prefs, KeystoreSecretBox()).dictationWaitSeconds.first())
    }

    @Test fun unreadablePreferencesStillAllowDictationWithTheDefaultWait() = runTest {
        val prefs = object : DataStore<Preferences> by preferences("unreadable") {
            override val data = flow<Preferences> { throw IOException("Settings unavailable") }
        }
        assertEquals(6, SettingsStore(prefs, KeystoreSecretBox()).dictationWaitSeconds.first())
    }
}
