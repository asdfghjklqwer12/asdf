package com.studystreak.sim

import com.studystreak.domain.streak.Account
import com.studystreak.domain.streak.Mark
import com.studystreak.domain.streak.SettleEvent
import com.studystreak.domain.streak.ShardResult
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
 * A7 — 광고로 조각을 주는 게 맞는가, 프리즈가 ✕만 막는 게 맞는가.
 *
 * 9절에서 "광고 경로가 ✕ 규칙을 헐겁게 만든다"까지는 나왔지만, 대안을 나란히 재본 적이
 * 없다. 네 가지를 **같은 씨앗**으로 돌린다 — 조각과 프리즈는 판정(○△✕)에 안 끼어들므로
 * 네 설정의 마크 열이 완전히 같다. 난수는 완료율과 "안 여는 날"에만 쓴다.
 *
 * 실행: ./gradlew :sim:run --args="a7"
 */

private val MON_FRI = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

private class Setup(val label: String, val ads: Boolean, val restChecks: Boolean, val blocksPartial: Boolean)

private val SETUPS = listOf(
    Setup("장치 없음", ads = false, restChecks = false, blocksPartial = false),
    Setup("A 현행", ads = true, restChecks = true, blocksPartial = false),
    Setup("B 광고 뺌", ads = false, restChecks = true, blocksPartial = false),
    Setup("C 광고+△방어", ads = true, restChecks = true, blocksPartial = true),
    Setup("D △방어만", ads = false, restChecks = true, blocksPartial = true),
)

private class A7Run(
    val resets: Int, val fromPartial: Int, val fromNone: Int,
    val blockedNone: Int, val blockedPartial: Int,
    val wastedShards: Int, val meanStreak: Double, val months: Double,
)

private fun runA7(
    setup: Setup,
    subjectCount: Int,
    completionRate: Double = 0.95,
    skipRate: Double = 0.07, // 한 달에 이틀쯤 아예 안 연다 (9절과 같은 사용자)
    trials: Int = 400,
    days: Int = 1000,
    seed: Int = 20260916,
    /** 광고를 보는 날의 비율. 1.0 이면 난수를 안 뽑아 위 표의 수치가 그대로 재현된다 */
    adRate: Double = 1.0,
): A7Run {
    val engine = StreakEngine(
        Clock.fixed(Instant.parse("2026-01-01T12:00:00Z"), ZoneOffset.UTC),
        freezeBlocksPartialReset = setup.blocksPartial,
    )
    val rng = Random(seed)
    val start = LocalDate.of(2026, 1, 1)

    var resets = 0; var fromPartial = 0; var fromNone = 0
    var blockedNone = 0; var blockedPartial = 0; var wasted = 0
    val streakLengths = mutableListOf<Int>()

    repeat(trials) {
        val account = Account()
        val subjects = (1..subjectCount).map {
            Subject("과목$it", required = true, weekdays = MON_FRI, tasks = 3)
        }

        for (i in 0 until days) {
            val date = start.plusDays(i.toLong())
            val skipped = rng.nextDouble() < skipRate
            val done = subjects.associate { s ->
                s.name to (0 until s.tasks).count { rng.nextDouble() < completionRate && !skipped }
            }
            val before = account.overall
            val result = engine.settle(account, subjects, date, done)

            when (result.event) {
                is SettleEvent.PartialReset -> { resets++; fromPartial++; streakLengths += before + 1 }
                SettleEvent.ResetWithoutFreeze -> { resets++; fromNone++; streakLengths += before }
                SettleEvent.FreezeDefended -> blockedNone++
                is SettleEvent.FreezeDefendedPartial -> blockedPartial++
                null -> Unit
            }

            // 조각 수급 — 난수를 안 쓴다. 설정마다 난수열이 어긋나면 비교가 안 된다
            if (setup.restChecks && result.log.mark == Mark.REST) {
                if (account.restCheck(date).rejected) wasted++
            }
            if (setup.ads && (adRate >= 1.0 || rng.nextDouble() < adRate)) {
                repeat(StreakRules.AD_VIEWS_PER_DAY) {
                    if (account.watchAd(date).rejected) wasted++
                }
            }

            val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (account.logs.size > 2 * StreakRules.WINDOW_DAYS) {
                account.logs.removeAll { it.date < keepFrom }
            }
            if (account.adViews.size > 8) account.adViews.removeAll { it < keepFrom }
            if (account.shardEvents.size > 16) account.shardEvents.removeAll { it.date < keepFrom }
        }
    }

    val months = trials.toDouble() * days / 30.0
    return A7Run(
        resets, fromPartial, fromNone, blockedNone, blockedPartial, wasted,
        meanStreak = if (streakLengths.isEmpty()) Double.NaN else streakLengths.average(),
        months = months,
    )
}

fun printA7() {
    println("=".repeat(RULE_WIDTH))
    println("A7 — 광고로 조각을 주는 게 맞는가, 프리즈가 ✕만 막는 게 맞는가")
    println("=".repeat(RULE_WIDTH))
    println("평일 학습 · 과목당 태스크 3개 · 여는 날 완료율 95% · 한 달에 이틀쯤 아예 안 엶")
    println("400명 × 1000일 = 설정당 40만 일 · 광고는 매일 2편, 휴식일엔 매번 자발 체크")
    println("네 설정을 같은 씨앗으로 — 조각/프리즈는 판정에 안 끼어들어 ○△✕ 열이 완전히 같다")

    for (subjectCount in listOf(1, 3)) {
        println()
        println("필수 ${subjectCount}과목")
        println("-".repeat(RULE_WIDTH))
        print(cell("설정", 15))
        for (h in listOf("초기화/월", "평균 유지", "△발/✕발", "막은 날/월", "조각 낭비/월")) print(cell(h, 13))
        println()
        for (setup in SETUPS) {
            val r = runA7(setup, subjectCount)
            print(cell(setup.label, 15))
            print(cell("%.2f회".format(r.resets / r.months), 13))
            print(cell("%.1f일".format(r.meanStreak), 13))
            print(cell(
                if (r.resets == 0) "—"
                else "%.0f%% / %.0f%%".format(100.0 * r.fromPartial / r.resets, 100.0 * r.fromNone / r.resets), 13))
            print(cell("%.2f / %.2f".format(r.blockedNone / r.months, r.blockedPartial / r.months), 13))
            println(cell("%.1f회".format(r.wastedShards / r.months), 13))
        }
    }
    // 광고를 매일 보는 사용자는 드물다 — 얼마나 자주 봐야 규칙이 헐거워지나
    println()
    println("광고를 보는 날의 비율에 따라 (필수 1과목 · A 현행)")
    println("-".repeat(RULE_WIDTH))
    print(cell("광고 보는 날", 15))
    for (h in listOf("초기화/월", "평균 유지", "장치 없음 대비", "조각 낭비/월")) print(cell(h, 15))
    println()
    val bare = runA7(SETUPS[0], 1)
    for (rate in listOf(0.0, 0.25, 0.50, 0.75, 1.0)) {
        val r = runA7(SETUPS[1], 1, adRate = rate)
        print(cell(if (rate == 0.0) "안 봄" else "${(rate * 100).toInt()}%", 15))
        print(cell("%.2f회".format(r.resets / r.months), 15))
        print(cell("%.1f일".format(r.meanStreak), 15))
        print(cell("%+.0f%%".format(100.0 * (r.meanStreak - bare.meanStreak) / bare.meanStreak), 15))
        println(cell("%.1f회".format(r.wastedShards / r.months), 15))
    }

    println("=".repeat(RULE_WIDTH))
    println("\"막은 날/월\" = ✕를 막은 날 / △ 초기화를 막은 날")
    println("\"조각 낭비\" = 상한에 걸려 버려진 조각. 수급이 소모보다 많다는 증거")
    println("=".repeat(RULE_WIDTH))
}
