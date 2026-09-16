package com.studystreak.sim

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.streak.StreakRules.PARTIAL_MIN_RATIO
import com.studystreak.domain.toChecklist
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

/**
 * "3줄이 낫다" 가 정말인지 확인한다 — 훨씬 크게, 그리고 **짝지어서**.
 *
 * `checklist` 모드는 줄 수마다 다른 씨앗을 썼다. 그러면 줄 수의 효과와 씨앗의 운이 섞인다.
 * 여기서는 **같은 씨앗을 모든 줄 수에 준다.** 난수는 "몇 페이지를 읽었나" 에만 쓰이고
 * 그건 줄 수와 무관하므로, 같은 씨앗이면 **완전히 같은 사용자가 완전히 같은 날 같은 만큼
 * 읽는다.** 달라지는 건 체크박스를 어떻게 그렸나 하나뿐이다.
 *
 * 그래서 이 모드는 줄 수의 효과를 따로 떼어 볼 수 있고, 판마다 누가 이겼는지도 셀 수 있다.
 *
 * 실행: ./gradlew :sim:run --args="checklist-confirm"
 */

private const val REPLICATES = 40
private const val TRIALS = 2000
private const val SEED_BASE = 20260916

private val SPLITS = listOf(1, 2, 3, 4, 5, 6, 7)
private val WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

/** [amount] 를 [lines] 줄로 나눈 모양. 나머지는 앞줄이 갖는다 (`toChecklist` 와 같은 규칙). */
private fun shape(amount: Int, lines: Int): List<Int> {
    val count = minOf(lines, amount)
    if (count <= 0) return emptyList()
    val base = amount / count
    val extra = amount % count
    return (0 until count).map { base + if (it < extra) 1 else 0 }
}

/** ✕를 면하려면 몇 페이지를 읽어야 하나 — 재는 게 아니라 계산으로 나온다. */
private fun pagesToEscapeNone(amount: Int, lines: Int): Int {
    val sizes = shape(amount, lines)
    val needLines = ceil(PARTIAL_MIN_RATIO * sizes.size).toInt().coerceAtLeast(1)
    return sizes.take(needLines).sum()
}

private fun printThresholds() {
    val a = allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), WEEKDAYS, dailyMax = 10)
    println("=".repeat(RULE_WIDTH))
    println("1. 계산으로 나오는 부분 — ✕를 면하는 문턱")
    println("=".repeat(RULE_WIDTH))
    println("30% 기준은 줄 단위로 올림된다. ceil(0.3 × 줄수) 줄을 체크해야 ✕를 면하고,")
    println("그게 곧 \"몇 페이지를 읽어야 하나\" 로 바뀐다. 시뮬레이션이 아니라 산수다.")
    println()

    for (amount in listOf(a.peak, a.base)) {
        println("하루 ${amount}페이지인 날")
        println("-".repeat(RULE_WIDTH))
        print(cell("줄 수", 8)); print(cell("나눈 모양", 20)); print(cell("필요한 줄", 12))
        print(cell("필요한 페이지", 16)); println(cell("전체 대비", 12))
        for (n in SPLITS) {
            val sizes = shape(amount, n)
            if (sizes.size < n) continue
            val pages = pagesToEscapeNone(amount, n)
            val need = ceil(PARTIAL_MIN_RATIO * sizes.size).toInt().coerceAtLeast(1)
            val mark = if (pages == SPLITS.minOf { pagesToEscapeNone(amount, it) }) "  ← 최저" else ""
            print(cell("${n}줄", 8)); print(cell(sizes.joinToString("+"), 20)); print(cell("${need}줄", 12))
            print(cell("${pages}p", 16)); println(cell("%.0f%%$mark".format(100.0 * pages / amount), 12))
        }
        println()
    }
    println("명목 기준은 30% 다. 거기에 가장 가까운 줄 수가 기획 5.2의 의도에 맞다.")
}

private class Paired(val resetsPerPlan: DoubleArray, val meanStreak: DoubleArray,
                     val full: Long, val partial: Long, val none: Long, val judged: Long)

private fun measurePaired(lines: Int, keep: Double, skip: Double): Paired {
    val resets = DoubleArray(REPLICATES)
    val streaks = DoubleArray(REPLICATES)
    var full = 0L; var partial = 0L; var none = 0L
    for (r in 0 until REPLICATES) {
        val res = runSplit(lines, keep, skip, trials = TRIALS, seed = SEED_BASE + r)
        resets[r] = res.resets.toDouble() / res.plans
        streaks[r] = res.meanStreak
        full += res.full; partial += res.partial; none += res.none
    }
    return Paired(resets, streaks, full, partial, none, full + partial + none)
}

fun printChecklistConfirm() {
    val a = allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), WEEKDAYS, dailyMax = 10)
    printThresholds()

    println()
    println("=".repeat(RULE_WIDTH))
    println("2. 재야 나오는 부분 — 같은 씨앗을 모든 줄 수에 준다")
    println("=".repeat(RULE_WIDTH))
    println("정석 480페이지 · 9/9 ~ 12/31 · 평일 · 학습 가능일 ${a.studyDayCount}일 " +
        "(공부 ${a.allocDayCount}일 + 버퍼 ${a.bufferDayCount}일)")
    println("줄 수 하나당 $REPLICATES 판 × $TRIALS 플랜 = ${"%,d".format(REPLICATES * TRIALS)} 플랜 " +
        "(${"%,d".format(REPLICATES.toLong() * TRIALS * a.studyDayCount)}일)")
    println("난수는 \"몇 페이지 읽었나\" 에만 쓴다. 같은 씨앗 = 같은 사용자가 같은 만큼 읽은 것이다")
    println("→ 줄 수만 다른 짝지은 비교라서, 판마다 누가 이겼는지 셀 수 있다")

    for ((keep, skip, label) in listOf(
        Triple(0.99, 0.02, "거의 매일 끝까지 읽는 사용자"),
        Triple(0.97, 0.05, "가끔 중간에 그만두는 사용자"),
        Triple(0.94, 0.10, "자주 흐지부지되는 사용자"),
    )) {
        println()
        println("$label  (한 페이지 더 읽을 확률 ${(keep * 100).toInt()}% · 아예 안 여는 날 ${(skip * 100).toInt()}%)")
        println("-".repeat(RULE_WIDTH))
        val byLines = SPLITS.associateWith { measurePaired(it, keep, skip) }
        val three = byLines.getValue(3)

        print(cell("줄 수", 6)); print(cell("○", 9)); print(cell("△", 9)); print(cell("✕", 9))
        print(cell("초기화/플랜", 13)); print(cell("평균 유지", 11)); println("3줄이 이긴 판")
        for (n in SPLITS) {
            val p = byLines.getValue(n)
            val mean = p.resetsPerPlan.average()
            val wins = (0 until REPLICATES).count { three.resetsPerPlan[it] < p.resetsPerPlan[it] }
            print(cell("${n}줄", 6))
            print(cell("%.1f%%".format(100.0 * p.full / p.judged), 9))
            print(cell("%.1f%%".format(100.0 * p.partial / p.judged), 9))
            print(cell("%.1f%%".format(100.0 * p.none / p.judged), 9))
            print(cell("%.2f회".format(mean), 13))
            print(cell("%.2f일".format(p.meanStreak.average()), 11))
            println(if (n == 3) "—" else "$wins/$REPLICATES")
        }
        println("-".repeat(RULE_WIDTH))
        val fullSpread = SPLITS.map { 100.0 * byLines.getValue(it).full / byLines.getValue(it).judged }
        println("○ 비율은 줄 수와 무관하다: %.1f%% ~ %.1f%% (폭 %.1f%%p)"
            .format(fullSpread.min(), fullSpread.max(), fullSpread.max() - fullSpread.min()))
        val two = byLines.getValue(2)
        println("3줄 vs 2줄 — 판마다의 차이 %.2f회 ~ %.2f회, 평균 %.2f회 (3줄이 덜 끊긴다)"
            .format(
                (0 until REPLICATES).minOf { two.resetsPerPlan[it] - three.resetsPerPlan[it] },
                (0 until REPLICATES).maxOf { two.resetsPerPlan[it] - three.resetsPerPlan[it] },
                (0 until REPLICATES).map { two.resetsPerPlan[it] - three.resetsPerPlan[it] }.average(),
            ))
    }
    println("=".repeat(RULE_WIDTH))
}
