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
 * 전체 프리즈가 △ 3개 초기화도 막게 하는 스위치 (A7에서 재보고 **끄기로 한** 것).
 *
 * 기본이 꺼짐이라는 것과, 켰을 때 어떻게 동작하는지를 박아둔다.
 * 기각한 선택지를 나중에 다시 꺼내볼 수 있으려면 동작이 고정돼 있어야 한다.
 */
class FreezePartialSwitchTest {

    private val clock = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC)
    private val off = StreakEngine(clock)
    private val on = StreakEngine(clock, freezeBlocksPartialReset = true)
    private val everyday = DayOfWeek.entries.toSet()
    private val start = LocalDate.of(2026, 9, 1)

    private fun math() = Subject("수학", required = true, weekdays = everyday, tasks = 3)

    /** 5일 채운 뒤 △를 [partials] 번 낸다. 마지막 정산 결과를 돌려준다. */
    private fun threePartials(engine: StreakEngine, acc: Account, subs: List<Subject>, partials: Int = 3): SettleResult {
        for (i in 0 until 5) engine.settle(acc, subs, start.plusDays(i.toLong()), mapOf("수학" to 3))
        var last: SettleResult? = null
        for (i in 0 until partials) {
            last = engine.settle(acc, subs, start.plusDays((5 + i).toLong()), mapOf("수학" to 1))
        }
        return last!!
    }

    private fun isPartialDefended(event: SettleEvent?): Boolean = event is SettleEvent.FreezeDefendedPartial

    private fun isPartialReset(event: SettleEvent?): Boolean = event is SettleEvent.PartialReset

    @Test
    fun `기본은 꺼져 있다 — 프리즈가 있어도 세모 셋이면 초기화된다`() {
        val subs = listOf(math())
        val acc = Account()
        acc.freeze = 1

        val r = threePartials(off, acc, subs)

        assertTrue(isPartialReset(r.event), "기획 5.5대로 프리즈는 ✕만 막는다")
        assertEquals(0, acc.overall)
        assertEquals(1, acc.freeze, "프리즈는 안 쓰인다")
    }

    @Test
    fun `켜면 프리즈가 세모 초기화를 막는다`() {
        val subs = listOf(math())
        val acc = Account()
        acc.freeze = 1

        val r = threePartials(on, acc, subs)

        assertTrue(isPartialDefended(r.event))
        assertEquals(0, acc.freeze, "프리즈 하나를 쓴다")
        assertEquals(8, acc.overall, "5일 + △ 3일. △가 준 +1 은 살아 있다")
    }

    @Test
    fun `막아도 창의 세모는 안 비운다`() {
        // ✕를 막았을 때와 같은 모양이다. 그래서 막아도 다음 △ 하나에 또 위태롭다 —
        // A7 에서 "△ 방어는 한 칸 미루는 것뿐" 이라고 본 근거다
        val subs = listOf(math())
        val acc = Account()
        acc.freeze = 1

        threePartials(on, acc, subs)
        assertEquals(3, acc.logs.count { it.mark == Mark.PARTIAL && !it.consumed }, "창의 △가 그대로다")

        val next = on.settle(acc, subs, start.plusDays(8), mapOf("수학" to 1))

        assertTrue(isPartialReset(next.event), "프리즈를 다 썼으니 이번엔 죽는다")
        assertEquals(0, acc.overall)
    }

    @Test
    fun `프리즈가 없으면 켜도 그냥 초기화된다`() {
        val subs = listOf(math())
        val acc = Account()

        val r = threePartials(on, acc, subs)

        assertTrue(isPartialReset(r.event))
        assertEquals(0, acc.overall)
    }
}
