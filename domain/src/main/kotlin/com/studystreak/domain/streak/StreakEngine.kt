package com.studystreak.domain.streak

import com.studystreak.domain.streak.StreakRules.DEFAULT_DAY_CUTOFF_HOUR
import com.studystreak.domain.streak.StreakRules.PARTIAL_MIN_RATIO
import com.studystreak.domain.streak.StreakRules.PARTIAL_RESET_AT
import com.studystreak.domain.streak.StreakRules.SUBJECT_FREEZE_MAX
import com.studystreak.domain.streak.StreakRules.WINDOW_DAYS
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 정산 중에 일어난 사건. 없으면 null. */
sealed interface SettleEvent {
    /** 창 안의 △가 3개가 되어 초기화됨 */
    data class PartialReset(val partialCount: Int) : SettleEvent

    /** ✕를 전체 프리즈로 방어 — 숫자가 그 자리에 멈춘다 (+1 아님) */
    data object FreezeDefended : SettleEvent

    /** 프리즈가 없어 ✕로 초기화됨 */
    data object ResetWithoutFreeze : SettleEvent
}

data class SettleResult(
    val log: DayLog,
    val event: SettleEvent?,
    /** 이미 마감된 날짜라 아무것도 바꾸지 않았다 */
    val alreadySettled: Boolean,
)

/**
 * 기획 노트 9장의 정산 로직.
 *
 * 모든 시각 판정은 주입받은 [clock] 으로만 한다 — 나중에 서버 시각으로 갈아끼우기 위해서다.
 * 여기서 `LocalDate.now()` 를 직접 부르지 않는다.
 */
class StreakEngine(
    private val clock: Clock,
    /** 하루 마감 시각. 사용자 설정, 기본 새벽 3시 (5.3) */
    private val dayCutoffHour: Int = DEFAULT_DAY_CUTOFF_HOUR,
) {
    /**
     * 지금 이 순간이 어느 "학습일"에 속하는가. 마감 시각 전이면 아직 전날이다 (5.3).
     *
     * [zone] 은 **계정에 저장된** 타임존이다. 기기 타임존을 따라가면 시계 조작 방지가 무의미해지고
     * 하루 경계가 움직여 streak이 이상하게 끊긴다.
     */
    fun currentStudyDate(zone: ZoneId): LocalDate {
        val now = ZonedDateTime.ofInstant(clock.instant(), zone)
        return if (now.hour < dayCutoffHour) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    /**
     * 하루 마감. [doneTasks] = {과목명: 그날 체크한 태스크 수}.
     *
     * 멱등하다 — 이미 마감된 날짜를 다시 넣으면 아무것도 바꾸지 않고 기존 로그를 돌려준다 (10장).
     */
    fun settle(
        account: Account,
        subjects: List<Subject>,
        date: LocalDate,
        doneTasks: Map<String, Int>,
    ): SettleResult {
        account.logs.firstOrNull { it.date == date }?.let {
            return SettleResult(it, event = null, alreadySettled = true)
        }

        val now = clock.instant()
        val reallyDone = mutableSetOf<String>()

        // 1단계 — 과목별 (과목 완료 = 그날 태스크 전부 체크, 5.1)
        for (subject in subjects) {
            if (!subject.studiesOn(date)) continue
            if ((doneTasks[subject.name] ?: 0) >= subject.tasksOn(date)) {
                subject.streak += 1
                subject.longest = maxOf(subject.longest, subject.streak)
                if (subject.streak % 7 == 0 && subject.freeze < SUBJECT_FREEZE_MAX) {
                    subject.freeze += 1
                }
                reallyDone += subject.name
            } else {
                if (subject.freeze > 0) {
                    subject.freeze -= 1 // 과목 프리즈가 과목 streak만 지켜준다
                } else {
                    subject.streak = 0
                }
                // 전체 판정에서는 완료로 치지 않는다 → reallyDone 에 넣지 않음
            }
        }

        // 2단계 — 전체 (△/✕는 태스크 단위로 가른다)
        val due = subjects.filter { it.required && it.studiesOn(date) }
        val done = due.filter { it.name in reallyDone }
        val checked = due.sumOf { minOf(doneTasks[it.name] ?: 0, it.tasksOn(date)) }
        val total = due.sumOf { it.tasksOn(date) }
        val ratio = if (total > 0) checked.toDouble() / total else 0.0

        val mark: Mark
        if (due.isEmpty()) {
            mark = Mark.REST
        } else if (done.size == due.size) {
            mark = Mark.FULL
            account.overall += 1
        } else if (ratio >= PARTIAL_MIN_RATIO) { // 그날 태스크의 30% 이상 했으면 △
            mark = Mark.PARTIAL
            account.overall += 1
        } else { // 30%에 못 미치면 ✕ (0%도 포함)
            mark = Mark.NONE
        }

        val log = DayLog(
            date = date,
            mark = mark,
            due = due.size,
            done = done.size,
            tasksChecked = checked,
            tasksTotal = total,
            closedAt = now,
        )
        account.logs += log

        var event: SettleEvent? = null

        // △ 트랙
        if (mark == Mark.PARTIAL) {
            val lo = date.minusDays((WINDOW_DAYS - 1).toLong())
            val recent = account.logs.filter {
                it.mark == Mark.PARTIAL && it.date >= lo && it.date <= date && !it.consumed
            }
            if (recent.size >= PARTIAL_RESET_AT) {
                account.overall = 0
                account.lastResetDate = date
                account.consumeRecentPartials(date, now)
                event = SettleEvent.PartialReset(recent.size)
            }
        }

        // ✕ 트랙 (△와 독립)
        if (mark == Mark.NONE) {
            if (account.freeze > 0) {
                account.freeze -= 1
                account.freezeUsedAt = date
                event = SettleEvent.FreezeDefended
                // overall 그대로 유지 — +1도 아니고 0도 아님
            } else {
                account.overall = 0
                account.lastResetDate = date
                account.consumeRecentPartials(date, now) // △발 초기화와 똑같이 창을 비워준다
                event = SettleEvent.ResetWithoutFreeze
            }
        }

        account.longest = maxOf(account.longest, account.overall)
        return SettleResult(log, event, alreadySettled = false)
    }
}
