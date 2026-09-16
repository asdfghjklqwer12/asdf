package com.studystreak.sim

import com.studystreak.domain.plan.allocate
import com.studystreak.domain.plan.redistribute
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

/**
 * "계획 조정"의 값 — 자꾸 재분배하면 하루 분량이 얼마나 불어나나.
 *
 * 밀린 날의 두 갈래 중 하나는 계획 조정이다. 밀린 몫이 남은 학습일에 펴지므로 내일은
 * 가벼워지지만, 남은 날들이 조금씩 무거워진다. 그 비용을 분량 기준으로 잰다.
 *
 * (태스크 개수로 환산하는 건 "분량을 태스크 몇 개로 쪼개나"가 정해져야 가능하다 —
 * `docs/남은-일.md` A1)
 *
 * 실행: ./gradlew :sim:run --args="replan"
 */

private val PLAN_WEEKDAYS = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private class ReplanRun(
    val peakAt: List<Int>,
    val finalPeak: Int,
    val replans: Int,
    val leftover: Int,
)

/**
 * 완료율 [rate] 인 사용자가 매 학습일마다 계획 조정을 고른다.
 * 재분배는 늘 **원본** 계획에서 누적 완료량으로 다시 계산한다.
 */
private fun runReplan(rate: Double, seed: Int, replanEvery: Int = 1): ReplanRun {
    val a = allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), PLAN_WEEKDAYS, dailyMax = 10)
    val days = a.days
    val rng = Random(seed)
    var current = a.plan
    var cumulativeDone = 0
    var replans = 0
    val peakAt = mutableListOf<Int>()
    // 마지막 날은 그 뒤가 없어 늘 0이 나오므로 90% 지점까지만 본다
    val checkpoints = setOf(0, days.size / 4, days.size / 2, days.size * 3 / 4, days.size * 9 / 10)

    for ((i, date) in days.withIndex()) {
        val planned = current.getValue(date)
        // 배정된 분량의 rate 만큼 해낸다
        val did = (0 until planned).count { rng.nextDouble() < rate }
        cumulativeDone += did

        if (i in checkpoints) {
            val rest = days.filter { it.isAfter(date) }
            peakAt += if (rest.isEmpty()) 0 else rest.maxOf { current.getValue(it) }
        }

        if ((i + 1) % replanEvery == 0 && i < days.size - 1) {
            val r = redistribute(a, date, cumulativeDone, dailyMax = 10)
            if (r.behind > 0) {
                current = r.plan
                replans++
            }
        }
    }

    val finalPeak = peakAt.lastOrNull() ?: a.peak
    return ReplanRun(peakAt, finalPeak, replans, 480 - cumulativeDone)
}

fun printReplanCost() {
    println("=".repeat(RULE_WIDTH))
    println("\"계획 조정\" 의 값 — 정석 480페이지 · 9/9 ~ 12/31 · 평일 · 82학습일")
    println("원래 하루 6~7페이지. 밀릴 때마다 계획 조정을 고르면 하루 분량이 어떻게 변하나")
    println("=".repeat(RULE_WIDTH))
    println()
    print(cell("완료율", 10))
    for (h in listOf("시작", "25%", "절반", "75%", "90%", "조정 횟수", "못 끝낸 양")) {
        print(cell(h, 11))
    }
    println()
    println("-".repeat(RULE_WIDTH))

    for (rate in listOf(1.0, 0.95, 0.90, 0.85, 0.80, 0.70)) {
        val r = runReplan(rate, seed = 500 + (rate * 100).toInt())
        print(cell("${(rate * 100).toInt()}%", 10))
        for (p in r.peakAt) print(cell("${p}p", 11))
        print(cell("${r.replans}회", 11))
        print(cell("${r.leftover}p", 11))
        println()
    }
    println("-".repeat(RULE_WIDTH))
    println("각 칸 = 그 시점에서 **남은 구간**의 하루 최대 분량. 원래 계획은 6~7p 다")
    println("=".repeat(RULE_WIDTH))
    println()

    println("-".repeat(RULE_WIDTH))
    println("얼마나 자주 조정하느냐 — 완료율 85% 사용자")
    println("-".repeat(RULE_WIDTH))
    print(cell("조정 주기", 14))
    for (h in listOf("절반 시점", "90% 지점", "조정 횟수", "못 끝낸 양")) print(cell(h, 12))
    println()
    for ((every, label) in listOf(1 to "매 학습일", 5 to "주 1회", 10 to "2주 1회", 22 to "월 1회")) {
        val r = runReplan(0.85, seed = 585, replanEvery = every)
        print(cell(label, 14))
        print(cell("${r.peakAt[2]}p", 12))
        print(cell("${r.peakAt.last()}p", 12))
        print(cell("${r.replans}회", 12))
        print(cell("${r.leftover}p", 12))
        println()
    }
    println("=".repeat(RULE_WIDTH))
}
