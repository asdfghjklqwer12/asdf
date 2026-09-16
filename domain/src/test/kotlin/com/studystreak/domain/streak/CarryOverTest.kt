package com.studystreak.domain.streak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 이월 규칙 — △·✕인 날 못 한 태스크가 다음 학습일로 넘어간다.
 *
 * 사용자가 계획을 다시 짜면 이월이 지워지고, 안 짜면 다음 날에 밀린 것과 그날 것을
 * **전부** 해야 완료다. 파이썬 원본에는 없는 규칙이라 과목별 옵트인으로 붙였다.
 */
class CarryOverTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))

    /** 2026-09-07 은 월요일 */
    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private val monToFri = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun day(offset: Int) = monday.plusDays(offset.toLong())

    private fun math(
        tasks: Int = 3,
        /** 기본값은 Subject 의 기본값과 같은 1배 상한 */
        cap: Int? = 1,
        carryOver: Boolean = true,
        streak: Int = 0,
        freeze: Int = 0,
    ) = Subject(
        "수학", required = true, weekdays = monToFri, tasks = tasks,
        carryOver = carryOver, carryOverCap = cap, streak = streak, freeze = freeze,
    )

    // ── 기본 동작 ────────────────────────────────────────────────

    @Test
    fun `못 한 태스크가 다음 날로 넘어간다`() {
        val acc = Account()
        val subject = math()

        val r = engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1))

        assertEquals(Mark.PARTIAL, r.log.mark, "3개 중 1개 = 33% 라 △")
        assertEquals(2, subject.carriedTasks, "못 한 2개가 넘어간다")
        assertEquals(5, subject.tasksOn(day(1)), "화요일은 원래 3개 + 이월 2개")
    }

    @Test
    fun `밀린 것까지 다 해야 완료다`() {
        val acc = Account()
        val subject = math()
        engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1)) // 2개 이월

        // 화요일에 원래 분량 3개만 해도 아직 완료가 아니다
        val r = engine.settle(acc, listOf(subject), day(1), mapOf("수학" to 3))

        assertEquals(Mark.PARTIAL, r.log.mark, "5개 중 3개 = 60% 라 아직 △")
        assertEquals(0, subject.streak, "과목 완료가 아니다")
        assertEquals(2, subject.carriedTasks, "여전히 2개가 남아 또 넘어간다")
    }

    @Test
    fun `밀린 것까지 다 하면 이월이 사라진다`() {
        val acc = Account()
        val subject = math()
        engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1)) // 2개 이월

        val r = engine.settle(acc, listOf(subject), day(1), mapOf("수학" to 5))

        assertEquals(Mark.FULL, r.log.mark, "5개를 다 했으니 ○")
        assertEquals(1, subject.streak)
        assertEquals(0, subject.carriedTasks)
        assertEquals(3, subject.tasksOn(day(2)), "수요일은 원래 분량으로 돌아온다")
        assertTrue(r.carriedOver.isEmpty())
    }

    @Test
    fun `상한을 풀면 계속 쌓인다`() {
        val acc = Account()
        val subject = math(cap = null)
        val piled = mutableListOf<Int>()

        for (i in 0 until 3) {
            engine.settle(acc, listOf(subject), day(i), emptyMap())
            piled += subject.carriedTasks
        }

        assertEquals(listOf(3, 6, 9), piled, "하루에 3개씩 불어난다")
        assertEquals(12, subject.tasksOn(day(3)), "목요일엔 원래 3개 + 이월 9개")
    }

    @Test
    fun `기본값은 하루치까지만 쌓인다`() {
        val acc = Account()
        val subject = Subject("수학", required = true, weekdays = monToFri, tasks = 3, carryOver = true)
        assertEquals(1, subject.carryOverCap, "기본 상한은 원래 하루치의 1배")

        val piled = mutableListOf<Int>()
        for (i in 0 until 3) {
            engine.settle(acc, listOf(subject), day(i), emptyMap())
            piled += subject.carriedTasks
        }

        assertEquals(listOf(3, 3, 3), piled, "내일은 아무리 밀려도 최대 이틀치다")
        assertEquals(6, subject.tasksOn(day(3)))
    }

    @Test
    fun `상한을 넘친 몫은 계획에서 사라지고 그걸 알려준다`() {
        val acc = Account()
        val subject = math(tasks = 3) // 기본 상한 1배 = 3개

        val first = engine.settle(acc, listOf(subject), monday, emptyMap())
        assertEquals(mapOf("수학" to 3), first.carriedOver)
        assertTrue(first.carryDropped.isEmpty(), "첫날은 아직 상한에 안 닿는다")

        // 이튿날 배정은 6개. 하나도 안 하면 6개가 밀리는데 3개까지만 넘어간다
        val second = engine.settle(acc, listOf(subject), day(1), emptyMap())

        assertEquals(mapOf("수학" to 3), second.carriedOver)
        assertEquals(mapOf("수학" to 3), second.carryDropped, "넘친 3개는 재분배가 맡아야 한다")
        assertEquals(3, subject.carriedTasks)
    }

    @Test
    fun `상한이 무제한이면 아무것도 사라지지 않는다`() {
        val acc = Account()
        val subject = math(cap = null)

        val results = (0 until 3).map { engine.settle(acc, listOf(subject), day(it), emptyMap()) }

        assertTrue(results.all { it.carryDropped.isEmpty() }, "무제한이면 전부 다음 날로 간다")
        assertEquals(9, subject.carriedTasks)
    }

    // ── 사용자가 계획을 다시 짜는 경우 ──────────────────────────

    @Test
    fun `계획을 다시 짜면 쌓인 이월이 지워진다`() {
        val acc = Account()
        val subject = math(cap = null)
        for (i in 0 until 3) engine.settle(acc, listOf(subject), day(i), emptyMap())
        assertEquals(9, subject.carriedTasks)

        subject.clearCarryOver() // 사용자가 "조정할게요" 를 골랐다

        assertEquals(0, subject.carriedTasks)
        assertEquals(3, subject.tasksOn(day(3)), "목요일은 원래 분량뿐")
    }

    @Test
    fun `이월된 내역을 정산 결과로 알려준다`() {
        val acc = Account()
        val subs = listOf(
            math(tasks = 3),
            Subject("영어", required = true, weekdays = monToFri, tasks = 4, carryOver = true),
        )

        val r = engine.settle(acc, subs, monday, mapOf("수학" to 1, "영어" to 4))

        // 사용자에게 "수학 2개가 다음 학습일로 넘어갑니다" 를 물을 때 쓸 정보
        assertEquals(mapOf("수학" to 2), r.carriedOver, "다 한 영어는 안 들어간다")
    }

    // ── 휴식일 ──────────────────────────────────────────────────

    @Test
    fun `휴식일은 건너뛰고 다음 학습일에 얹힌다`() {
        val acc = Account()
        val subject = math()
        val friday = day(4)

        engine.settle(acc, listOf(subject), friday, mapOf("수학" to 1)) // 2개 이월
        val saturday = engine.settle(acc, listOf(subject), day(5), emptyMap())

        assertEquals(Mark.REST, saturday.mark(), "토요일은 그대로 휴식")
        assertEquals(2, subject.carriedTasks, "휴식일에는 쌓이지도 줄지도 않는다")
        assertEquals(5, subject.tasksOn(day(7)), "다음 월요일에 얹힌다")
    }

    private fun SettleResult.mark() = log.mark

    // ── 30% 판정 ────────────────────────────────────────────────

    @Test
    fun `이월분도 30퍼센트 판정의 분모에 들어간다`() {
        val acc = Account()
        val subject = math(tasks = 10)
        engine.settle(acc, listOf(subject), monday, emptyMap()) // 10개 이월, ✕

        // 화요일 배정은 20개. 6개는 30% 라 △ — 원래 분량 10개 기준이었다면 60% 였을 것이다
        val r = engine.settle(acc, listOf(subject), day(1), mapOf("수학" to 6))

        assertEquals(Mark.PARTIAL, r.log.mark)
        assertEquals(20, r.log.tasksTotal, "분모가 이월을 포함한 20개")
        assertEquals(6, r.log.tasksChecked)
        assertEquals(10, subject.carriedTasks, "남은 14개 중 상한(1배 = 10개)까지만 넘어간다")
        assertEquals(mapOf("수학" to 4), r.carryDropped, "넘친 4개는 계획에서 사라진다")
    }

    @Test
    fun `이월이 쌓이면 같은 양을 해도 ✕로 떨어진다`() {
        val acc = Account()
        val subject = math(tasks = 10)
        // 매일 3개씩만 하는 사용자 — 이월이 없었다면 계속 30%로 △였다
        val marks = mutableListOf<Mark>()
        for (i in 0 until 4) {
            marks += engine.settle(acc, listOf(subject), day(i), mapOf("수학" to 3)).log.mark
        }

        assertEquals(Mark.PARTIAL, marks[0], "첫날은 3/10 = 30%")
        assertEquals(Mark.NONE, marks[1], "이튿날은 3/17 = 17%")
        assertTrue(marks.drop(1).all { it == Mark.NONE }, "쌓일수록 비율이 떨어져 ✕가 된다")
    }

    // ── 상한 ────────────────────────────────────────────────────

    @Test
    fun `상한을 주면 거기서 멈춘다`() {
        val acc = Account()
        val subject = math(tasks = 3, cap = 2) // 원래 분량의 2배 = 6개까지
        val piled = mutableListOf<Int>()

        for (i in 0 until 4) {
            engine.settle(acc, listOf(subject), day(i), emptyMap())
            piled += subject.carriedTasks
        }

        assertEquals(listOf(3, 6, 6, 6), piled, "6개에서 더 안 쌓인다")
        assertEquals(9, subject.tasksOn(day(4)), "그래도 그날은 원래 3개 + 이월 6개")
    }

    // ── 다른 규칙과의 관계 ──────────────────────────────────────

    @Test
    fun `과목 프리즈가 streak을 지켜줘도 이월은 쌓인다`() {
        val acc = Account()
        val subject = math(streak = 5, freeze = 1)

        engine.settle(acc, listOf(subject), monday, emptyMap())

        assertEquals(5, subject.streak, "프리즈가 과목 streak 을 지켰다")
        assertEquals(0, subject.freeze, "프리즈는 소진됐다")
        assertEquals(3, subject.carriedTasks, "공부는 안 했으니 진도는 그대로 넘어간다")
    }

    @Test
    fun `배정량을 넘겨 체크해도 이월은 음수가 되지 않는다`() {
        val acc = Account()
        val subject = math()

        val r = engine.settle(acc, listOf(subject), monday, mapOf("수학" to 99))

        assertEquals(Mark.FULL, r.log.mark)
        assertEquals(0, subject.carriedTasks)
    }

    @Test
    fun `재정산해도 이월이 두 번 쌓이지 않는다`() {
        val acc = Account()
        val subject = math()

        engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1))
        val again = engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1))

        assertTrue(again.alreadySettled)
        assertEquals(2, subject.carriedTasks, "2개 그대로")
        assertTrue(again.carriedOver.isEmpty(), "재정산은 아무것도 안 넘긴다")
    }

    @Test
    fun `이월을 안 켠 과목은 예전과 똑같이 돈다`() {
        val acc = Account()
        val subject = math(carryOver = false)

        engine.settle(acc, listOf(subject), monday, mapOf("수학" to 1))

        assertEquals(0, subject.carriedTasks)
        assertEquals(3, subject.tasksOn(day(1)), "화요일은 원래 분량 그대로")
    }

    @Test
    fun `과목마다 따로 켤 수 있다`() {
        val acc = Account()
        val withCarry = math(tasks = 3)
        val without = Subject("영어", required = true, weekdays = monToFri, tasks = 3, carryOver = false)

        engine.settle(acc, listOf(withCarry, without), monday, emptyMap())

        assertEquals(3, withCarry.carriedTasks)
        assertEquals(0, without.carriedTasks)
        assertEquals(mapOf("수학" to 3), engineCarry(acc, withCarry, without))
    }

    /** 다음 날 정산에서 어느 과목이 이월을 내는지 */
    private fun engineCarry(acc: Account, vararg subs: Subject): Map<String, Int> =
        engine.settle(acc, subs.toList(), day(1), subs.associate { it.name to it.baseTasksOn(day(1)) })
            .carriedOver

    // ── 당일 계획 과목 ──────────────────────────────────────────

    @Test
    fun `당일 계획도 이월을 켤 수 있다`() {
        val acc = Account()
        val today = Subject(
            "오늘 계획", required = true, sameDay = true,
            daily = mapOf(monday to 4, day(2) to 2), // 화요일은 계획을 안 넣었다
            carryOver = true,
        )

        engine.settle(acc, listOf(today), monday, mapOf("오늘 계획" to 1))
        assertEquals(3, today.carriedTasks)

        val tuesday = engine.settle(acc, listOf(today), day(1), emptyMap())
        assertEquals(Mark.REST, tuesday.log.mark, "계획을 안 넣은 날은 휴식이라 이월이 안 얹힌다")
        assertEquals(3, today.carriedTasks)

        assertEquals(5, today.tasksOn(day(2)), "수요일에 원래 2개 + 이월 3개")
    }
}
