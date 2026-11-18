package com.phoneuse.routine

import com.phoneuse.i18n.Lang
import com.phoneuse.proactive.AskLog
import com.phoneuse.proactive.BriefingText
import java.time.DayOfWeek
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class RoutineEnglishTest {
    private var before = true

    @Before fun english() {
        before = Lang.korean
        Lang.korean = false
    }

    @After fun restore() {
        Lang.korean = before
    }

    @Test fun whenTextInEnglish() {
        assertEquals("07:30 · Weekdays", RoutineText.whenText(Trigger.Time(7, 30, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))))
        assertEquals("When charging starts", RoutineText.whenText(Trigger.Device(DeviceKind.CHARGER_CONNECTED, null)))
        assertEquals("1 h 30 min", RoutineText.minutes(90))
    }

    @Test fun templatesAreEnglish() {
        val first = RoutineTemplates.all.first()
        assertEquals("Morning briefing", first.title)
        assertEquals("Morning briefing", first.draft.title)
    }

    @Test fun englishRoutineAskIsOurs() {
        assertTrue(AskLog.ours(BriefingText.TODAY_PROMPT_EN))
        assertTrue(AskLog.ours("${BriefingText.ROUTINE_PROMPT_EN} at 08:00: check the weather"))
    }

    @Test fun builderErrorsFollowTheAppLanguage() {
        val draft = RoutineDraft(title = "Example", prompt = "")
        assertEquals(RoutineParse.Error("Enter what you want the routine to do"), draft.toRoutine())
        assertEquals(
            RoutineParse.Error("Choose an app or enter a keyword for the notification trigger"),
            draft.copy(trigger = TriggerKind.NOTIFICATION).toRoutine(),
        )
        Lang.korean = true
        assertEquals(RoutineParse.Error("action.prompt가 비었어요"), draft.toRoutine())
    }

    @Test fun savedPlaceSourcesUseTheCurrentLanguage() {
        assertEquals("From memory", PlaceResolver.sourceLabel("기억에서"))
        assertEquals("Map search", PlaceResolver.sourceLabel("지도 검색"))
        assertEquals("Set by you", PlaceResolver.sourceLabel("직접 지정"))
        assertEquals("Other source", PlaceResolver.sourceLabel("Other source"))
        Lang.korean = true
        assertEquals("직접 지정", PlaceResolver.sourceLabel("Set by you"))
    }

    @Test fun parcelTemplateKeywordFollowsTheLanguageWhenCreated() {
        val english = RoutineTemplates.all.single { it.title == "Sort parcel messages" }
        assertTrue(english.description.contains("'parcel'"))
        assertEquals("parcel", english.draft.notifKeyword)
        Lang.korean = true
        val korean = RoutineTemplates.all.single { it.title == "택배 문자 오면 정리" }
        assertEquals("택배", korean.draft.notifKeyword)
        val saved = (english.draft.toRoutine() as RoutineParse.Ok).routine
        assertEquals("parcel", RoutineDraft.from(saved).notifKeyword)
    }
}
