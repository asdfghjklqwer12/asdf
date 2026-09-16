package com.studystreak.domain

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.Mark
import com.studystreak.domain.streak.StreakEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 분량 계획 → 체크리스트 → 과목 으로 이어지는 다리.
 *
 * 기획 6.2의 예시(정석 480페이지 · 9/9 ~ 12/31 · 평일)를 그대로 쓴다.
 */
class ChecklistTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC))
    private val monToFri = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun jeongseok() =
        allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), monToFri, dailyMax = 10)

    // ── 나누기 ──────────────────────────────────────────────────

    @Test
    fun `하루치를 두 줄로 나눈다`() {
        val checklist = jeongseok().toChecklist()
        val first = checklist.first()

        assertEquals(7, first.amount, "9월 9일은 7페이지")
        assertEquals(2, first.tasks.size)
        assertEquals(4, first.tasks[0].amount, "나머지는 앞줄이 갖는다")
        assertEquals(3, first.tasks[1].amount)
    }

    @Test
    fun `위치가 플랜 전체에 걸쳐 이어진다`() {
        val checklist = jeongseok().toChecklist()
        val studyDays = checklist.filterNot { it.isRestDay }

        assertEquals(listOf(1 to 4, 5 to 7), studyDays[0].tasks.map { it.from to it.to })
        assertEquals(listOf(8 to 11, 12 to 14), studyDays[1].tasks.map { it.from to it.to })
        assertEquals(listOf(15 to 18, 19 to 21), studyDays[2].tasks.map { it.from to it.to })
    }

    @Test
    fun `마지막 위치가 총분량과 같다`() {
        val checklist = jeongseok().toChecklist()
        val last = checklist.flatMap { it.tasks }.last()

        assertEquals(480, last.to, "1페이지부터 480페이지까지 빠짐없이 덮는다")
    }

    @Test
    fun `줄을 다 합치면 총분량이다`() {
        val checklist = jeongseok().toChecklist()

        assertEquals(480, checklist.sumOf { day -> day.tasks.sumOf { it.amount } })
        assertTrue(
            checklist.all { it.amount == it.tasks.sumOf { t -> t.amount } },
            "하루치와 그날 줄의 합이 어긋나면 안 된다",
        )
    }

    // ── 휴식일 ──────────────────────────────────────────────────

    @Test
    fun `버퍼일은 줄이 없어 휴식일이 된다`() {
        val a = jeongseok()
        val checklist = a.toChecklist()
        val restDays = checklist.filter { it.isRestDay }.map { it.date }

        assertEquals(a.bufferDays, restDays, "버퍼일이 그대로 휴식일이다")
        assertTrue(checklist.filter { it.isRestDay }.all { it.amount == 0 })
    }

    @Test
    fun `분량이 한 개뿐인 날은 한 줄만 만든다`() {
        // 5강짜리를 3개월에 — 공부하는 날이 5일뿐이고 하루 1강이다
        val a = allocate(5, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 12, 31), monToFri)
        val checklist = a.toChecklist()
        val studyDays = checklist.filterNot { it.isRestDay }

        assertEquals(5, studyDays.size)
        assertTrue(studyDays.all { it.tasks.size == 1 }, "1강을 두 줄로 쪼갤 수는 없다")
        assertEquals(5, studyDays.flatMap { it.tasks }.last().to)
    }

    @Test
    fun `줄 수를 세 개로 바꿀 수도 있다`() {
        val first = jeongseok().toChecklist(tasksPerDay = 3).first()

        assertEquals(3, first.tasks.size)
        assertEquals(listOf(3, 2, 2), first.tasks.map { it.amount }, "7 = 3 + 2 + 2")
    }

    // ── 과목으로 넘기기 ─────────────────────────────────────────

    @Test
    fun `두 줄 중 한 줄만 하면 세모다`() {
        val checklist = jeongseok().toChecklist()
        val subject = checklist.toSubject("정석", required = true)
        val firstStudyDay = checklist.first { !it.isRestDay }.date

        val log = engine.settle(Account(), listOf(subject), firstStudyDay, mapOf("정석" to 1)).log

        assertEquals(Mark.PARTIAL, log.mark, "1/2 = 50% 라 △")
        assertEquals(2, log.tasksTotal)
    }

    @Test
    fun `두 줄을 다 하면 동그라미다`() {
        val checklist = jeongseok().toChecklist()
        val subject = checklist.toSubject("정석", required = true)
        val firstStudyDay = checklist.first { !it.isRestDay }.date

        val log = engine.settle(Account(), listOf(subject), firstStudyDay, mapOf("정석" to 2)).log

        assertEquals(Mark.FULL, log.mark)
        assertEquals(1, subject.streak)
    }

    @Test
    fun `아무것도 안 하면 가위표다`() {
        val checklist = jeongseok().toChecklist()
        val subject = checklist.toSubject("정석", required = true)
        val firstStudyDay = checklist.first { !it.isRestDay }.date

        val log = engine.settle(Account(), listOf(subject), firstStudyDay, emptyMap()).log

        assertEquals(Mark.NONE, log.mark, "0/2 = 0% 라 ✕")
    }

    @Test
    fun `버퍼일에는 자동으로 가위표가 안 찍힌다`() {
        // 기획 6.2 수정 ②가 막으려던 바로 그 문제
        val a = jeongseok()
        val checklist = a.toChecklist()
        val subject = checklist.toSubject("정석", required = true)
        val acc = Account()

        val log = engine.settle(acc, listOf(subject), a.bufferDays.first(), emptyMap()).log

        assertEquals(Mark.REST, log.mark, "체크할 게 없는 날은 휴식이다")
        assertEquals(0, acc.overall, "휴식일은 늘지도 줄지도 않는다")
    }

    // ── 한 줄이면 왜 안 되는가 ─────────────────────────────────

    @Test
    fun `한 줄로 만들면 절반을 해도 가위표다`() {
        // A1 을 두 줄로 정한 이유. 기획 5.2가 태스크 단위 판정으로 바꾼 문제가
        // 자동 분배 플랜에서 되살아난다.
        val checklist = jeongseok().toChecklist(tasksPerDay = 1)
        val subject = checklist.toSubject("정석", required = true)
        val firstStudyDay = checklist.first { !it.isRestDay }.date

        assertEquals(7, checklist.first().tasks.single().amount, "7페이지가 한 줄")

        // 6페이지를 읽어도 그 한 줄을 못 끝냈으면 체크가 0이다
        val log = engine.settle(Account(), listOf(subject), firstStudyDay, emptyMap()).log
        assertEquals(Mark.NONE, log.mark, "전부 아니면 전무 — △가 나올 수 없다")
    }
}
