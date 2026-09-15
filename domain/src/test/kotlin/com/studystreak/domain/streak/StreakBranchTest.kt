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
 * streak_sim.py 의 검증 22개가 **코드로는 지나가지만 한 번도 단언하지 않은** 경로들.
 *
 * 전부 파이썬 구현을 돌려서 기대값을 뽑았고, 시나리오 129개 × 최대 200일
 * (정산 24,201건 + 조각·광고 13,403건)을 차분 비교해 두 구현이 같다는 걸 확인한 뒤 고정했다.
 * 규칙을 새로 정한 게 아니라, 이미 있던 동작에 자물쇠를 채운 것이다.
 */
class StreakBranchTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    private val start: LocalDate = LocalDate.of(2026, 9, 7)
    private val everyDay = DayOfWeek.entries.toSet()

    private fun day(offset: Int) = start.plusDays(offset.toLong())

    /** 태스크 3개짜리 필수 1과목을 [days]일 돌리되, [gap]일마다 1개만 해서 △를 만든다. */
    private fun runWithPartialEvery(gap: Int, days: Int): Pair<Account, Int> {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = everyDay, tasks = 3))
        var resets = 0
        for (i in 0 until days) {
            val r = engine.settle(acc, subs, day(i), mapOf("수학" to if (i % gap == 0) 1 else 3))
            if (r.event is SettleEvent.PartialReset) resets++
        }
        return acc to resets
    }

    // ── △ 창(7일)의 실제 경계 ────────────────────────────────────
    // 창이 7일인데 △ 3개가 들어가려면 간격이 3일 이하여야 한다.

    @Test
    fun `△ 간격이 4일이면 60일 동안 한 번도 안 끊긴다`() {
        val (acc, resets) = runWithPartialEvery(gap = 4, days = 60)

        assertEquals(0, resets, "창에 △가 최대 2개라 3개째가 안 나온다")
        assertEquals(60, acc.overall)
        assertEquals(60, acc.longest)
    }

    @Test
    fun `△ 간격이 3일이면 초기화된다`() {
        val (acc, resets) = runWithPartialEvery(gap = 3, days = 60)

        assertEquals(6, resets, "간격 3일이 초기화가 일어나는 경계")
        assertEquals(8, acc.overall)
        assertEquals(8, acc.longest)
    }

    @Test
    fun `매일 30%만 하면 사흘마다 초기화된다`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = everyDay, tasks = 10))
        val resetDays = mutableListOf<Int>()
        for (i in 0 until 60) {
            val r = engine.settle(acc, subs, day(i), mapOf("수학" to 3)) // 딱 30%
            if (r.event is SettleEvent.PartialReset) resetDays += i + 1
        }

        assertEquals(listOf(3, 6, 9, 12, 15), resetDays.take(5))
        assertEquals(20, resetDays.size, "60일에 20번")
        assertEquals(2, acc.longest, "△만으로는 최장 2일을 못 넘는다")
    }

    // ── 과목 프리즈 적립과 상한 ─────────────────────────────────

    @Test
    fun `과목 프리즈는 7일마다 1개씩, 2개에서 멈춘다`() {
        val acc = Account()
        val math = Subject("수학", required = true, weekdays = everyDay, tasks = 1)
        val snapshots = mutableListOf<Triple<Int, Int, Int>>()
        for (i in 1..21) {
            engine.settle(acc, listOf(math), day(i - 1), mapOf("수학" to 1))
            if (i % 7 == 0) snapshots += Triple(i, math.streak, math.freeze)
        }

        assertEquals(
            listOf(Triple(7, 7, 1), Triple(14, 14, 2), Triple(21, 21, 2)),
            snapshots,
            "21일째에도 3개가 되면 안 된다",
        )
    }

    // ── 배정량보다 많이 체크했을 때 ─────────────────────────────

    @Test
    fun `배정량을 넘겨 체크해도 비율은 배정량까지만 센다`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = everyDay, tasks = 2),
            Subject("영어", required = true, weekdays = everyDay, tasks = 8),
        )
        // min(99,2) + min(1,8) = 3 / 10 = 30% → △
        val log = engine.settle(acc, subs, start, mapOf("수학" to 99, "영어" to 1)).log

        assertEquals(Mark.PARTIAL, log.mark)
        assertEquals(2, log.due)
        assertEquals(1, log.done, "수학만 완료")
        assertEquals(3, log.tasksChecked, "99가 2로 잘려야 한다")
        assertEquals(10, log.tasksTotal)
    }

    @Test
    fun `한 과목을 아무리 많이 해도 다른 과목 몫을 대신 못 채운다`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = everyDay, tasks = 2),
            Subject("영어", required = true, weekdays = everyDay, tasks = 8),
        )
        // min(99,2) = 2 / 10 = 20% → ✕
        val log = engine.settle(acc, subs, start, mapOf("수학" to 99)).log

        assertEquals(Mark.NONE, log.mark)
        assertEquals(2, log.tasksChecked)
    }

    // ── 필수 과목이 없는 계정 ───────────────────────────────────

    @Test
    fun `과목이 하나도 없으면 전체는 휴식이다`() {
        val acc = Account()
        val log = engine.settle(acc, emptyList(), start, emptyMap()).log

        assertEquals(Mark.REST, log.mark)
        assertEquals(0, log.due)
        assertEquals(0, acc.overall)
    }

    @Test
    fun `선택 과목만 있으면 과목 streak 은 돌아도 전체는 안 는다`() {
        val acc = Account()
        val history = Subject("한국사", required = false, weekdays = everyDay, tasks = 2)

        val miss = engine.settle(acc, listOf(history), start, mapOf("한국사" to 0)).log
        assertEquals(Mark.REST, miss.mark)
        assertEquals(0, history.streak)

        val hit = engine.settle(acc, listOf(history), day(1), mapOf("한국사" to 2)).log
        assertEquals(Mark.REST, hit.mark, "선택 과목을 다 해도 전체는 휴식")
        assertEquals(1, history.streak, "과목 streak 은 정상 작동한다")
        assertEquals(0, acc.overall, "전체 streak 은 끝까지 0")
    }

    // ── 두 프리즈가 따로 닳는다 ─────────────────────────────────

    @Test
    fun `전체 프리즈와 과목 프리즈는 각자 닳는다`() {
        val acc = Account(overall = 9, longest = 9, freeze = 1)
        val math = Subject(
            "수학", required = true, weekdays = everyDay, tasks = 2,
            streak = 9, longest = 9, freeze = 2,
        )
        val subs = listOf(math)

        val d1 = engine.settle(acc, subs, day(0), emptyMap())
        assertEquals(SettleEvent.FreezeDefended, d1.event)
        assertEquals(9, acc.overall, "방어한 날은 그 자리")
        assertEquals(0, acc.freeze)
        assertEquals(9, math.streak)
        assertEquals(1, math.freeze, "과목 프리즈도 하루치 닳는다")

        val d2 = engine.settle(acc, subs, day(1), emptyMap())
        assertEquals(SettleEvent.ResetWithoutFreeze, d2.event)
        assertEquals(0, acc.overall, "전체는 프리즈가 떨어져 초기화")
        assertEquals(9, math.streak, "과목은 남은 프리즈가 지켜준다")
        assertEquals(0, math.freeze)

        engine.settle(acc, subs, day(2), emptyMap())
        assertEquals(0, math.streak, "과목 프리즈도 떨어지면 과목이 끊긴다")
    }

    // ── ✕ 초기화가 창을 비우는 것의 실제 효과 ───────────────────

    @Test
    fun `✕ 초기화는 창 안의 △를 전부 소진 처리한다`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = everyDay, tasks = 3))

        engine.settle(acc, subs, day(0), mapOf("수학" to 1)) // △
        engine.settle(acc, subs, day(1), mapOf("수학" to 1)) // △
        assertEquals(0, acc.logs.count { it.consumed })
        assertEquals(2, acc.overall)

        engine.settle(acc, subs, day(2), emptyMap()) // ✕ → 초기화
        assertEquals(2, acc.logs.count { it.consumed }, "창의 △ 2개가 소진돼야 한다")
        assertEquals(0, acc.overall)

        // 소진됐으니 새 △ 3개를 다시 채워야 초기화된다
        engine.settle(acc, subs, day(3), mapOf("수학" to 1))
        assertEquals(1, acc.overall)
        engine.settle(acc, subs, day(4), mapOf("수학" to 1))
        assertEquals(2, acc.overall)
        val third = engine.settle(acc, subs, day(5), mapOf("수학" to 1))
        assertTrue(third.event is SettleEvent.PartialReset, "여기서 비로소 3개째")
        assertEquals(0, acc.overall)
        assertEquals(5, acc.logs.count { it.consumed })
    }
}
