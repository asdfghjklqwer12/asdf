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
    /**
     * 이 정산으로 다음 학습일에 넘어간 태스크 — {과목명: 이월 수}. 비어 있으면 넘어간 게 없다.
     *
     * 사용자에게 "수학 2개가 다음 학습일로 넘어갑니다. 계획을 다시 짜시겠어요?" 를 물을 때 쓴다.
     * 그 자리에서 답을 안 해도 쌓인 몫은 `Subject.carriedTasks` 에 남아 있으므로,
     * 나중에 다시 물어보려면 그쪽을 읽으면 된다. 재정산([alreadySettled])일 때는 비어 있다.
     */
    val carriedOver: Map<String, Int> = emptyMap(),
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
        val carriedOver = mutableMapOf<String, Int>()

        // 이월분을 포함한 그날 배정량을 **먼저 확정한다.**
        // 1단계가 carriedTasks 를 갱신하므로, 그 뒤에 tasksOn 을 다시 부르면
        // 방금 다음 날로 넘긴 몫까지 오늘 분량으로 세게 된다.
        val requiredToday: Map<Subject, Int> = subjects.associateWith { it.tasksOn(date) }

        // 1단계 — 과목별 (과목 완료 = 그날 태스크 전부 체크, 5.1)
        for (subject in subjects) {
            if (!subject.studiesOn(date)) continue
            val required = requiredToday.getValue(subject) // 이월분을 포함한 오늘 몫
            val done = doneTasks[subject.name] ?: 0
            // 밀린 것까지 다 해야 완료다
            if (done >= required) {
                subject.streak += 1
                subject.longest = maxOf(subject.longest, subject.streak)
                if (subject.streak % 7 == 0 && subject.freeze < SUBJECT_FREEZE_MAX) {
                    subject.freeze += 1
                }
                subject.carriedTasks = 0 // 밀린 것까지 다 했으니 이월이 없다
                reallyDone += subject.name
            } else {
                if (subject.freeze > 0) {
                    subject.freeze -= 1 // 과목 프리즈가 과목 streak만 지켜준다
                } else {
                    subject.streak = 0
                }
                // 과목 프리즈가 streak 을 지켜줘도 진도는 안 나갔으므로 이월은 그대로 쌓인다
                val carry = subject.carryAfter(date, required, done)
                subject.carriedTasks = carry
                if (carry > 0) carriedOver[subject.name] = carry
                // 전체 판정에서는 완료로 치지 않는다 → reallyDone 에 넣지 않음
            }
        }

        // 2단계 — 전체 (△/✕는 태스크 단위로 가른다)
        val due = subjects.filter { it.required && it.studiesOn(date) }
        val done = due.filter { it.name in reallyDone }
        val checked = due.sumOf { minOf(doneTasks[it.name] ?: 0, requiredToday.getValue(it)) }
        val total = due.sumOf { requiredToday.getValue(it) }
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
        return SettleResult(log, event, alreadySettled = false, carriedOver = carriedOver)
    }
}
