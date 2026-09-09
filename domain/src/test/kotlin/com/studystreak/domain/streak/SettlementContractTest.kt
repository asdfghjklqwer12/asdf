package com.studystreak.domain.streak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 파이썬 검증 38개에는 없지만 이식 조건으로 요구된 두 가지 — 멱등성과 Clock 주입.
 * streak 규칙 자체는 건드리지 않는다.
 */
class SettlementContractTest {

    private val monToFri = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )
    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun engineAt(instant: String, cutoff: Int = StreakRules.DEFAULT_DAY_CUTOFF_HOUR) =
        StreakEngine(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC), dayCutoffHour = cutoff)

    // ── 멱등성 (10장) ─────────────────────────────────────────────

    @Test
    fun `같은 날짜를 두 번 정산해도 streak이 두 번 오르지 않는다`() {
        val engine = engineAt("2026-09-09T12:00:00Z")
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 2))

        engine.settle(acc, subs, monday, mapOf("수학" to 2))
        val first = engine.settle(acc, subs, monday, mapOf("수학" to 2))

        assertTrue(first.alreadySettled)
        assertEquals(1, acc.overall)
        assertEquals(1, subs[0].streak)
        assertEquals(1, acc.logs.size, "로그도 하루에 하나여야 한다")
    }

    @Test
    fun `재정산은 첫 정산 로그를 그대로 돌려준다`() {
        val engine = engineAt("2026-09-09T12:00:00Z")
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 2))

        val original = engine.settle(acc, subs, monday, mapOf("수학" to 2))
        // 두 번째에는 아예 다른 체크 결과를 넣어도 이미 마감된 날은 안 바뀐다
        val again = engine.settle(acc, subs, monday, emptyMap())

        assertFalse(original.alreadySettled)
        assertTrue(again.alreadySettled)
        assertEquals(Mark.FULL, again.log.mark)
        assertEquals(original.log, again.log)
    }

    @Test
    fun `밀린 날들을 한 번에 채워도 각 날짜는 한 번씩만 센다`() {
        val engine = engineAt("2026-09-11T12:00:00Z")
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 1))
        val done = mapOf("수학" to 1)

        repeat(2) { // 접속 때마다 미정산 날짜를 다시 훑는 상황 (10장 정산 방식)
            for (i in 0..4) engine.settle(acc, subs, monday.plusDays(i.toLong()), done)
        }

        assertEquals(5, acc.overall)
        assertEquals(5, acc.logs.size)
    }

    // ── Clock 주입 (5.3) ──────────────────────────────────────────

    @Test
    fun `마감 시각 전이면 아직 전날이다`() {
        // 서울 기준 9월 10일 새벽 2시 = 아직 9월 9일 (기본 마감 새벽 3시)
        val engine = engineAt("2026-09-09T17:00:00Z")
        assertEquals(LocalDate.of(2026, 9, 9), engine.currentStudyDate(ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `마감 시각을 넘기면 그날로 넘어간다`() {
        // 서울 기준 9월 10일 새벽 3시
        val engine = engineAt("2026-09-09T18:00:00Z")
        assertEquals(LocalDate.of(2026, 9, 10), engine.currentStudyDate(ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `학습일은 계정 타임존으로 정해진다`() {
        // 같은 순간이라도 계정에 저장된 타임존에 따라 학습일이 다르다.
        // 기기 타임존을 따라가지 않는 것이 요점 (5.3)
        // 서울은 9/9 오후 2시, LA 는 아직 9/8 밤 10시
        val engine = engineAt("2026-09-09T05:00:00Z")
        assertEquals(LocalDate.of(2026, 9, 9), engine.currentStudyDate(ZoneId.of("Asia/Seoul")))
        assertEquals(LocalDate.of(2026, 9, 8), engine.currentStudyDate(ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `마감 시각은 사용자가 바꿀 수 있다`() {
        // 마감을 새벽 5시로 미루면 서울 새벽 4시는 아직 전날이다
        val engine = engineAt("2026-09-09T19:00:00Z", cutoff = 5)
        assertEquals(LocalDate.of(2026, 9, 9), engine.currentStudyDate(ZoneId.of("Asia/Seoul")))
    }
}
