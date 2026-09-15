package com.studystreak.domain.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * allocation.py 의 검증 16개가 **코드로는 지나가지만 한 번도 단언하지 않은** 경로들.
 *
 * 전부 파이썬 구현을 돌려서 기대값을 뽑았고, 무작위 1,500조합 차분 비교
 * (allocate 1,123건 + redistribute 17,600건)로 두 구현이 같다는 걸 확인한 뒤 고정한 것이다.
 * 규칙을 새로 정한 게 아니라, 이미 있던 동작에 자물쇠를 채운 것이다.
 */
class PlanBranchTest {

    private val wd = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun baseCase(): Allocation =
        allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), wd, dailyMax = 10)

    // ── 제외일 — 파이썬 검증이 한 번도 넘기지 않은 인자 ──────────

    @Test
    fun `제외일은 학습 가능일에서 빠진다`() {
        val excluded = setOf(
            LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 16),
        )
        val withOut = allocate(100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 30), wd)
        val a = allocate(100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 30), wd, excluded)

        assertEquals(18, withOut.studyDayCount, "제외 없으면 평일 18일")
        assertEquals(15, a.studyDayCount, "제외 3일이 빠져 15일")
        assertTrue(a.days.none { it in excluded }, "제외일이 학습일 목록에 남아 있다")
    }

    @Test
    fun `제외일이 있어도 배분 총합은 정확하다`() {
        val excluded = setOf(
            LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 16),
        )
        val a = allocate(100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 30), wd, excluded)

        assertEquals(100, a.plan.values.sum())
        assertEquals(3, a.bufferDayCount)
        assertEquals(12, a.allocDayCount)
        assertEquals(8, a.base)
        assertEquals(4, a.remainder)
        assertEquals(9, a.peak)
    }

    @Test
    fun `제외일이 있어도 버퍼는 기간 전체에 흩어진다`() {
        val excluded = setOf(
            LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 16),
        )
        val a = allocate(100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 30), wd, excluded)

        assertEquals(
            listOf(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28)),
            a.bufferDays,
            "제외일을 뺀 뒤의 학습일 위에 고르게 놓여야 한다",
        )
    }

    // ── 재분배가 아무것도 하지 않아야 하는 경우 ──────────────────

    @Test
    fun `계획보다 앞서가고 있으면 재분배가 아무것도 안 바꾼다`() {
        val a = baseCase()
        val cut = a.days[9]
        val planned = a.days.take(10).sumOf { a.plan.getValue(it) }
        val r = redistribute(a, cut, doneAmount = planned + 50, dailyMax = 10)

        assertEquals(0, r.behind, "앞서가도 behind 는 음수가 아니라 0")
        assertEquals(0, r.usedBufferDays)
        assertEquals(0, r.addedPerDay)
        assertTrue(r.warnings.isEmpty())
        assertEquals(a.plan, r.plan, "계획이 그대로여야 한다")
    }

    @Test
    fun `계획만큼 딱 했으면 재분배가 아무것도 안 바꾼다`() {
        val a = baseCase()
        val cut = a.days[9]
        val planned = a.days.take(10).sumOf { a.plan.getValue(it) }
        val r = redistribute(a, cut, doneAmount = planned, dailyMax = 10)

        assertEquals(0, r.behind)
        assertEquals(a.plan, r.plan)
    }

    // ── 남은 날이 없을 때 ────────────────────────────────────────

    @Test
    fun `마지막 날까지 밀리면 갈 곳이 없다고 경고한다`() {
        val a = baseCase()
        val r = redistribute(a, doneThrough = a.days.last(), doneAmount = 0, dailyMax = 10)

        assertEquals(480, r.behind, "총분량 전체가 밀린 셈")
        assertEquals(listOf(PlanWarning.NoDaysLeftToRedistribute), r.warnings)
        assertEquals(0, r.usedBufferDays)
        assertEquals(0, r.addedPerDay)
        assertEquals(a.plan, r.plan, "밀린 몫이 갈 곳이 없어 계획은 그대로다")
    }

    // ── 여러 번 밀렸을 때 ───────────────────────────────────────

    @Test
    fun `재분배를 반복해도 남은 계획은 실제 남은 분량과 같다`() {
        // 사용자는 한 번만 밀리지 않는다. 매번 **원본** 계획에서 누적 완료량으로 다시 계산한다.
        val a = baseCase()
        val days = a.days

        for ((idx, doneSoFar) in listOf(9 to 28, 24 to 95, 44 to 219)) {
            val cut = days[idx]
            val r = redistribute(a, cut, doneAmount = doneSoFar, dailyMax = 10)
            val remain = days.filter { it.isAfter(cut) }.sumOf { r.plan.getValue(it) }

            assertEquals(480 - doneSoFar, remain, "${idx + 1}일째 누적 ${doneSoFar}p 완료")
        }
    }

    @Test
    fun `직전 재분배 결과 위에 또 재분배하면 분량이 부풀려진다`() {
        // 이러면 안 된다는 것을 고정해 둔다. 재분배는 못 한 몫을 남은 날에 **더하므로**,
        // 결과 계획 위에서 또 재분배하면 이미 밀어둔 몫을 한 번 더 센다.
        val a = baseCase()
        val days = a.days

        val r1 = redistribute(a, days[9], doneAmount = 28, dailyMax = 10)
        val chained = redistribute(a.copy(plan = r1.plan), days[24], doneAmount = 95, dailyMax = 10)
        val fromOriginal = redistribute(a, days[24], doneAmount = 95, dailyMax = 10)

        val cut = days[24]
        val chainedRemain = days.filter { it.isAfter(cut) }.sumOf { chained.plan.getValue(it) }
        val correctRemain = days.filter { it.isAfter(cut) }.sumOf { fromOriginal.plan.getValue(it) }

        assertEquals(385, correctRemain, "원본에서 계산하면 480 - 95 = 385")
        assertEquals(413, chainedRemain, "연쇄로 부르면 28p 더 요구한다 — 1회차 밀림을 두 번 센 것")
        assertTrue(chainedRemain > correctRemain, "연쇄가 항상 더 많이 요구한다")
    }

    // ── 기본 하루치가 0일 때 ────────────────────────────────────

    @Test
    fun `기본 하루치가 0이면 버퍼에 하루 1씩만 채운다`() {
        // 5강을 3개월에 — 배분일보다 총분량이 적어 base 가 0이 된다
        val a = allocate(5, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 12, 31), wd)
        assertEquals(0, a.base, "이 경우에만 unit 이 1로 떨어진다")

        val cut = a.days[2]
        val r = redistribute(a, cut, doneAmount = 0)

        assertEquals(3, r.behind)
        assertEquals(3, r.usedBufferDays, "base 가 0이라 하루 1씩, 3일을 쓴다")
        assertEquals(0, r.addedPerDay)
        assertTrue(
            a.days.filter { it.isAfter(cut) && r.plan.getValue(it) != a.plan.getValue(it) }
                .all { r.plan.getValue(it) == 1 },
            "채워 넣은 날은 전부 1이어야 한다",
        )
    }

    // ── 총분량이 0일 때 ─────────────────────────────────────────

    @Test
    fun `총분량이 0이면 학습일이 전부 휴식일이 된다`() {
        val a = allocate(0, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 31), wd)

        assertEquals(0, a.plan.values.sum())
        assertEquals(0, a.peak)
        assertEquals(0, a.base)
        assertEquals(0, a.remainder)
        assertEquals(a.studyDayCount, a.restDays.size, "체크할 게 없으니 전부 휴식일")
        assertTrue(a.warnings.any { it is PlanWarning.TotalBelowAllocDays })
    }
}
