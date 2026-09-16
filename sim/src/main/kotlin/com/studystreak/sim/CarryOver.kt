package com.studystreak.sim

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
 * 이월 규칙이 실제로 어떻게 굴러가는지 잰다.
 *
 * 못 한 태스크가 다음 날로 넘어가면 다음 날 분량이 커지고, 커질수록 또 못 해서 더 쌓인다.
 * 그 눈덩이가 며칠 만에 감당 불가가 되는지, 상한을 두면 어떻게 달라지는지 본다.
 *
 * 실행: ./gradlew :sim:run --args="carry"
 */

private val MON_FRI = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private class CarryStats(
    val full: Int, val partial: Int, val none: Int,
    val meanStreak: Double, val meanCarry: Double, val maxCarry: Int,
    val heavyDays: Int, val judged: Int, val resets: Int, val replans: Int, val days: Long,
)

/**
 * @param cap 이월 상한 (원래 분량의 배수). null 이면 무제한
 * @param replanAt 이월이 원래 분량의 이 배수를 넘으면 사용자가 계획을 다시 짠다. null 이면 안 짬
 * @param capacity 하루에 손댈 수 있는 태스크 수의 상한 — 원래 하루치의 배수.
 *   사람은 오늘 분량이 4배가 됐다고 4배를 하지 않는다. 이 한계가 눈덩이를 만든다.
 */
private fun runCarry(
    cap: Int?,
    carryOver: Boolean,
    replanAt: Int?,
    skip: Double,
    capacity: Double = Double.MAX_VALUE,
    trials: Int = 300,
    daysPerTrial: Int = 720,
    seed: Int = 77,
): CarryStats {
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val start = LocalDate.of(2026, 9, 7)
    val tasksPerDay = 3

    var full = 0; var partial = 0; var none = 0
    var resets = 0; var replans = 0
    var carrySum = 0L; var carryDays = 0L; var maxCarry = 0; var heavy = 0
    val lengths = mutableListOf<Int>()

    repeat(trials) {
        val acc = Account()
        val subject = Subject(
            "정석", required = true, weekdays = MON_FRI, tasks = tasksPerDay,
            carryOver = carryOver, carryOverCap = cap,
        )
        val subs = listOf(subject)
        for (i in 0 until daysPerTrial) {
            val date = start.plusDays(i.toLong())
            if (!subject.studiesOn(date)) continue

            val required = subject.tasksOn(date)
            val skipped = rng.nextDouble() < skip
            // 손댈 수 있는 만큼만 시도하고, 시도한 것 중 95%를 해낸다
            val attempt = if (capacity == Double.MAX_VALUE) required
            else minOf(required, kotlin.math.ceil(capacity * tasksPerDay).toInt())
            val checked = if (skipped) 0 else (0 until attempt).count { rng.nextDouble() < 0.95 }

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

            carrySum += subject.carriedTasks
            carryDays++
            if (subject.carriedTasks > maxCarry) maxCarry = subject.carriedTasks
            if (subject.carriedTasks >= 3 * tasksPerDay) heavy++ // 하루가 4배 이상 무거워진 날

            // 앱이 "이렇게 조정할까요?" 카드를 띄우고 사용자가 수락하는 경우
            if (replanAt != null && subject.carriedTasks > replanAt * tasksPerDay) {
                subject.clearCarryOver()
                replans++
            }

            val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (acc.logs.size > 2 * StreakRules.WINDOW_DAYS) acc.logs.removeAll { it.date < keepFrom }
        }
    }

    return CarryStats(
        full, partial, none,
        meanStreak = if (lengths.isEmpty()) Double.NaN else lengths.average(),
        meanCarry = carrySum.toDouble() / carryDays,
        maxCarry = maxCarry,
        heavyDays = heavy, judged = full + partial + none,
        resets = resets, replans = replans, days = carryDays,
    )
}

fun printCarryOver() {
    println("=".repeat(RULE_WIDTH))
    println("이월 규칙 — 못 한 태스크가 다음 학습일로 넘어간다")
    println("필수 1과목 · 원래 하루 3태스크 · 평일 · 한 달에 2일쯤 아예 안 염 · 설정당 21만 학습일")
    println("=".repeat(RULE_WIDTH))
    println()
    println("핵심은 \"하루에 얼마나 더 할 수 있느냐\" 다.")
    println("오늘 분량이 4배가 됐다고 4배를 하는 사람은 없다. 그 한계가 눈덩이를 만든다.")

    val capacities = listOf(
        1.0 to "원래 하루치까지만",
        1.3 to "원래의 1.3배까지",
        1.5 to "원래의 1.5배까지",
        2.0 to "원래의 2배까지",
    )
    val rows = listOf<Triple<String, Pair<Boolean, Int?>, Int?>>(
        Triple("이월 없음 (지금까지)", false to null, null),
        Triple("이월 · 무제한", true to null, null),
        Triple("이월 · 2배 상한", true to 2, null),
        Triple("무제한 + 2배에서 재설정", true to null, 2),
    )

    for ((capacity, capLabel) in capacities) {
        println()
        println("하루 처리 능력: $capLabel")
        println("-".repeat(RULE_WIDTH))
        print(cell("설정", 22))
        for (h in listOf("○", "△", "✕", "평균 유지", "평균 이월", "최대", "4배+인 날")) print(cell(h, 10))
        println()

        for ((label, conf, replanAt) in rows) {
            val (on, cap) = conf
            val s = runCarry(cap, on, replanAt, skip = 0.10, capacity = capacity, seed = 77)
            print(cell(label, 22))
            print(cell("%.1f%%".format(100.0 * s.full / s.judged), 10))
            print(cell("%.1f%%".format(100.0 * s.partial / s.judged), 10))
            print(cell("%.1f%%".format(100.0 * s.none / s.judged), 10))
            print(cell("%.1f일".format(s.meanStreak), 10))
            print(cell("%.1f개".format(s.meanCarry), 10))
            print(cell("${s.maxCarry}개", 10))
            print(cell("%.0f%%".format(100.0 * s.heavyDays / s.days), 10))
            println()
        }
    }

    println()
    println("-".repeat(RULE_WIDTH))
    println("재설정을 어느 선에서 권할 것인가 — 처리 능력 1.3배, 이월 무제한")
    println("-".repeat(RULE_WIDTH))
    print(cell("재설정 기준", 22))
    for (h in listOf("평균 유지", "평균 이월", "최대", "재설정/월")) print(cell(h, 12))
    println()
    for (at in listOf(null, 1, 2, 3, 5)) {
        val s = runCarry(cap = null, carryOver = true, replanAt = at, skip = 0.10, capacity = 1.3, seed = 4242)
        print(cell(if (at == null) "안 함" else "원래의 ${at}배 넘으면", 22))
        print(cell("%.1f일".format(s.meanStreak), 12))
        print(cell("%.1f개".format(s.meanCarry), 12))
        print(cell("${s.maxCarry}개", 12))
        print(cell("%.1f회".format(30.4 * s.replans / s.days), 12))
        println()
    }
    println("=".repeat(RULE_WIDTH))
}
