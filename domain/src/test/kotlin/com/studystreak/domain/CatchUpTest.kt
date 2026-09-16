package com.studystreak.domain

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.Mark
import com.studystreak.domain.streak.StreakEngine
import com.studystreak.domain.streak.Subject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 밀린 날의 두 갈래 — [CatchUpChoice.Replan] 과 [CatchUpChoice.DoTomorrow].
 *
 * 사용자가 카드에서 고르는 두 선택지를 도메인이 안전하게 적용하는지 본다.
 */
class CatchUpTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    private val monToFri = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    /** 정석 480페이지 · 9/9 ~ 12/31 · 평일 (기획 6.2의 예시) */
    private fun plan() =
        allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), monToFri, dailyMax = 10)

    private fun math() = Subject(
        "수학", required = true, weekdays = monToFri, tasks = 3, carryOver = true,
    )

    /** 밀린 상태를 만든다 — 월요일에 3개 중 1개만 함 */
    private fun fallBehind(subject: Subject): LocalDate {
        val monday = LocalDate.of(2026, 9, 7)
        engine.settle(Account(), listOf(subject), monday, mapOf("수학" to 1))
        return monday
    }

    // ── 내일 같이 하기 ──────────────────────────────────────────

    @Test
    fun `내일 같이 하기는 계획을 안 건드린다`() {
        val a = plan()
        val subject = math()
        val today = fallBehind(subject)

        val result = applyCatchUp(CatchUpChoice.DoTomorrow, a, subject, today, doneAmount = 20, dailyMax = 10)

        assertNull(result, "재분배가 일어나지 않는다")
        assertEquals(2, subject.carriedTasks, "밀린 2개가 그대로 내일로 간다")
        assertEquals(5, subject.tasksOn(today.plusDays(1)), "내일은 원래 3개 + 이월 2개")
    }

    // ── 계획 조정 ───────────────────────────────────────────────

    @Test
    fun `계획 조정은 남은 학습일에 다시 펴고 이월을 지운다`() {
        val a = plan()
        val subject = math()
        val today = fallBehind(subject)
        assertEquals(2, subject.carriedTasks, "고르기 전에는 이월이 있다")

        val cut = a.days[9]
        val planned = a.days.take(10).sumOf { a.plan.getValue(it) }
        val result = applyCatchUp(CatchUpChoice.Replan, a, subject, cut, doneAmount = planned - 21, dailyMax = 10)

        checkNotNull(result)
        assertEquals(21, result.behind, "밀린 21페이지가 남은 날에 펴진다")
        assertEquals(0, subject.carriedTasks, "이월은 지워진다")
        assertEquals(3, subject.tasksOn(today.plusDays(1)), "내일은 원래 분량으로 돌아온다")
    }

    @Test
    fun `계획 조정은 밀린 몫을 두 번 요구하지 않는다`() {
        // 재분배만 하고 이월을 안 지우면, 같은 몫이 남은 날들에 펴지면서 내일에도 얹힌다.
        // applyCatchUp 이 그걸 막는 것이 존재 이유다.
        val a = plan()
        val subject = math()
        fallBehind(subject)

        val cut = a.days[9]
        val planned = a.days.take(10).sumOf { a.plan.getValue(it) }
        val result = applyCatchUp(CatchUpChoice.Replan, a, subject, cut, doneAmount = planned - 21, dailyMax = 10)

        checkNotNull(result)
        val remaining = a.days.filter { it.isAfter(cut) }.sumOf { result.plan.getValue(it) }
        assertEquals(480 - (planned - 21), remaining, "남은 계획 = 총분량 − 실제 완료량")
        assertEquals(0, subject.carriedTasks, "그 위에 이월이 또 얹히면 두 번 요구하는 것이다")
    }

    // ── 두 갈래의 차이 ─────────────────────────────────────────

    @Test
    fun `두 갈래는 내일의 무게가 다르다`() {
        val a = plan()
        val cut = a.days[9]
        val planned = a.days.take(10).sumOf { a.plan.getValue(it) }
        val done = planned - 21

        val replanSubject = math()
        val tomorrowSubject = math()
        val today = fallBehind(replanSubject)
        fallBehind(tomorrowSubject)

        val replanned = applyCatchUp(CatchUpChoice.Replan, a, replanSubject, cut, done, dailyMax = 10)
        applyCatchUp(CatchUpChoice.DoTomorrow, a, tomorrowSubject, cut, done, dailyMax = 10)

        // 계획 조정: 내일은 가볍지만 남은 날들이 조금씩 무거워진다
        assertEquals(3, replanSubject.tasksOn(today.plusDays(1)))
        checkNotNull(replanned)
        assertTrue(replanned.usedBufferDays > 0, "버퍼일이 밀린 몫을 흡수한다")

        // 내일 같이 하기: 내일만 무겁고 계획은 그대로
        assertEquals(5, tomorrowSubject.tasksOn(today.plusDays(1)))
    }

    @Test
    fun `어느 쪽을 골라도 다음 날 판정 규칙은 같다`() {
        // 고르는 건 분량이지 규칙이 아니다. 30% 판정도 완료 기준도 그대로다.
        val a = plan()
        val subject = math()
        val today = fallBehind(subject)
        applyCatchUp(CatchUpChoice.DoTomorrow, a, subject, today, doneAmount = 20, dailyMax = 10)

        val acc = Account()
        // 내일 배정은 5개. 2개는 40% 라 △
        val log = engine.settle(acc, listOf(subject), today.plusDays(1), mapOf("수학" to 2)).log

        assertEquals(Mark.PARTIAL, log.mark)
        assertEquals(5, log.tasksTotal)
        assertEquals(2, log.tasksChecked)
    }
}
