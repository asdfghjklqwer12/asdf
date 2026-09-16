package com.studystreak.sim

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.Mark
import com.studystreak.domain.streak.SettleEvent
import com.studystreak.domain.streak.StreakEngine
import com.studystreak.domain.streak.StreakRules
import com.studystreak.domain.toChecklist
import com.studystreak.domain.toSubject
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * 하루치를 몇 줄로 나눌까 — **진짜 체크리스트로** 다시 잰다.
 *
 * 앞의 `tasks` 측정은 매일 태스크 수가 같은 가상 모델이었고, 줄마다 독립적으로 성공/실패한다고
 * 봤다. 진짜 플랜은 다르다.
 *
 * - 버퍼일(휴식일)이 8~10일마다 섞인다
 * - 사용자는 **페이지를 순서대로 읽는다.** 4페이지를 읽으면 첫 줄만 체크되지,
 *   두 줄이 각각 따로 성공하는 게 아니다
 *
 * 그래서 읽은 페이지 수를 먼저 뽑고, 그게 몇 줄을 덮는지 계산한다.
 *
 * 실행: ./gradlew :sim:run --args="checklist"
 */

private val WEEK = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

internal class SplitResult(
    val full: Int, val partial: Int, val none: Int, val rest: Int,
    val resets: Int, val meanStreak: Double, val longestDrought: Int, val plans: Int,
)

internal fun runSplit(
    tasksPerDay: Int,
    keepReading: Double,
    skip: Double,
    trials: Int = 2000,
    seed: Int = 61,
): SplitResult {
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val allocation = allocate(
        480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), WEEK, dailyMax = 10,
    )
    val checklist = allocation.toChecklist(tasksPerDay)

    var full = 0; var partial = 0; var none = 0; var rest = 0
    var resets = 0; var longestDrought = 0
    val streakLengths = mutableListOf<Int>()

    repeat(trials) {
        val subject = checklist.toSubject("정석", required = true)
        val acc = Account()
        val subs = listOf(subject)
        var drought = 0

        for (day in checklist) {
            val skipped = skip > 0.0 && rng.nextDouble() < skip
            // 페이지를 순서대로 읽다가 어느 순간 그만둔다
            var read = 0
            if (!skipped) {
                while (read < day.amount && rng.nextDouble() < keepReading) read++
            }
            // 읽은 데까지 덮인 줄만 체크된다
            val firstPage = day.tasks.firstOrNull()?.from ?: 0
            val checked = day.tasks.count { it.to <= firstPage + read - 1 }

            val before = acc.overall
            val r = engine.settle(acc, subs, day.date, mapOf("정석" to checked))
            when (r.log.mark) {
                Mark.FULL -> { full++; if (drought > longestDrought) longestDrought = drought; drought = 0 }
                Mark.PARTIAL -> { partial++; drought++ }
                Mark.NONE -> { none++; drought++ }
                Mark.REST -> rest++ // 휴식일은 가뭄으로 안 센다 — 체크할 게 없었으니까
            }
            when (r.event) {
                is SettleEvent.PartialReset -> { streakLengths += before + 1; resets++ }
                SettleEvent.ResetWithoutFreeze -> { streakLengths += before; resets++ }
                else -> Unit
            }
            val keepFrom = day.date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (acc.logs.size > 2 * StreakRules.WINDOW_DAYS) acc.logs.removeAll { it.date < keepFrom }
        }
        if (drought > longestDrought) longestDrought = drought
    }

    return SplitResult(
        full, partial, none, rest, resets,
        meanStreak = if (streakLengths.isEmpty()) Double.NaN else streakLengths.average(),
        longestDrought = longestDrought,
        plans = trials,
    )
}

fun printChecklistSplit() {
    val a = allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), WEEK, dailyMax = 10)
    println("=".repeat(RULE_WIDTH))
    println("하루치를 몇 줄로 나눌까 — 진짜 체크리스트로")
    println("정석 480페이지 · 9/9 ~ 12/31 · 평일 · 학습 가능일 ${a.studyDayCount}일 " +
        "(공부 ${a.allocDayCount}일 + 버퍼 ${a.bufferDayCount}일) · 하루 ${a.base}~${a.peak}p")
    println("사용자는 페이지를 순서대로 읽다가 그만둔다. 읽은 데까지 덮인 줄만 체크된다")
    println("=".repeat(RULE_WIDTH))

    for ((keep, skip, label) in listOf(
        Triple(0.99, 0.02, "거의 매일 끝까지 읽는 사용자"),
        Triple(0.97, 0.05, "가끔 중간에 그만두는 사용자"),
        Triple(0.94, 0.10, "자주 흐지부지되는 사용자"),
    )) {
        println()
        println("$label  (한 페이지 더 읽을 확률 ${(keep * 100).toInt()}% · 아예 안 여는 날 ${(skip * 100).toInt()}%)")
        println("-".repeat(RULE_WIDTH))
        print(cell("줄 수", 8))
        for (h in listOf("○", "△", "✕", "초기화/플랜", "평균 유지", "○ 가뭄 최장")) print(cell(h, 12))
        println()

        for (n in listOf(1, 2, 3, 4, 7)) {
            val r = runSplit(n, keep, skip, seed = 61 + n * 17 + (skip * 100).toInt())
            val judged = (r.full + r.partial + r.none).toDouble()
            print(cell("${n}줄", 8))
            print(cell("%.1f%%".format(100 * r.full / judged), 12))
            print(cell("%.1f%%".format(100 * r.partial / judged), 12))
            print(cell("%.1f%%".format(100 * r.none / judged), 12))
            print(cell("%.1f회".format(r.resets.toDouble() / r.plans), 12))
            print(cell("%.1f일".format(r.meanStreak), 12))
            print(cell("${r.longestDrought}일", 12))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("\"초기화/플랜\" = 이 ${a.studyDayCount}일짜리 플랜 하나를 도는 동안 streak이 끊기는 횟수")
    println("=".repeat(RULE_WIDTH))
}
