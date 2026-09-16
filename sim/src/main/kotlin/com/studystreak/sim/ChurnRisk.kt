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
 * 재설정 카드를 언제 띄울 것인가 — **이탈 위험**을 기준으로 고른다.
 *
 * 이탈률 자체는 못 잰다(앱이 없으니 행동 데이터가 없다). 대신 기획 12장이 이탈 위험으로
 * 지목한 것들을 대리 지표로 잰다.
 *
 * | 12장의 위험 | 지표 |
 * |---|---|
 * | streak 끊긴 날 그대로 이탈 | 초기화/월 |
 * | 플랜을 과하게 잡고 포기 | 하루가 감당 불가해지는 빈도 |
 * | 성취감이 없으면 안 함 (3장 가설) | ○를 한 번도 못 받는 최장 구간 |
 * | 알림 피로 | 카드/월 |
 *
 * 실행: ./gradlew :sim:run --args="churn"
 */

private val WD = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

/** 재설정 카드를 언제 띄우나 */
private enum class Card {
    /** 안 띄운다 */
    NEVER,

    /** 이월이 생기는 즉시 — "2개가 내일로 넘어가요. 계획을 조정할까요?" */
    ON_CARRY,

    /** 진도가 사라지기 시작할 때 — "이대로면 3개가 계획에서 빠져요" */
    ON_DROPPED,
}

private class Risk(
    val resetsPerMonth: Double,
    val fullRatio: Double,
    val cardsPerMonth: Double,
    val longestDrought: Int,
    val meanDrought: Double,
    val meanStreak: Double,
)

private fun measureRisk(
    carryOver: Boolean,
    card: Card,
    skip: Double,
    capacity: Double = 1.3,
    trials: Int = 400,
    daysPerTrial: Int = 720,
    seed: Int = 909,
): Risk {
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val start = LocalDate.of(2026, 9, 7)
    val tasksPerDay = 3

    var studyDays = 0L; var fulls = 0; var resets = 0; var cards = 0
    var longestDrought = 0
    val droughts = mutableListOf<Int>()
    val streakLengths = mutableListOf<Int>()

    repeat(trials) {
        val acc = Account()
        val subject = Subject(
            "정석", required = true, weekdays = WD, tasks = tasksPerDay,
            carryOver = carryOver, // maxDaysWorth 는 기본값(하루 이틀치)
        )
        val subs = listOf(subject)
        var drought = 0

        for (i in 0 until daysPerTrial) {
            val date = start.plusDays(i.toLong())
            if (!subject.studiesOn(date)) continue
            studyDays++

            val required = subject.tasksOn(date)
            val skipped = skip > 0.0 && rng.nextDouble() < skip
            val attempt = minOf(required, kotlin.math.ceil(capacity * tasksPerDay).toInt())
            val checked = if (skipped) 0 else (0 until attempt).count { rng.nextDouble() < 0.95 }

            val before = acc.overall
            val r = engine.settle(acc, subs, date, mapOf("정석" to checked))

            if (r.log.mark == Mark.FULL) {
                fulls++
                if (drought > 0) droughts += drought
                if (drought > longestDrought) longestDrought = drought
                drought = 0
            } else {
                drought++
            }
            when (r.event) {
                is SettleEvent.PartialReset -> { streakLengths += before + 1; resets++ }
                SettleEvent.ResetWithoutFreeze -> { streakLengths += before; resets++ }
                else -> Unit
            }

            // 카드를 띄우고 사용자가 받아들였다고 본다 (가장 낙관적인 경우)
            val show = when (card) {
                Card.NEVER -> false
                Card.ON_CARRY -> subject.carriedTasks > 0
                Card.ON_DROPPED -> r.carryDropped.isNotEmpty()
            }
            if (show) {
                cards++
                subject.clearCarryOver()
            }

            val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (acc.logs.size > 2 * StreakRules.WINDOW_DAYS) acc.logs.removeAll { it.date < keepFrom }
        }
        if (drought > longestDrought) longestDrought = drought
    }

    val studyDaysPerMonth = 30.4 * 5 / 7
    return Risk(
        resetsPerMonth = studyDaysPerMonth * resets / studyDays,
        fullRatio = 100.0 * fulls / studyDays,
        cardsPerMonth = studyDaysPerMonth * cards / studyDays,
        longestDrought = longestDrought,
        meanDrought = if (droughts.isEmpty()) 0.0 else droughts.average(),
        meanStreak = if (streakLengths.isEmpty()) Double.NaN else streakLengths.average(),
    )
}

fun printChurnRisk() {
    println("=".repeat(RULE_WIDTH))
    println("이탈 위험으로 본 재설정 카드 시점")
    println("필수 1과목 · 하루 3태스크 · 평일 · 여는 날 완료율 95% · 하루 처리 능력 1.3배")
    println("설정당 29만 학습일. 카드는 뜨면 사용자가 받아들인다고 본다")
    println("=".repeat(RULE_WIDTH))
    println()
    println("\"○ 가뭄\" = 연속으로 ○를 한 번도 못 받은 학습일 수. 3장 가설상 성취감이 없는 구간이다.")

    val options = listOf(
        Triple("이월 안 씀", false, Card.NEVER),
        Triple("이월만, 카드 없음", true, Card.NEVER),
        Triple("이월 + 넘칠 때 카드", true, Card.ON_DROPPED),
        Triple("이월 + 생기면 바로 카드", true, Card.ON_CARRY),
    )

    for (skip in listOf(0.02, 0.05, 0.10, 0.20)) {
        println()
        println("한 달에 ${"%.1f".format(skip * 30.4 * 5 / 7)}일쯤 아예 안 여는 사용자")
        println("-".repeat(RULE_WIDTH))
        print(cell("설정", 24))
        for (h in listOf("초기화/월", "○ 비율", "○ 가뭄 최장", "평균 가뭄", "카드/월")) print(cell(h, 11))
        println()

        for ((label, carry, card) in options) {
            val r = measureRisk(carry, card, skip, seed = 909 + (skip * 100).toInt())
            print(cell(label, 24))
            print(cell("%.1f회".format(r.resetsPerMonth), 11))
            print(cell("%.0f%%".format(r.fullRatio), 11))
            print(cell("${r.longestDrought}일", 11))
            print(cell("%.1f일".format(r.meanDrought), 11))
            print(cell("%.1f회".format(r.cardsPerMonth), 11))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
}
