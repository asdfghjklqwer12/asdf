package com.studystreak.sim

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.plan.redistribute
import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.Mark
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
 * 기획 13장이 "남은 건 숫자 튜닝"이라며 남겨둔 것들을 실제로 재어본다.
 * 규칙은 건드리지 않고 관찰만 한다.
 */

private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

// ─────────────────────────────────────────────────────────────
// 1. 하루치를 태스크 몇 개로 쪼갤 것인가 (docs/남은-일.md A1)
// ─────────────────────────────────────────────────────────────

/**
 * 자동 분배 플랜 하나만 쓰는 사용자의 하루는 태스크 몇 개가 되어야 하나.
 *
 * 태스크 1개면 전부 아니면 전무라 △가 나올 수 없고, 30% 유예가 통째로 무력화된다.
 * 몇 개부터 유예가 살아나는지, 그래서 streak이 얼마나 오래 가는지 본다.
 *
 * 실행: ./gradlew :sim:run --args="tasks"
 */
fun printTaskSplit() {
    val trials = 300
    val daysPerTrial = 1000
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val start = LocalDate.of(2026, 9, 7)

    println("=".repeat(RULE_WIDTH))
    println("하루치를 태스크 몇 개로 쪼갤 것인가 — 필수 1과목(자동 분배 플랜 하나)")
    println("여는 날 태스크 완료율 95% 고정 · 안 여는 날 비율만 바꿈 · 설정당 ${trials * daysPerTrial / 1000}천 일")
    println("=".repeat(RULE_WIDTH))

    for (skip in listOf(0.0, 0.05, 0.10)) {
        println()
        println("한 달에 ${"%.1f".format(skip * 30.4)}일쯤 아예 안 여는 사용자 (건너뛸 확률 ${(skip * 100).toInt()}%)")
        println("-".repeat(RULE_WIDTH))
        print(cell("하루 태스크", 13))
        for (label in listOf("○", "△", "✕", "평균 유지", "초기화/월")) print(cell(label, 12))
        println()

        for (tasksPerDay in listOf(1, 2, 3, 4, 5, 10)) {
            val rng = Random(2024 + tasksPerDay * 13 + (skip * 100).toInt())
            var full = 0; var partial = 0; var none = 0
            var resets = 0
            val lengths = mutableListOf<Int>()

            repeat(trials) {
                val acc = Account()
                val subject = Subject("정석", required = true, weekdays = WEEKDAYS, tasks = tasksPerDay)
                val subs = listOf(subject)
                for (i in 0 until daysPerTrial) {
                    val date = start.plusDays(i.toLong())
                    val skipped = rng.nextDouble() < skip
                    val checked = if (skipped) 0 else (0 until tasksPerDay).count { rng.nextDouble() < 0.95 }
                    val before = acc.overall
                    val r = engine.settle(acc, subs, date, mapOf("정석" to checked))
                    when (r.log.mark) {
                        Mark.FULL -> full++
                        Mark.PARTIAL -> partial++
                        Mark.NONE -> none++
                        Mark.REST -> Unit
                    }
                    when (r.event) {
                        is SettleEvent.PartialReset -> { lengths += before + 1; resets++ }
                        SettleEvent.ResetWithoutFreeze -> { lengths += before; resets++ }
                        else -> Unit
                    }
                    val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
                    if (acc.logs.size > 2 * StreakRules.WINDOW_DAYS) acc.logs.removeAll { it.date < keepFrom }
                }
            }

            val judged = (full + partial + none).toDouble()
            val allDays = trials.toDouble() * daysPerTrial
            print(cell("${tasksPerDay}개", 13))
            print(cell("%.1f%%".format(100 * full / judged), 12))
            print(cell("%.1f%%".format(100 * partial / judged), 12))
            print(cell("%.1f%%".format(100 * none / judged), 12))
            print(cell(if (lengths.isEmpty()) "안 끊김" else "%.1f일".format(lengths.average()), 12))
            print(cell("%.2f회".format(30.4 * resets / allDays), 12))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("→ 태스크 1개면 △ 열이 0%다. 30% 유예가 통째로 죽고 ✕만 남는다.")
    println("=".repeat(RULE_WIDTH))
}

// ─────────────────────────────────────────────────────────────
// 2. 버퍼 15%가 적절한가 (기획 13장 3번)
// ─────────────────────────────────────────────────────────────

/**
 * 버퍼가 실제로 며칠치 밀림을 흡수하는지 잰다.
 *
 * 기획 6.2의 예시 그대로 — 480페이지를 9/9 ~ 12/31 평일에. 기간 중간에서 k일을 통째로
 * 빼먹었을 때, 재분배 후에도 하루 분량이 그대로인 최대 k 가 그 버퍼 비율의 흡수력이다.
 *
 * 실행: ./gradlew :sim:run --args="buffer"
 */
fun printBufferTuning() {
    val total = 480
    val start = LocalDate.of(2026, 9, 9)
    val end = LocalDate.of(2026, 12, 31)

    println("=".repeat(RULE_WIDTH))
    println("버퍼 비율 튜닝 — 정석 ${total}페이지 · ${start} ~ ${end} · 평일")
    println("=".repeat(RULE_WIDTH))
    print(cell("버퍼", 8))
    for (label in listOf("버퍼일", "배분일", "하루", "버퍼 간격", "25%지점", "50%지점", "75%지점")) {
        print(cell(label, 10))
    }
    println()
    println("-".repeat(RULE_WIDTH))

    for (ratio in listOf(0.05, 0.10, 0.15, 0.20, 0.25, 0.30)) {
        val a = allocate(total, start, end, WEEKDAYS, bufferRatio = ratio)
        val zeros = a.days.filter { a.plan.getValue(it) == 0 }
        val gaps = zeros.zipWithNext { x, y -> java.time.temporal.ChronoUnit.DAYS.between(x, y) }
        val meanGap = if (gaps.isEmpty()) 0.0 else gaps.average()

        print(cell("%.0f%%".format(ratio * 100), 8))
        print(cell("${a.bufferDayCount}일", 10))
        print(cell("${a.allocDayCount}일", 10))
        print(cell("${a.peak}p", 10))
        print(cell("%.1f일".format(meanGap), 10))
        for (position in listOf(0.25, 0.50, 0.75)) {
            print(cell("${absorbable(a, position)}일", 10))
        }
        println()
    }
    println("-".repeat(RULE_WIDTH))
    println("\"n%지점\" = 기간의 그 지점에서 며칠을 통째로 빼먹어도 하루 분량이 안 늘어나는가")
    println("=".repeat(RULE_WIDTH))
    println()

    // 기획 6.2가 든 예시를 그대로 확인한다
    val a = allocate(total, start, end, WEEKDAYS)
    println("기획 6.2 예시 확인 — 버퍼 15%")
    println("  학습 가능일 ${a.studyDayCount}일 · 버퍼 ${a.bufferDayCount}일 · 배분일 ${a.allocDayCount}일 · 하루 ${a.base}~${a.peak}페이지")
    println("  버퍼 없이 배분했다면 하루 ${(total + a.studyDayCount - 1) / a.studyDayCount}페이지 (${a.studyDayCount}일 전부 사용)")
    println("  → 버퍼를 두는 값으로 하루 ${a.peak - (total + a.studyDayCount - 1) / a.studyDayCount}페이지를 더 낸다")
}

/**
 * [position] 지점부터 연속 며칠을 통째로 빼먹어도 **버퍼만으로 흡수되는가.**
 *
 * 기획 6.2의 의도가 정확히 이것이다 — "3일 밀려도 비워둔 15일 중 3일로 흘러들어가고,
 * 하루 분량은 그대로다". 버퍼가 모자라 남은 학습일에 얹기 시작하면(`addedPerDay > 0`)
 * 그때부터 하루 분량이 늘어난다.
 */
private fun absorbable(
    a: com.studystreak.domain.plan.Allocation,
    position: Double,
): Int {
    val startIdx = (a.days.size * position).toInt()
    val doneUpTo = a.days.take(startIdx).sumOf { a.plan.getValue(it) } // 여기까진 계획대로 했다
    var absorbed = 0
    for (missed in 1..a.days.size - startIdx) {
        val cutIdx = minOf(a.days.size - 1, startIdx + missed - 1)
        val r = redistribute(a, a.days[cutIdx], doneUpTo)
        if (r.addedPerDay > 0) break // 버퍼가 모자라 하루 분량이 늘어나기 시작했다
        absorbed = missed
    }
    return absorbed
}

// ─────────────────────────────────────────────────────────────
// 3. 프리즈가 실제로 얼마나 막아주는가 (기획 13장 1·2번)
// ─────────────────────────────────────────────────────────────

/**
 * 조각 수급과 ✕ 소모를 **같이** 돌려서, 프리즈가 실제 몇 %의 ✕를 막는지 본다.
 *
 * 앞의 rates·skips 측정은 프리즈 수입이 0인 상태였다. 실제 사용자는 휴식일에 자발 체크를
 * 하고 광고도 본다. 그 수급이 ✕ 빈도를 따라잡는지가 "조각 6개"와 "주 3개 상한"이
 * 적절한지에 대한 진짜 답이다.
 *
 * 실행: ./gradlew :sim:run --args="freeze"
 */
fun printFreezeCoverage() {
    val trials = 400
    val daysPerTrial = 730 // 2년
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val start = LocalDate.of(2026, 9, 7)

    println("=".repeat(RULE_WIDTH))
    println("프리즈가 실제로 막아주는 비율 — 조각 수급과 ✕ 소모를 같이 돌린다")
    println("필수 1과목 · 하루 태스크 3개 · 여는 날 완료율 95% · 설정당 ${trials * daysPerTrial / 1000}천 일")
    println("=".repeat(RULE_WIDTH))

    for ((restP, adP, label) in listOf(
        Triple(0.0, 0.0, "아무것도 안 함"),
        Triple(0.5, 0.0, "휴식일에 절반쯤 자발 체크"),
        Triple(1.0, 0.0, "휴식일마다 자발 체크"),
        Triple(0.0, 1.0, "매일 광고 2편"),
        Triple(1.0, 1.0, "둘 다 최대로"),
    )) {
        println()
        println("$label  (휴식일 체크 ${(restP * 100).toInt()}% · 광고 ${(adP * 100).toInt()}%)")
        println("-".repeat(RULE_WIDTH))
        print(cell("안 여는 날", 12))
        for (h in listOf("✕/월", "막은 비율", "초기화/월", "평균 유지", "조각 낭비")) print(cell(h, 12))
        println()

        for (skip in listOf(0.05, 0.10, 0.20)) {
            val rng = Random(31337 + (skip * 100).toInt() + (restP * 10).toInt() * 3 + (adP * 10).toInt())
            var crosses = 0; var defended = 0; var resets = 0; var wasted = 0
            val lengths = mutableListOf<Int>()

            repeat(trials) {
                val acc = Account()
                val subject = Subject("정석", required = true, weekdays = WEEKDAYS, tasks = 3)
                val subs = listOf(subject)
                for (i in 0 until daysPerTrial) {
                    val date = start.plusDays(i.toLong())
                    val isStudyDay = subject.studiesOn(date)

                    // 휴식일 자발 체크 (기획 5.4) — 주 3개 상한은 Account 가 알아서 건다
                    if (!isStudyDay && rng.nextDouble() < restP) {
                        if (acc.restCheck(date).rejected) wasted++
                    }
                    // 보상형 광고 — 하루 2편까지
                    if (rng.nextDouble() < adP) {
                        repeat(2) { if (acc.watchAd(date).rejected) wasted++ }
                    }

                    val skipped = rng.nextDouble() < skip
                    val checked = if (skipped) 0 else (0 until 3).count { rng.nextDouble() < 0.95 }
                    val before = acc.overall
                    val r = engine.settle(acc, subs, date, mapOf("정석" to checked))

                    if (r.log.mark == Mark.NONE) crosses++
                    when (r.event) {
                        SettleEvent.FreezeDefended -> defended++
                        is SettleEvent.PartialReset -> { lengths += before + 1; resets++ }
                        SettleEvent.ResetWithoutFreeze -> { lengths += before; resets++ }
                        else -> Unit
                    }
                    val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
                    if (acc.logs.size > 2 * StreakRules.WINDOW_DAYS) acc.logs.removeAll { it.date < keepFrom }
                }
            }

            val allDays = trials.toDouble() * daysPerTrial
            print(cell("%.0f%%".format(skip * 100), 12))
            print(cell("%.1f회".format(30.4 * crosses / allDays), 12))
            print(cell(if (crosses == 0) "—" else "%.0f%%".format(100.0 * defended / crosses), 12))
            print(cell("%.1f회".format(30.4 * resets / allDays), 12))
            print(cell(if (lengths.isEmpty()) "안 끊김" else "%.1f일".format(lengths.average()), 12))
            print(cell("%.1f회/월".format(30.4 * wasted / allDays), 12))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("\"막은 비율\" = ✕인 날 중 프리즈가 방어해 준 비율. \"조각 낭비\" = 상한에 걸려 거절된 횟수.")
    println("=".repeat(RULE_WIDTH))
}
