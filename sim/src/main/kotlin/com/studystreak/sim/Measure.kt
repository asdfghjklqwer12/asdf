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
 * 기획 노트 5.2의 숫자 표를 **이식된 엔진으로 다시 재어본다.**
 *
 * 설계 결정("완화 장치를 넣지 않는다")이 그 표 위에 서 있으므로, 옮긴 엔진이 같은 숫자를
 * 내는지 확인할 값어치가 있다. 규칙은 건드리지 않고 관찰만 한다.
 *
 * 조건은 기획 노트가 적어둔 그대로다 — 과목당 태스크 3개, 평일 학습, 필수 과목만.
 * 프리즈·조각은 사용자가 행동해야 생기므로 이 측정에서는 한 번도 안 생긴다 (기본 규칙만 본다).
 *
 * 실행: ./gradlew :sim:run --args="measure"
 */

private val MON_TO_FRI = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)

/** 기획 노트 5.2가 적어둔 값 — 비교 대상 */
private val PLAN_NOTE_STREAK = mapOf(
    0.97 to listOf(116, 59, 26, 16, 11),
    0.93 to listOf(39, 12, 7, 5, 4),
    0.88 to listOf(14, 5, 4, 3, 2),
)
private val PLAN_NOTE_PARTIAL_RATIO = mapOf(1 to 14.6, 3 to 35.1, 5 to 48.1) // 완료율 93%일 때

private class Measurement(
    val meanStreakAtReset: Double,
    val partialRatioAllDays: Double,
    val partialRatioStudyDays: Double,
    val resets: Int,
    val marks: Map<Mark, Int>,
)

private fun measure(
    subjectCount: Int,
    completionRate: Double,
    trials: Int,
    daysPerTrial: Int,
    seed: Int,
    /** 그날 앱을 아예 안 여는 확률. 태스크를 하나씩 빼먹는 것과는 다른 실패 방식이다. */
    skipRate: Double = 0.0,
): Measurement {
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val start = LocalDate.of(2026, 9, 7)

    val streakLengths = mutableListOf<Int>()
    val marks = mutableMapOf(Mark.FULL to 0, Mark.PARTIAL to 0, Mark.NONE to 0, Mark.REST to 0)
    var resets = 0

    repeat(trials) {
        val account = Account()
        val subjects = (1..subjectCount).map {
            Subject("과목$it", required = true, weekdays = MON_TO_FRI, tasks = 3)
        }
        var beforeSettle = 0

        for (i in 0 until daysPerTrial) {
            val date = start.plusDays(i.toLong())
            val skipped = rng.nextDouble() < skipRate
            val done = subjects.associate { s ->
                s.name to if (skipped) 0 else (0 until s.tasks).count { rng.nextDouble() < completionRate }
            }
            beforeSettle = account.overall
            val result = engine.settle(account, subjects, date, done)
            marks[result.log.mark] = marks.getValue(result.log.mark) + 1

            when (result.event) {
                // △로 죽은 날은 그날 +1을 받았다가 0이 됐으므로 한 칸 더 산 것이다
                is SettleEvent.PartialReset -> { streakLengths += beforeSettle + 1; resets++ }
                // ✕로 죽은 날은 +1이 없었다
                SettleEvent.ResetWithoutFreeze -> { streakLengths += beforeSettle; resets++ }
                else -> Unit
            }

            // 7일 창 밖의 로그는 이후 판정에 관여하지 않는다. 날짜를 되돌리거나 재정산하지 않는
            // 이 측정에서만 안전한 최적화다 (settle 이 매번 전체 로그를 훑기 때문에 필요하다).
            val keepFrom = date.minusDays(StreakRules.WINDOW_DAYS.toLong())
            if (account.logs.size > 2 * StreakRules.WINDOW_DAYS) {
                account.logs.removeAll { it.date < keepFrom }
            }
        }
    }

    val judged = marks.getValue(Mark.FULL) + marks.getValue(Mark.PARTIAL) + marks.getValue(Mark.NONE)
    val allDays = trials.toLong() * daysPerTrial
    return Measurement(
        meanStreakAtReset = if (streakLengths.isEmpty()) Double.NaN else streakLengths.average(),
        partialRatioAllDays = 100.0 * marks.getValue(Mark.PARTIAL) / allDays,
        partialRatioStudyDays = 100.0 * marks.getValue(Mark.PARTIAL) / judged,
        resets = resets,
        marks = marks,
    )
}

fun printMeasurement() {
    val trials = 400
    val daysPerTrial = 1000
    val rates = listOf(0.97, 0.93, 0.88)

    println("=".repeat(RULE_WIDTH))
    println("기획 노트 5.2 재측정  ·  과목당 태스크 3개 · 평일 학습 · 필수만")
    println("시행 ${trials}회 × ${daysPerTrial}일 = 설정당 ${trials * daysPerTrial / 1000}천 일")
    println("=".repeat(RULE_WIDTH))

    println()
    println("[1] 평균 streak 유지 일수 — 초기화될 때까지 버틴 길이")
    println("-".repeat(RULE_WIDTH))
    print(cell("태스크 완료율", 16))
    for (n in 1..5) print(cell("필수 ${n}개", 12))
    println()
    println("-".repeat(RULE_WIDTH))

    val measured = mutableMapOf<Pair<Double, Int>, Measurement>()
    for (rate in rates) {
        print(cell("${(rate * 100).toInt()}%  측정", 16))
        for (n in 1..5) {
            val m = measure(n, rate, trials, daysPerTrial, seed = 1000 + n * 7 + (rate * 100).toInt())
            measured[rate to n] = m
            print(cell("%.1f일".format(m.meanStreakAtReset), 12))
        }
        println()
        print(cell("      기획 노트", 16))
        for (v in PLAN_NOTE_STREAK.getValue(rate)) print(cell("${v}일", 12))
        println()
        println()
    }

    println("-".repeat(RULE_WIDTH))
    println("[2] △가 찍히는 비율 — 완료율 93%")
    println("-".repeat(RULE_WIDTH))
    print(cell("", 16))
    for (n in 1..5) print(cell("필수 ${n}개", 12))
    println()
    print(cell("전체 날짜 대비", 16))
    for (n in 1..5) print(cell("%.1f%%".format(measured.getValue(0.93 to n).partialRatioAllDays), 12))
    println()
    print(cell("학습일 대비", 16))
    for (n in 1..5) print(cell("%.1f%%".format(measured.getValue(0.93 to n).partialRatioStudyDays), 12))
    println()
    print(cell("기획 노트", 16))
    for (n in 1..5) {
        print(cell(PLAN_NOTE_PARTIAL_RATIO[n]?.let { "%.1f%%".format(it) } ?: "—", 12))
    }
    println()

    println()
    println("-".repeat(RULE_WIDTH))
    println("[3] 판정 분포 — 완료율 93%")
    println("-".repeat(RULE_WIDTH))
    print(cell("", 16))
    for (label in listOf("○", "△", "✕", "휴식")) print(cell(label, 12))
    println()
    for (n in 1..5) {
        val m = measured.getValue(0.93 to n)
        val total = m.marks.values.sum().toDouble()
        print(cell("필수 ${n}개", 16))
        for (mark in listOf(Mark.FULL, Mark.PARTIAL, Mark.NONE, Mark.REST)) {
            print(cell("%.1f%%".format(100.0 * m.marks.getValue(mark) / total), 12))
        }
        println()
    }
    println("=".repeat(RULE_WIDTH))
}

/**
 * 완료율을 쓸어보며 ○ / △ / ✕ 가 어떤 비율로 나오는지 본다.
 *
 * 기획 13장의 "남은 건 숫자 튜닝" 중 조각·프리즈 비율을 정하려면
 * **✕가 애초에 얼마나 자주 나오는지**를 먼저 알아야 한다. 프리즈는 ✕만 막기 때문이다.
 *
 * 실행: ./gradlew :sim:run --args="rates"
 */
fun printRateSweep() {
    val trials = 200
    val daysPerTrial = 1000

    println("=".repeat(RULE_WIDTH))
    println("완료율에 따른 판정 분포와 초기화 원인")
    println("과목당 태스크 3개 · 평일 학습 · 프리즈 없음 · 설정당 ${trials * daysPerTrial / 1000}천 일")
    println("=".repeat(RULE_WIDTH))

    for (subjectCount in listOf(1, 3)) {
        println()
        println("필수 ${subjectCount}과목 (그날 태스크 ${subjectCount * 3}개)")
        println("-".repeat(RULE_WIDTH))
        print(cell("완료율", 9))
        for (label in listOf("○", "△", "✕", "평균 유지", "초기화/천일")) print(cell(label, 12))
        println()

        for (rate in listOf(0.99, 0.97, 0.95, 0.93, 0.90, 0.85, 0.80, 0.70, 0.60, 0.50)) {
            val m = measure(subjectCount, rate, trials, daysPerTrial, seed = 7777 + (rate * 100).toInt())
            val judged = (m.marks.getValue(Mark.FULL) + m.marks.getValue(Mark.PARTIAL) +
                m.marks.getValue(Mark.NONE)).toDouble()
            print(cell("${(rate * 100).toInt()}%", 9))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.FULL) / judged), 12))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.PARTIAL) / judged), 12))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.NONE) / judged), 12))
            print(cell("%.1f일".format(m.meanStreakAtReset), 12))
            print(cell("%.1f회".format(1000.0 * m.resets / (trials.toDouble() * daysPerTrial)), 12))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("→ ✕ 열이 프리즈가 막아줄 수 있는 날의 비율이다. 0%면 프리즈가 할 일이 없다.")
    println("=".repeat(RULE_WIDTH))
}

/** 시행 길이를 늘려도 평균이 안 변하는지 — 잘림(censoring) 편향 확인 */
fun printCensoringCheck() {
    println("=".repeat(RULE_WIDTH))
    println("시행 길이별 평균 streak 유지 일수 — 측정이 시행 길이에 흔들리는가")
    println("=".repeat(RULE_WIDTH))
    print(cell("설정", 22))
    for (d in listOf(500, 1000, 2000, 5000, 10000)) print(cell("${d}일", 11))
    println()
    println("-".repeat(RULE_WIDTH))
    for ((n, rate) in listOf(1 to 0.97, 1 to 0.93, 3 to 0.97, 3 to 0.93)) {
        print(cell("필수 ${n}개 · ${(rate * 100).toInt()}%", 22))
        for (days in listOf(500, 1000, 2000, 5000, 10000)) {
            val trials = maxOf(40, 400_000 / days)
            val m = measure(n, rate, trials, days, seed = 31 + days)
            print(cell("%.1f".format(m.meanStreakAtReset), 11))
        }
        println()
    }
    println("=".repeat(RULE_WIDTH))
}


/**
 * 더 현실적인 실패 모델 — 사용자는 태스크를 하나씩 빼먹는 게 아니라 **그날을 통째로 건너뛴다.**
 *
 * 앞의 rates 측정은 매일 앱을 열고 태스크만 조금 흘리는 사용자를 가정한다. 그런 사용자에게는
 * ✕가 거의 안 생기고, 따라서 프리즈·조각·광고·결제 전체가 할 일이 없다.
 * 여기서는 "그날 아예 안 연 날"을 섞어서 프리즈가 실제로 몇 번이나 쓰일지 본다.
 *
 * 실행: ./gradlew :sim:run --args="skips"
 */
fun printSkipSweep() {
    val trials = 200
    val daysPerTrial = 1000
    val completionRate = 0.95 // 여는 날은 성실하다고 두고, 안 여는 날의 비율만 바꾼다

    println("=".repeat(RULE_WIDTH))
    println("빼먹는 날이 섞이면 — 여는 날 완료율 ${(completionRate * 100).toInt()}% 고정")
    println("과목당 태스크 3개 · 평일 학습 · 프리즈 없음 · 설정당 ${trials * daysPerTrial / 1000}천 일")
    println("=".repeat(RULE_WIDTH))

    for (subjectCount in listOf(1, 3)) {
        println()
        println("필수 ${subjectCount}과목")
        println("-".repeat(RULE_WIDTH))
        print(cell("안 여는 날", 12))
        for (label in listOf("○", "△", "✕", "평균 유지", "✕/월", "초기화/월")) print(cell(label, 11))
        println()

        for (skip in listOf(0.0, 0.02, 0.05, 0.10, 0.15, 0.20, 0.30, 0.50)) {
            val m = measure(subjectCount, completionRate, trials, daysPerTrial,
                seed = 4242 + (skip * 100).toInt(), skipRate = skip)
            val judged = (m.marks.getValue(Mark.FULL) + m.marks.getValue(Mark.PARTIAL) +
                m.marks.getValue(Mark.NONE)).toDouble()
            val allDays = trials.toDouble() * daysPerTrial
            print(cell("%.0f%%".format(skip * 100), 12))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.FULL) / judged), 11))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.PARTIAL) / judged), 11))
            print(cell("%.1f%%".format(100 * m.marks.getValue(Mark.NONE) / judged), 11))
            print(cell("%.1f일".format(m.meanStreakAtReset), 11))
            print(cell("%.1f회".format(30.4 * m.marks.getValue(Mark.NONE) / allDays), 11))
            print(cell("%.1f회".format(30.4 * m.resets / allDays), 11))
            println()
        }
    }
    println("=".repeat(RULE_WIDTH))
    println("→ \"✕/월\" 이 프리즈가 필요한 횟수다. 프리즈 1개는 조각 6개(= 광고 12편 또는 2주치 휴식 체크).")
    println("=".repeat(RULE_WIDTH))
}
