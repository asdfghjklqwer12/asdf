package com.studystreak.domain.streak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * streak_sim.py 의 edge_tests() 22개를 그대로 옮긴 것.
 * 순서·이름·검증 내용 모두 파이썬과 1:1 대응한다.
 */
class StreakEngineTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC))

    /** 월요일 */
    private val start: LocalDate = LocalDate.of(2026, 9, 7)

    private val monToFri = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    private fun fresh(): Pair<Account, List<Subject>> = Account() to listOf(
        Subject("수학", required = true, weekdays = monToFri, tasks = 2),
        Subject("영어", required = true, weekdays = monToFri, tasks = 2),
    )

    /** 둘 다 전부 완료 */
    private val d2 = mapOf("수학" to 2, "영어" to 2)

    /** 수학만 완료 */
    private val dMath = mapOf("수학" to 2)

    private fun day(offset: Long) = start.plusDays(offset)

    // 1) 초기화 직후 △ 소진 처리가 되는가 (안 되면 새 streak이 △ 한 번에 또 죽는다)
    @Test
    fun `초기화 직후 △ 1개로 또 죽지 않는다`() {
        val (acc, subs) = fresh()
        repeat(3) { engine.settle(acc, subs, day(it.toLong()), dMath) } // 월화수 모두 △
        val afterReset = acc.overall
        engine.settle(acc, subs, day(3), d2)      // 목 ○
        engine.settle(acc, subs, day(4), dMath)   // 금 △

        assertEquals(0, afterReset, "△ 3개째에 초기화되어야 한다")
        assertEquals(2, acc.overall, "초기화 후 $afterReset → ○ → △ 이면 2여야 함, 실제 ${acc.overall}")
    }

    // 2) ✕ 프리즈: 1회 방어는 숫자 유지(+1 아님), 다음 ✕는 즉시 초기화
    @Test
    fun `✕ 방어는 +1이 아니라 그 자리 유지`() {
        val (acc, subs) = fresh()
        repeat(3) { engine.settle(acc, subs, day(it.toLong()), d2) }
        val before = acc.overall
        acc.freeze = 1
        engine.settle(acc, subs, day(3), emptyMap())   // 목 ✕ (방어)
        val held = acc.overall

        assertEquals(before, held, "$before → $held")
    }

    @Test
    fun `프리즈 소진 후 다음 ✕는 즉시 0`() {
        val (acc, subs) = fresh()
        repeat(3) { engine.settle(acc, subs, day(it.toLong()), d2) }
        acc.freeze = 1
        engine.settle(acc, subs, day(3), emptyMap())   // 목 ✕ (방어)
        engine.settle(acc, subs, day(4), emptyMap())   // 금 ✕ (초기화)

        assertEquals(0, acc.overall, "실제 ${acc.overall}")
    }

    // 3) 휴식일은 중립 — ○도 △도 아니고 끊기지도 않는다
    @Test
    fun `휴식일은 rest, 숫자 변화 없음`() {
        val (acc, subs) = fresh()
        repeat(3) { engine.settle(acc, subs, day(it.toLong()), d2) }
        val before = acc.overall
        val sat = day(5)
        val log = engine.settle(acc, subs, sat, emptyMap()).log

        assertEquals(Mark.REST, log.mark, "실제 ${log.mark}")
        assertEquals(before, acc.overall, "$before→${acc.overall}")
    }

    // 4) 선택 과목은 전체 판정에서 빠진다
    @Test
    fun `선택 과목 미완은 △를 만들지 않는다`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = monToFri, tasks = 2),
            Subject("한국사", required = false, weekdays = monToFri, tasks = 1),
        )
        val log = engine.settle(acc, subs, start, mapOf("수학" to 2)).log // 필수만 완료, 선택은 안 함

        assertEquals(Mark.FULL, log.mark, "실제 ${log.mark}")
    }

    // 5) 과목 프리즈가 지켜준 과목은 전체 판정에서 완료가 아니다
    @Test
    fun `과목 프리즈로 지켜진 날은 전체에서 △`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = monToFri, tasks = 2, freeze = 1),
            Subject("영어", required = true, weekdays = monToFri, tasks = 2),
        )
        val log = engine.settle(acc, subs, start, mapOf("영어" to 2)).log

        assertEquals(Mark.PARTIAL, log.mark, "mark=${log.mark}, 수학 streak=${subs[0].streak}")
        assertEquals(0, subs[0].streak, "과목 프리즈는 streak을 지키지만 완료로 치지 않는다")
    }

    // 6) 광고: 하루 2회까지, 2회당 조각 1개
    @Test
    fun `광고는 하루 2회까지`() {
        val acc = Account()
        val r = List(3) { acc.watchAd(start) }

        assertTrue(r[2].rejected, "$r")
        assertEquals(ShardResult.Rejected(ShardResult.Rejected.Reason.DAILY_AD_LIMIT), r[2])
    }

    @Test
    fun `광고 2회 = 조각 1개 (하루 최대 1조각)`() {
        val acc = Account()
        repeat(3) { acc.watchAd(start) }

        assertEquals(1, acc.shards, "shards=${acc.shards}")
    }

    @Test
    fun `광고만으로는 6일 걸려 프리즈 1개`() {
        val acc = Account()
        for (i in 0 until 6) {
            repeat(2) { acc.watchAd(day(i.toLong())) }
        }

        assertEquals(1, acc.freeze, "freeze=${acc.freeze}, shards=${acc.shards}")
        assertEquals(0, acc.shards, "freeze=${acc.freeze}, shards=${acc.shards}")
    }

    // 7) 프리즈를 이미 보유하면 조각은 5개에서 멈춘다
    @Test
    fun `프리즈 보유 중엔 조각이 5개에서 멈춘다`() {
        val acc = Account(freeze = 1)
        for (i in 0 until 8) {
            repeat(2) { acc.watchAd(day(i.toLong())) }
        }

        assertEquals(1, acc.freeze, "freeze=${acc.freeze}, shards=${acc.shards}")
        assertEquals(StreakRules.SHARD_CAP, acc.shards, "freeze=${acc.freeze}, shards=${acc.shards}")
    }

    // 7b) 프리즈를 쓰고 나면 조각 1개로 바로 다시 채워진다 (대기 상태가 안 생긴다)
    @Test
    fun `프리즈 소진 후 조각 1개로 즉시 재충전`() {
        val acc = Account(freeze = 1)
        for (i in 0 until 8) {
            repeat(2) { acc.watchAd(day(i.toLong())) }
        }
        acc.freeze = 0 // ✕ 방어로 소진했다고 치자
        val result = acc.restCheck(day(20))

        assertEquals(1, acc.freeze, "$result / freeze=${acc.freeze}, shards=${acc.shards}")
        assertEquals(0, acc.shards, "$result / freeze=${acc.freeze}, shards=${acc.shards}")
    }

    // 7c) ✕로 초기화될 때도 창의 △가 소진 처리된다
    @Test
    fun `✕로 초기화된 뒤에도 △ 1개로 또 죽지 않는다`() {
        val (acc, subs) = fresh()
        repeat(2) { engine.settle(acc, subs, day(it.toLong()), dMath) } // 월화 △
        engine.settle(acc, subs, day(2), emptyMap())  // 수 ✕ → 초기화
        engine.settle(acc, subs, day(3), d2)          // 목 ○
        engine.settle(acc, subs, day(4), dMath)       // 금 △

        assertEquals(2, acc.overall, "실제 ${acc.overall} (2여야 함)")
    }

    // 8) 휴식일 조각은 주 3개까지
    @Test
    fun `휴식일 조각은 주 3개까지`() {
        val acc = Account()
        val monday = LocalDate.of(2026, 9, 7)
        val res = List(4) { acc.restCheck(monday.plusDays(it.toLong())) }

        assertTrue(res[3].rejected, "$res")
        assertEquals(ShardResult.Rejected(ShardResult.Rejected.Reason.WEEKLY_REST_LIMIT), res[3])
    }

    // 9) 필수 1과목이어도 태스크를 일부 했으면 △ (✕가 아님)
    @Test
    fun `필수 1과목 + 태스크 일부 완료 → ✕가 아니라 △`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 3))
        val log = engine.settle(acc, subs, start, mapOf("수학" to 1)).log

        assertEquals(Mark.PARTIAL, log.mark, "실제 ${log.mark}")
    }

    // 10) 정말 아무것도 안 했을 때만 ✕
    @Test
    fun `태스크를 하나도 안 하면 ✕`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 3))
        val log = engine.settle(acc, subs, start, emptyMap()).log

        assertEquals(Mark.NONE, log.mark, "실제 ${log.mark}")
    }

    // 11) 30% 미만만 하면 △가 아니라 ✕
    @Test
    fun `태스크 10% 완료는 △가 아니라 ✕`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 10))
        val log = engine.settle(acc, subs, start, mapOf("수학" to 1)).log // 10%

        assertEquals(Mark.NONE, log.mark, "실제 ${log.mark}")
    }

    // 12) 30% 딱 채우면 △
    @Test
    fun `태스크 30% 완료는 △`() {
        val acc = Account()
        val subs = listOf(Subject("수학", required = true, weekdays = monToFri, tasks = 10))
        val log = engine.settle(acc, subs, start, mapOf("수학" to 3)).log // 30%

        assertEquals(Mark.PARTIAL, log.mark, "실제 ${log.mark}")
    }

    // 13) 여러 과목 합산 비율로 판정한다
    @Test
    @DisplayName("과목 합산 12.5%는 ✕")
    fun `과목 합산 12_5%는 ✕`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = monToFri, tasks = 4),
            Subject("영어", required = true, weekdays = monToFri, tasks = 4),
        )
        val log = engine.settle(acc, subs, start, mapOf("수학" to 1)).log // 1/8 = 12.5%

        assertEquals(Mark.NONE, log.mark, "실제 ${log.mark}")
    }

    // 14) 당일 계획: 계획을 안 넣은 날은 휴식 — 끊기지도, 늘지도 않는다
    @Test
    fun `당일 계획을 안 넣은 날은 휴식 (streak 유지)`() {
        val acc = Account()
        val todayPlan = Subject(
            "오늘 계획", required = true, sameDay = true,
            daily = mapOf(start to 3, day(2) to 2),
        )
        val subs = listOf(todayPlan)
        engine.settle(acc, subs, start, mapOf("오늘 계획" to 3)) // 월: 3개 넣고 다 함 → ○
        val a1 = acc.overall
        val log2 = engine.settle(acc, subs, day(1), emptyMap()).log // 화: 계획 안 넣음
        val a2 = acc.overall

        assertEquals(Mark.REST, log2.mark, "mark=${log2.mark}, $a1→$a2")
        assertEquals(1, a1, "mark=${log2.mark}, $a1→$a2")
        assertEquals(1, a2, "mark=${log2.mark}, $a1→$a2")
    }

    @Test
    fun `계획을 다시 넣은 날 streak이 이어진다`() {
        val acc = Account()
        val todayPlan = Subject(
            "오늘 계획", required = true, sameDay = true,
            daily = mapOf(start to 3, day(2) to 2),
        )
        val subs = listOf(todayPlan)
        engine.settle(acc, subs, start, mapOf("오늘 계획" to 3))  // 월 ○
        engine.settle(acc, subs, day(1), emptyMap())              // 화 휴식
        engine.settle(acc, subs, day(2), mapOf("오늘 계획" to 2)) // 수: 2개 넣고 다 함 → ○

        assertEquals(2, acc.overall, "전체 ${acc.overall}, 과목 ${todayPlan.streak}")
        assertEquals(2, todayPlan.streak, "전체 ${acc.overall}, 과목 ${todayPlan.streak}")
    }

    // 15) 당일 계획도 30% 규칙이 그대로 적용된다
    @Test
    fun `당일 계획도 30% 미만이면 ✕`() {
        val acc = Account()
        val tp = Subject("오늘 계획", required = true, sameDay = true, daily = mapOf(start to 10))
        val log = engine.settle(acc, listOf(tp), start, mapOf("오늘 계획" to 2)).log // 20%

        assertEquals(Mark.NONE, log.mark, "실제 ${log.mark}")
    }

    // 16) 기간 플랜과 당일 계획을 같이 돌리면 태스크가 합산된다
    @Test
    fun `기간 플랜 + 당일 계획은 태스크 합산으로 판정`() {
        val acc = Account()
        val subs = listOf(
            Subject("수학", required = true, weekdays = monToFri, tasks = 2),
            Subject("오늘 계획", required = true, sameDay = true, daily = mapOf(start to 2)),
        )
        val log = engine.settle(acc, subs, start, mapOf("수학" to 2)).log // 2/4 = 50%

        assertEquals(Mark.PARTIAL, log.mark, "mark=${log.mark}, due=${log.due}")
        assertEquals(2, log.due, "mark=${log.mark}, due=${log.due}")
    }
}
