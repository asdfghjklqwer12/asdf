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
 * streak_sim.py 의 scenario() 를 옮긴 60일 시뮬레이터.
 *
 * 검증이 아니라 **눈으로 보는 용도**다 — 판정·초기화·프리즈·조각이 60일 동안 어떻게 움직이는지
 * 하루씩 표로 찍는다. 규칙이 맞는지는 :domain 의 테스트 45개가 본다.
 *
 * 실행: ./gradlew :sim:run          (씨앗 고정 — 돌릴 때마다 같은 결과)
 *       ./gradlew :sim:run --args="42"
 */

private val MON_TO_FRI = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
)
private val MON_WED_FRI = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

private val WEEKDAY_LABEL = "월화수목금토일"

private fun markGlyph(mark: Mark) = when (mark) {
    Mark.FULL -> "○"
    Mark.PARTIAL -> "△"
    Mark.NONE -> "✕"
    Mark.REST -> "·"
}

private class Row(
    val date: LocalDate,
    val mark: Mark,
    val due: Int,
    val done: Int,
    val overall: Int,
    val partialsInWindow: Int,
    val freeze: Int,
    val shards: Int,
    val note: String,
)

private fun runScenario(seed: Int): Triple<Account, List<Subject>, List<Row>> {
    val account = Account()
    val subjects = listOf(
        Subject("수학", required = true, weekdays = MON_TO_FRI, tasks = 2),
        Subject("영어", required = true, weekdays = MON_TO_FRI, tasks = 1),
        Subject("국어", required = true, weekdays = MON_WED_FRI, tasks = 2),
        Subject("한국사", required = false, weekdays = MON_TO_FRI, tasks = 1), // 선택
    )
    val byName = subjects.associateBy { it.name }

    // 판정은 전부 주입받은 시계로만 한다. 시뮬레이터는 날짜를 직접 넘기므로 고정 시계면 충분하다.
    val engine = StreakEngine(Clock.fixed(Instant.parse("2026-09-07T12:00:00Z"), ZoneOffset.UTC))
    val rng = Random(seed)
    val start = LocalDate.of(2026, 9, 7)
    val rows = mutableListOf<Row>()

    for (i in 0 until 60) {
        val date = start.plusDays(i.toLong())
        val due = subjects.filter { it.required && it.studiesOn(date) }

        // 구간별 성실도를 다르게 줘서 여러 상황을 만든다
        val p = when {
            i < 14 -> 0.95 // 초반: 아주 성실
            i < 24 -> 0.62 // 중반: 흔들림 (△가 자주 나오는 구간)
            i < 30 -> 0.20 // 슬럼프 (✕가 나오는 구간)
            else -> 0.88   // 회복
        }

        val done = mutableMapOf<String, Int>()
        for (subject in due) {
            done[subject.name] = (0 until subject.tasks).count { rng.nextDouble() < p }
        }
        // 선택 과목은 절반쯤
        val history = byName.getValue("한국사")
        if (history.studiesOn(date) && rng.nextDouble() < 0.5) {
            done["한국사"] = 1
        }

        var note = ""
        if (due.isEmpty() && rng.nextDouble() < 0.6) {
            // 휴식일에 자발적으로 체크하는 경우
            note = describe(account.restCheck(date), "휴식일 자발 체크")
        } else if (account.freeze == 0 && account.overall >= 5 && rng.nextDouble() < 0.35) {
            // streak이 위태로우면 광고를 본다 (프리즈가 없고 최근에 흔들렸을 때)
            note = describe(account.watchAd(date), "광고 시청")
        }

        val result = engine.settle(account, subjects, date, done)
        describe(result.event)?.let { note = it } // 정산에서 사건이 났으면 그쪽이 더 중요하다

        val lo = date.minusDays((StreakRules.WINDOW_DAYS - 1).toLong())
        val partials = account.logs.count {
            it.mark == Mark.PARTIAL && it.date >= lo && it.date <= date && !it.consumed
        }

        rows += Row(
            date = date,
            mark = result.log.mark,
            due = result.log.due,
            done = result.log.done,
            overall = account.overall,
            partialsInWindow = partials,
            freeze = account.freeze,
            shards = account.shards,
            note = note,
        )
    }
    return Triple(account, subjects, rows)
}

private fun describe(event: SettleEvent?): String? = when (event) {
    is SettleEvent.PartialReset -> "△ ${event.partialCount}개 → 초기화"
    SettleEvent.FreezeDefended -> "프리즈로 방어 (숫자 유지)"
    SettleEvent.ResetWithoutFreeze -> "프리즈 없음 → 초기화"
    null -> null
}

private fun describe(result: ShardResult, action: String): String = when (result) {
    ShardResult.FreezeEarned -> "$action → 조각+1, 프리즈 획득"
    ShardResult.ShardGranted -> "$action → 조각+1"
    is ShardResult.AdProgressed -> "$action (${result.progress}/${result.needed})"
    is ShardResult.Rejected -> when (result.reason) {
        ShardResult.Rejected.Reason.FULL -> "$action → 거절 (가득 참)"
        ShardResult.Rejected.Reason.WEEKLY_REST_LIMIT -> "$action → 거절 (주 상한)"
        ShardResult.Rejected.Reason.DAILY_AD_LIMIT -> "$action → 거절 (일 상한)"
    }
}

// ── 표 그리기 ────────────────────────────────────────────────
// 한글은 터미널에서 두 칸을 먹는다. 그냥 padEnd 를 쓰면 열이 어긋난다.

internal fun widthOf(text: String): Int {
    var width = 0
    for (ch in text) {
        val c = ch.code
        val wide = c in 0x1100..0x115F || c in 0x2E80..0xA4CF ||
            c in 0xAC00..0xD7A3 || c in 0xF900..0xFAFF ||
            c in 0xFE30..0xFE6F || c in 0xFF00..0xFF60 || c in 0xFFE0..0xFFE6
        width += if (wide) 2 else 1
    }
    return width
}

internal fun cell(text: String, width: Int): String = text + " ".repeat(maxOf(0, width - widthOf(text)))

internal const val RULE_WIDTH = 78

fun printScenario(seed: Int) {
    val (account, subjects, rows) = runScenario(seed)

    println("=".repeat(RULE_WIDTH))
    println("60일 시나리오  ·  씨앗 $seed")
    println("=".repeat(RULE_WIDTH))
    println(
        cell("날짜", 12) + cell("요일", 5) + cell("판정", 5) + cell("완료", 7) +
            cell("연속", 5) + cell("△", 4) + cell("프리즈", 8) + cell("조각", 6) + "비고"
    )
    println("-".repeat(RULE_WIDTH))

    for (row in rows) {
        val completion = if (row.due > 0) "${row.done}/${row.due}" else "—"
        println(
            cell(row.date.toString(), 12) +
                cell(WEEKDAY_LABEL[row.date.dayOfWeek.value - 1].toString(), 5) +
                cell(markGlyph(row.mark), 5) +
                cell(completion, 7) +
                cell(row.overall.toString(), 5) +
                cell(row.partialsInWindow.toString(), 4) +
                cell(row.freeze.toString(), 8) +
                cell(row.shards.toString(), 6) +
                row.note
        )
    }

    println("-".repeat(RULE_WIDTH))
    val counts = Mark.entries.associateWith { m -> rows.count { it.mark == m } }
    println(
        "○ ${counts[Mark.FULL]}일 · △ ${counts[Mark.PARTIAL]}일 · " +
            "✕ ${counts[Mark.NONE]}일 · 휴식 ${counts[Mark.REST]}일"
    )
    println("전체 연속: 현재 ${account.overall}일 / 최장 ${account.longest}일")
    for (subject in subjects) {
        val tag = if (subject.required) "필수" else "선택"
        println(
            "  ${subject.name}($tag) 현재 ${subject.streak}일 / 최장 ${subject.longest}일 " +
                "/ 과목프리즈 ${subject.freeze}"
        )
    }
    println("=".repeat(RULE_WIDTH))
}
