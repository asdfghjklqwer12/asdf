package com.studystreak.domain.streak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 48시간 복구 (기획 5.5) — 파이썬에 없던 새 규칙.
 *
 * 끊긴 날의 밀린 하루치를 다음 이틀 안에 다 하면 숫자가 돌아온다. 달력 월 1회.
 * **복구는 초기화만 무른다 — 그날의 ○△✕ 는 안 바뀐다.**
 */
class RecoveryTest {

    private val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC))
    private val everyday = DayOfWeek.entries.toSet()
    private val start = LocalDate.of(2026, 9, 1)

    private fun math(tasks: Int = 3, carryOver: Boolean = false) =
        Subject("수학", required = true, weekdays = everyday, tasks = tasks, carryOver = carryOver)

    private fun english() =
        Subject("영어", required = false, weekdays = everyday, tasks = 2)

    /** [days] 일 동안 전부 완료 → 전체 streak 이 [days] 가 된다. 다음 날짜를 돌려준다. */
    private fun buildUp(acc: Account, subs: List<Subject>, days: Int): LocalDate {
        for (i in 0 until days) {
            engine.settle(acc, subs, start.plusDays(i.toLong()), subs.associate { it.name to it.tasks })
        }
        return start.plusDays(days.toLong())
    }

    // 한글 이름 테스트 안에서 inline 함수를 부르면 클래스 파일 이름이 깨진다 (CLAUDE.md 참고).
    // 꺼내 쓰는 건 전부 ASCII 이름 도우미로 감싼다.
    private fun availableOn(acc: Account, subs: List<Subject>, day: LocalDate): RecoveryOffer.Available =
        engine.recoveryOffer(acc, subs, day) as RecoveryOffer.Available

    private fun offerDenial(acc: Account, subs: List<Subject>, day: LocalDate): RecoveryDenial =
        (engine.recoveryOffer(acc, subs, day) as RecoveryOffer.Unavailable).reason

    private fun recoveredFrom(result: RecoveryResult): RecoveryResult.Recovered =
        result as RecoveryResult.Recovered

    private fun refusalOf(result: RecoveryResult): RecoveryDenial =
        (result as RecoveryResult.Refused).reason

    // ── 되돌아오는가 ────────────────────────────────────────────

    @Test
    fun `밀린 하루치를 다 하면 숫자가 돌아온다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)

        engine.settle(acc, subs, broke, emptyMap()) // ✕
        assertEquals(0, acc.overall, "끊겼다")

        val result = engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertEquals(20, recoveredFrom(result).overall, "끊기기 전 20으로 돌아온다")
        assertEquals(20, acc.overall)
    }

    @Test
    fun `가위표로 끊긴 날은 복구해도 하나 더 오르지 않는다`() {
        // 기존 규칙과 같은 모양이다 — 프리즈로 막은 날도 +1이 아니라 그 자리에 멈춘다
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)

        engine.settle(acc, subs, broke, emptyMap())
        engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertEquals(20, acc.overall, "21이 아니다 — 복구는 초기화만 무른다")
    }

    @Test
    fun `세모 세 개로 끊긴 것도 복구된다`() {
        val subs = listOf(math())
        val acc = Account()
        var day = buildUp(acc, subs, 5)

        // 3개 중 1개 = 33% 라 △. 창 안에 셋이 모이면 초기화된다
        engine.settle(acc, subs, day, mapOf("수학" to 1)); day = day.plusDays(1)
        engine.settle(acc, subs, day, mapOf("수학" to 1)); day = day.plusDays(1)
        val broke = day
        val r = engine.settle(acc, subs, broke, mapOf("수학" to 1))

        assertTrue(r.event is SettleEvent.PartialReset)
        assertEquals(0, acc.overall)

        val result = engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 2))

        // △ 자체의 +1 은 살아 있다 — 5일 + △ 3일 = 8
        assertEquals(8, recoveredFrom(result).overall, "△의 +1 은 그대로다. 복구는 초기화만 무른다")
    }

    @Test
    fun `끊긴 뒤 올린 숫자는 날아가지 않는다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)

        engine.settle(acc, subs, broke, emptyMap()) // 끊김
        engine.settle(acc, subs, broke.plusDays(1), mapOf("수학" to 3)) // 다음 날은 ○
        assertEquals(1, acc.overall, "새로 1일")

        engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertEquals(21, acc.overall, "20이 되살아나고 그 위에 새로 쌓은 1일이 얹힌다")
        assertEquals(21, acc.longest)
    }

    @Test
    fun `과목 스트릭도 같이 돌아온다`() {
        // 7일마다 과목 프리즈가 생기므로, 과목이 진짜 끊기려면 7일 미만이어야 한다
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 5)
        assertEquals(0, subs[0].freeze, "아직 과목 프리즈가 없다")

        engine.settle(acc, subs, broke, emptyMap())
        assertEquals(0, subs[0].streak, "과목도 끊겼다")

        val result = engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertEquals(5, subs[0].streak, "전체만 돌아오고 과목이 0이면 기록 화면이 어긋난다")
        assertEquals(5, recoveredFrom(result).subjects["수학"])
    }

    @Test
    fun `과목 프리즈가 지킨 과목은 복구가 건드리지 않는다`() {
        // 20일이면 과목 프리즈가 2개 있다. 프리즈가 과목 streak 을 이미 지켰으므로
        // 되돌릴 게 없다 — 복구가 한 번 더 얹으면 없던 날이 생긴다
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        assertEquals(2, subs[0].freeze)

        engine.settle(acc, subs, broke, emptyMap())
        assertEquals(20, subs[0].streak, "과목 프리즈가 지켰다")
        assertEquals(1, subs[0].freeze, "한 개 썼다")

        val result = engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertEquals(20, subs[0].streak, "그대로다")
        assertTrue(recoveredFrom(result).subjects.isEmpty(), "되돌린 과목이 없다")
    }

    // ── 기록은 거짓말하지 않는다 ────────────────────────────────

    @Test
    fun `복구해도 그날 표시는 가위표 그대로다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))
        val log = acc.logs.first { it.date == broke }

        assertEquals(Mark.NONE, log.mark, "동그라미로 바꾸면 안 한 날을 한 것처럼 기록이 거짓이 된다")
        assertEquals(0, log.tasksChecked, "그날 실제로 한 건 0개다")
        assertTrue(log.recovered, "대신 복구 도장이 찍힌다")
        assertNotNull(log.recoveredAt)
    }

    // ── 언제까지 쓸 수 있나 ─────────────────────────────────────

    @Test
    fun `끊긴 날 당일에는 아직 못 쓴다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        assertEquals(RecoveryDenial.TOO_EARLY, offerDenial(acc, subs, broke))
    }

    @Test
    fun `다음 이틀까지는 쓸 수 있다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        assertEquals(broke.plusDays(2), availableOn(acc, subs, broke.plusDays(1)).deadline)
        assertEquals(20, availableOn(acc, subs, broke.plusDays(2)).restoresTo, "이틀째도 된다")
    }

    @Test
    fun `사흘째에는 못 쓴다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        assertEquals(RecoveryDenial.EXPIRED, offerDenial(acc, subs, broke.plusDays(3)))
        assertEquals(
            RecoveryDenial.EXPIRED,
            refusalOf(engine.recover(acc, subs, broke.plusDays(3), mapOf("수학" to 3))),
        )
        assertEquals(0, acc.overall, "거부됐으면 아무것도 안 바뀐다")
    }

    // ── 월 1회 ──────────────────────────────────────────────────

    @Test
    fun `같은 달에 두 번은 못 쓴다`() {
        val subs = listOf(math())
        val acc = Account()
        val first = buildUp(acc, subs, 20)
        engine.settle(acc, subs, first, emptyMap())
        engine.recover(acc, subs, first.plusDays(1), mapOf("수학" to 3))

        // 같은 달에 또 끊긴다
        val second = first.plusDays(5)
        engine.settle(acc, subs, second.minusDays(1), mapOf("수학" to 3))
        engine.settle(acc, subs, second, emptyMap())

        assertEquals(RecoveryDenial.USED_THIS_MONTH, offerDenial(acc, subs, second.plusDays(1)))
    }

    @Test
    fun `달이 바뀌면 다시 쓸 수 있다`() {
        val subs = listOf(math())
        val acc = Account()
        acc.recoveries += LocalDate.of(2026, 9, 3) // 9월에 이미 썼다

        val broke = LocalDate.of(2026, 9, 29)
        for (i in 9 until 29) engine.settle(acc, subs, LocalDate.of(2026, 9, i), mapOf("수학" to 3))
        engine.settle(acc, subs, broke, emptyMap())

        assertEquals(RecoveryDenial.USED_THIS_MONTH, offerDenial(acc, subs, LocalDate.of(2026, 9, 30)))
        // 10월 1일은 새 달이다. 창(이틀)도 아직 안 지났다
        assertEquals(20, availableOn(acc, subs, LocalDate.of(2026, 10, 1)).restoresTo)
    }

    // ── 다 못 했으면 안 준다 ────────────────────────────────────

    @Test
    fun `밀린 걸 다 못 하면 거부된다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        val result = engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 2))

        assertEquals(RecoveryDenial.NOT_FINISHED, refusalOf(result))
        assertEquals(0, acc.overall, "거부됐으면 아무것도 안 바뀐다")
        assertTrue(acc.recoveries.isEmpty(), "월 1회를 소모하지도 않는다")
    }

    @Test
    fun `남은 몫은 그날 한 만큼 줄어든다`() {
        // 10개 중 2개 = 20% 라 ✕. 밀린 건 8개다
        val subs = listOf(math(tasks = 10))
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, mapOf("수학" to 2))

        assertEquals(mapOf("수학" to 8), availableOn(acc, subs, broke.plusDays(1)).remaining)
        assertEquals(RecoveryDenial.NOT_FINISHED, refusalOf(engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 7))))
        assertEquals(20, recoveredFrom(engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 8))).overall)
    }

    // ── 대상이 아닌 것들 ────────────────────────────────────────

    @Test
    fun `끊긴 적이 없으면 제안이 없다`() {
        val subs = listOf(math())
        val acc = Account()
        val today = buildUp(acc, subs, 20)

        assertEquals(RecoveryDenial.NO_BREAK, offerDenial(acc, subs, today))
    }

    @Test
    fun `프리즈로 막은 날은 끊김이 아니다`() {
        val subs = listOf(math())
        val acc = Account()
        acc.freeze = 1
        val day = buildUp(acc, subs, 20)

        val r = engine.settle(acc, subs, day, emptyMap())

        assertEquals(SettleEvent.FreezeDefended, r.event)
        assertEquals(20, acc.overall, "그 자리에 멈춘다")
        assertNull(acc.pendingBreak, "막았으니 되돌릴 게 없다")
    }

    @Test
    fun `되돌려도 달라지는 게 없으면 제안하지 않는다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = LocalDate.of(2026, 9, 10)

        engine.settle(acc, subs, broke, emptyMap()) // 0에서 끊긴다

        assertEquals(RecoveryDenial.NOTHING_TO_RESTORE, offerDenial(acc, subs, broke.plusDays(1)))
    }

    @Test
    fun `창 안에 또 끊기면 그 끊김이 대상이 된다`() {
        val subs = listOf(math())
        val acc = Account()
        val first = buildUp(acc, subs, 20)

        engine.settle(acc, subs, first, emptyMap()) // 20 → 0
        engine.settle(acc, subs, first.plusDays(1), emptyMap()) // 0 → 0, 새 끊김

        val pending = acc.pendingBreak
        assertNotNull(pending)
        assertEquals(first.plusDays(1), pending?.date, "가장 최근 끊김 하나만 되돌릴 수 있다")
        assertEquals(
            RecoveryDenial.NOTHING_TO_RESTORE,
            offerDenial(acc, subs, first.plusDays(2)),
            "두 번째 끊김은 0에서 0이라 되돌릴 게 없다 — 첫 끊김을 대신 살려주지 않는다",
        )
    }

    @Test
    fun `선택 과목은 밀린 몫에 들어가지 않는다`() {
        val subs = listOf(math(), english())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)

        engine.settle(acc, subs, broke, emptyMap())
        val offer = availableOn(acc, subs, broke.plusDays(1))

        assertEquals(setOf("수학"), offer.remaining.keys, "끊은 건 필수 과목이다")
        assertEquals(20, recoveredFrom(engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))).overall)
    }

    // ── 이월과 겹칠 때 ──────────────────────────────────────────

    @Test
    fun `이월이 켜진 과목은 이미 넘어갔다고 알려준다`() {
        // 밀린 몫이 이미 다음 학습일 배정에 들어가 있다. 앱이 같은 일을 두 번 시키면 안 된다
        val subs = listOf(math(carryOver = true))
        val acc = Account()
        val broke = buildUp(acc, subs, 20)

        engine.settle(acc, subs, broke, emptyMap())
        assertEquals(3, subs[0].carriedTasks, "3개가 다음 날로 넘어갔다")

        assertEquals(setOf("수학"), availableOn(acc, subs, broke.plusDays(1)).alreadyCarried)
    }

    @Test
    fun `복구를 쓰면 그 끊김은 사라진다`() {
        val subs = listOf(math())
        val acc = Account()
        val broke = buildUp(acc, subs, 20)
        engine.settle(acc, subs, broke, emptyMap())

        engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))

        assertNull(acc.pendingBreak)
        assertEquals(
            RecoveryDenial.NO_BREAK,
            refusalOf(engine.recover(acc, subs, broke.plusDays(1), mapOf("수학" to 3))),
            "두 번 불러도 두 번 안 준다",
        )
        assertEquals(20, acc.overall)
    }
}
