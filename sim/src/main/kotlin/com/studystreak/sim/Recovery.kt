package com.studystreak.sim

import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.RecoveryResult
import com.studystreak.domain.streak.SettleEvent
import com.studystreak.domain.streak.StreakEngine
import com.studystreak.domain.streak.StreakRules
import com.studystreak.domain.streak.Subject
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * 48시간 복구(5.5)가 **실제로 얼마나 켜지는가.**
 *
 * 기획서는 복구 대상이 ✕인지 △ 3개인지 안 적어뒀다. 그런데 streak을 실제로 죽이는 건
 * ✕가 아니라 △ 3개다(`docs/측정-결과.md` 2절). 그래서 "✕만 복구" 로 만들면 월 1회짜리
 * 장치가 거의 발동하지 않는다. 그 차이를 잰다.
 *
 * 세 가지를 **같은 씨앗**으로 돌린다 — 복구는 `overall` 만 바꾸고 판정에는 안 끼어들므로
 * 세 모드의 ○△✕ 열은 완전히 같다. 난수도 하루에 똑같은 개수만 뽑는다.
 *
 * 실행: ./gradlew :sim:run --args="recovery"
 */

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private enum class Mode(val label: String) {
    OFF("복구 없음"),
    NONE_ONLY("✕만 복구"),
    BOTH("△·✕ 둘 다"),
}

private class RecoveryRun(
    val breaks: Int, val fromPartial: Int, val fromNone: Int,
    val used: Int, val realResets: Int, val meanStreak: Double, val trials: Int,
)

private fun runRecovery(
    mode: Mode,
    completionRate: Double,
    makeUpRate: Double,
    subjectCount: Int = 3,
    trials: Int = 300,
    days: Int = 365,
    seed: Int = 20260916,
): RecoveryRun {
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val start = LocalDate.of(2026, 1, 1)

    var breaks = 0; var fromPartial = 0; var fromNone = 0; var used = 0; var realResets = 0
    val streakLengths = mutableListOf<Int>()

    repeat(trials) {
        val account = Account()
        val subjects = (1..subjectCount).map {
            Subject("과목$it", required = true, weekdays = WEEKDAYS, tasks = 3)
        }
        var brokeYesterday: Int? = null // 어제 끊긴 자리의 "끊기기 전 값"

        for (i in 0 until days) {
            val date = start.plusDays(i.toLong())

            // 난수는 모드와 무관하게 하루 한 번 뽑는다 — 안 그러면 세 모드의 난수열이 어긋난다
            val willMakeUp = rng.nextDouble() < makeUpRate
            var recoveredToday = false
            val pending = account.pendingBreak
            if (mode != Mode.OFF && willMakeUp && pending != null) {
                val covered = when (mode) {
                    Mode.NONE_ONLY -> pending.cause == SettleEvent.ResetWithoutFreeze
                    else -> true
                }
                if (covered) {
                    val r = engine.recover(account, subjects, date, pending.remaining)
                    if (r is RecoveryResult.Recovered) { used++; recoveredToday = true }
                }
            }
            // 어제 끊긴 걸 못 되돌렸으면 그게 진짜 죽음이다
            brokeYesterday?.let { if (!recoveredToday) { streakLengths += it; realResets++ } }
            brokeYesterday = null

            val done = subjects.associate { s ->
                s.name to (0 until s.tasks).count { rng.nextDouble() < completionRate }
            }
            val before = account.overall
            val result = engine.settle(account, subjects, date, done)

            when (val e = result.event) {
                is SettleEvent.PartialReset -> { breaks++; fromPartial++; brokeYesterday = before + 1 }
                SettleEvent.ResetWithoutFreeze -> { breaks++; fromNone++; brokeYesterday = before }
                else -> Unit
            }

            val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (account.logs.size > 2 * StreakRules.WINDOW_DAYS) {
                account.logs.removeAll { it.date < keepFrom }
            }
        }
        brokeYesterday?.let { streakLengths += it; realResets++ }
    }

    return RecoveryRun(
        breaks, fromPartial, fromNone, used, realResets,
        meanStreak = if (streakLengths.isEmpty()) Double.NaN else streakLengths.average(),
        trials = trials,
    )
}

fun printRecovery() {
    println("=".repeat(RULE_WIDTH))
    println("48시간 복구가 실제로 얼마나 켜지는가")
    println("=".repeat(RULE_WIDTH))
    println("필수 3과목 · 과목당 태스크 3개 · 평일 · 1년(365일) × 300명")
    println("밀린 하루치를 해내는 확률 70% · 달력 월 1회 · 끊긴 다음 날에 시도")
    println("세 모드를 같은 씨앗으로 돌린다 — 복구는 판정에 안 끼어들어 ○△✕ 열이 완전히 같다")

    for (rate in listOf(0.97, 0.93, 0.88)) {
        val runs = Mode.entries.associateWith { runRecovery(it, rate, makeUpRate = 0.70) }
        val off = runs.getValue(Mode.OFF)

        println()
        println("완료율 ${(rate * 100).toInt()}%%  — 1인당 끊김 %.1f회/년 ".format(off.breaks.toDouble() / off.trials) +
            "(△발 %.0f%% · ✕발 %.0f%%)".format(
                100.0 * off.fromPartial / off.breaks, 100.0 * off.fromNone / off.breaks))
        println("-".repeat(RULE_WIDTH))
        print(cell("모드", 14))
        for (h in listOf("복구 발동", "살린 끊김", "실질 초기화", "평균 유지", "복구 없을 때 대비")) print(cell(h, 13))
        println()
        for (mode in Mode.entries) {
            val r = runs.getValue(mode)
            print(cell(mode.label, 14))
            print(cell("%.1f회/년".format(r.used.toDouble() / r.trials), 13))
            print(cell(if (r.breaks == 0) "—" else "%.1f%%".format(100.0 * r.used / r.breaks), 13))
            print(cell("%.1f회/년".format(r.realResets.toDouble() / r.trials), 13))
            print(cell("%.1f일".format(r.meanStreak), 13))
            println(if (mode == Mode.OFF) "—" else "%+.0f%%".format(100.0 * (r.meanStreak - off.meanStreak) / off.meanStreak))
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("\"살린 끊김\" = 전체 끊김 중 복구가 되돌린 비율. 월 1회 상한이 천장을 만든다")
    println("=".repeat(RULE_WIDTH))
}
