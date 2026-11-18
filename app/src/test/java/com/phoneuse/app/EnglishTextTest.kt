package com.phoneuse.app

import com.phoneuse.cards.ParcelCard
import com.phoneuse.i18n.Lang
import com.phoneuse.phone.DigestBucket
import com.phoneuse.phone.StoredNotification
import com.phoneuse.today.TodaySummary
import com.phoneuse.ui.digest.DigestText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EnglishTextTest {
    private var saved = true

    @Before
    fun english() {
        saved = Lang.korean
        Lang.korean = false
    }

    @After
    fun restore() {
        Lang.korean = saved
    }

    @Test
    fun fallbackTextReadsInEnglish() {
        assertEquals("Task finished · Booked it", ReturnPolicy.fallbackText("Booked it"))
        assertEquals("Task finished · Done", ReturnPolicy.fallbackText(null))
    }

    @Test
    fun digestHeadingsAndPromptsUseTheSameLabels() {
        val headings = listOf("Needs a reply", "Payments", "Arriving today", "Other")
        assertEquals(headings, DigestBucket.entries.map { it.displayLabel })
        assertEquals(headings, DigestBucket.entries.map { DigestBucket.displayLabelFor(it.label) })
        assertEquals("Work", DigestBucket.displayLabelFor("Work"))
        val groups = mapOf(DigestBucket.OTHER to listOf(1, 2, 3), DigestBucket.REPLY to listOf(1))
        assertEquals("Needs a reply 1 · Other 3", DailySummary.text(groups))
        assertEquals(
            "Write a draft reply to notification 1 (19:12) under 'Needs a reply' in the notification summary",
            DigestText.replyPrompt(0, "19:12"),
        )
        assertEquals("Where is parcel 2 under 'Arriving today' in the notification summary?", DigestText.trackPrompt(1))
    }

    @Test
    fun parcelStageNamesFollowTheLanguageWithoutChangingStoredStatuses() {
        val statuses = listOf("접수", "이동 중", "배송 출발", "도착")
        assertEquals(statuses, ParcelCard.STAGES)
        assertEquals(listOf("Received", "In transit", "Out for delivery", "Delivered"), statuses.map { TodaySummary.stageLabel(it) })
        Lang.korean = true
        assertEquals(statuses, statuses.map { TodaySummary.stageLabel(it) })
    }

    @Test
    fun digestParcelLineLocalizesOnlyTheParsedStage() {
        val note = StoredNotification(
            "key", "package", "Messages", "Original title",
            "[로젠택배] 고객님의 상품이 접수되었습니다. 운송장번호: 34567890123", 0L,
        )
        assertEquals("로젠택배 Received", DigestText.parcelLine(note))
        val unparsed = note.copy(text = "A note from the sender")
        assertEquals("Original title", DigestText.parcelLine(unparsed))
        assertEquals("Messages", DigestText.parcelLine(unparsed.copy(title = "")))
        Lang.korean = true
        assertEquals("로젠택배 접수", DigestText.parcelLine(note))
    }
}
