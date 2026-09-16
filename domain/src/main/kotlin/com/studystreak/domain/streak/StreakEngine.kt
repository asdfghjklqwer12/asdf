package com.studystreak.domain.streak

import com.studystreak.domain.streak.StreakRules.DEFAULT_DAY_CUTOFF_HOUR
import com.studystreak.domain.streak.StreakRules.FREEZE_BLOCKS_PARTIAL_RESET
import com.studystreak.domain.streak.StreakRules.PARTIAL_MIN_RATIO
import com.studystreak.domain.streak.StreakRules.PARTIAL_RESET_AT
import com.studystreak.domain.streak.StreakRules.RECOVERIES_PER_MONTH
import com.studystreak.domain.streak.StreakRules.RECOVERY_WINDOW_DAYS
import com.studystreak.domain.streak.StreakRules.SUBJECT_FREEZE_MAX
import com.studystreak.domain.streak.StreakRules.WINDOW_DAYS
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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

    /**
     * △ 3개 초기화를 전체 프리즈로 방어 — `StreakEngine.freezeBlocksPartialReset` 이 켜졌을 때만 난다.
     *
     * ✕ 방어와 같은 모양이다. △가 준 +1은 살아 있고, 창의 △도 안 비운다 —
     * 그래서 창이 빌 때까지 또 △를 내면 다시 위태로워진다.
     */
    data class FreezeDefendedPartial(val partialCount: Int) : SettleEvent
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
    /**
     * 이월 상한에 걸려 **계획에서 사라진** 태스크 — {과목명: 사라진 수}.
     *
     * 비어 있지 않다는 건 진도가 조용히 밀려나고 있다는 뜻이다. 이월은 "어제 못 한 걸 오늘"
     * 까지만 책임지고, 그 너머는 재분배(기획 6.2)가 맡는다. 여기에 뭔가 찍히면
     * "3일 밀렸어요, 이렇게 조정할까요?" 카드를 띄울 때다.
     */
    val carryDropped: Map<String, Int> = emptyMap(),
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
    /**
     * 전체 프리즈가 **△ 3개 초기화도** 막는가. 기본은 끔 — 기획 5.5는 ✕만 막게 했다.
     *
     * **재봤고 안 켜기로 했다 (A7).** 켜도 얻는 게 없다 — 프리즈는 하나뿐이라 △를 막으면
     * ✕를 못 막고, △ 방어는 창의 △를 안 비워서 다음 △ 하나에 또 위태로워진다.
     * 필수 3과목이면 켠 쪽이 오히려 미세하게 나쁘다 (9.9일 vs 10.0일).
     * 광고까지 같이 켜면 191.8일이 되어 규칙이 사실상 없어진다
     * (`docs/측정-결과.md` 17절). **켜지 마라.** 재현하려면 `--args="a7"`.
     */
    private val freezeBlocksPartialReset: Boolean = FREEZE_BLOCKS_PARTIAL_RESET,
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
        val streakBeforeReset = mutableMapOf<String, Int>() // 48시간 복구가 되돌릴 값 (5.5)
        val carriedOver = mutableMapOf<String, Int>()
        val carryDropped = mutableMapOf<String, Int>()

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
                    if (subject.streak > 0) streakBeforeReset[subject.name] = subject.streak
                    subject.streak = 0
                }
                // 과목 프리즈가 streak 을 지켜줘도 진도는 안 나갔으므로 이월은 그대로 쌓인다
                val carry = subject.carryAfter(date, required, done)
                subject.carriedTasks = carry.carried
                if (carry.carried > 0) carriedOver[subject.name] = carry.carried
                if (carry.dropped > 0) carryDropped[subject.name] = carry.dropped
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

        // 48시간 복구(5.5)가 되돌릴 자리를 남긴다. 되돌릴 수 있는 건 가장 최근 끊김 하나다
        fun recordBreak(overallBefore: Int, cause: SettleEvent) {
            account.pendingBreak = Break(
                date = date,
                overallBefore = overallBefore,
                cause = cause,
                required = due.associate { it.name to requiredToday.getValue(it) },
                done = due.associate { it.name to minOf(doneTasks[it.name] ?: 0, requiredToday.getValue(it)) },
                subjectStreaksBefore = streakBeforeReset.filterKeys { name -> due.any { it.name == name } },
            )
        }

        // △ 트랙
        if (mark == Mark.PARTIAL) {
            val lo = date.minusDays((WINDOW_DAYS - 1).toLong())
            val recent = account.logs.filter {
                it.mark == Mark.PARTIAL && it.date >= lo && it.date <= date && !it.consumed
            }
            if (recent.size >= PARTIAL_RESET_AT && freezeBlocksPartialReset && account.freeze > 0) {
                account.freeze -= 1
                account.freezeUsedAt = date
                event = SettleEvent.FreezeDefendedPartial(recent.size)
                // overall 그대로 — △가 준 +1 은 살아 있다.
                // 창의 △도 안 비운다. ✕를 막았을 때와 같은 모양이다
            } else if (recent.size >= PARTIAL_RESET_AT) {
                val cause = SettleEvent.PartialReset(recent.size)
                recordBreak(account.overall, cause) // △는 이미 +1 된 값이다 — 복구는 초기화만 무른다
                account.overall = 0
                account.lastResetDate = date
                account.consumeRecentPartials(date, now)
                event = cause
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
                recordBreak(account.overall, SettleEvent.ResetWithoutFreeze) // ✕는 +1 이 없었다
                account.overall = 0
                account.lastResetDate = date
                account.consumeRecentPartials(date, now) // △발 초기화와 똑같이 창을 비워준다
                event = SettleEvent.ResetWithoutFreeze
            }
        }

        account.longest = maxOf(account.longest, account.overall)
        return SettleResult(log, event, alreadySettled = false,
            carriedOver = carriedOver, carryDropped = carryDropped)
    }

    // ---------- 48시간 복구 (5.5) ----------

    /**
     * 지금 끊긴 streak을 무를 수 있는가.
     *
     * 끊긴 날의 **밀린 하루치**를 다음 이틀 안에 다 하면 숫자가 돌아온다. 달력 월 1회.
     * [today] 는 [currentStudyDate] 로 뽑은 학습일이다.
     */
    fun recoveryOffer(account: Account, subjects: List<Subject>, today: LocalDate): RecoveryOffer {
        val b = account.pendingBreak ?: return RecoveryOffer.Unavailable(RecoveryDenial.NO_BREAK)
        if (b.overallBefore <= 0) return RecoveryOffer.Unavailable(RecoveryDenial.NOTHING_TO_RESTORE)
        if (!today.isAfter(b.date)) return RecoveryOffer.Unavailable(RecoveryDenial.TOO_EARLY)
        if (today.isAfter(b.deadline)) return RecoveryOffer.Unavailable(RecoveryDenial.EXPIRED)
        if (account.recoveriesIn(YearMonth.from(today)) >= RECOVERIES_PER_MONTH) {
            return RecoveryOffer.Unavailable(RecoveryDenial.USED_THIS_MONTH)
        }
        return RecoveryOffer.Available(
            brokenOn = b.date,
            deadline = b.deadline,
            restoresTo = account.overall + b.overallBefore,
            remaining = b.remaining,
            // 이월이 켜진 과목은 밀린 몫이 이미 다음 학습일 배정에 들어가 있다
            alreadyCarried = subjects
                .filter { it.carryOver && it.carriedTasks > 0 && it.name in b.remaining }
                .map { it.name }.toSet(),
        )
    }

    /**
     * 밀린 하루치를 다 했으니 끊김을 무른다. [makeUp] = {과목명: 밀린 몫 중 해낸 수}.
     *
     * **복구는 초기화만 무른다. 그날의 ○△✕ 는 안 바뀐다.**
     * 그래서 △로 끊긴 날은 △의 +1이 그대로 살아 있고, ✕로 끊긴 날은 +1 없이 그 자리에
     * 멈춘 것이 된다 — `SettleEvent.FreezeDefended` 와 같은 모양이다.
     *
     * 끊긴 뒤 오늘까지 올린 숫자는 안 날아간다. 끊기기 전 값을 **더하기** 때문이다.
     */
    fun recover(
        account: Account,
        subjects: List<Subject>,
        today: LocalDate,
        makeUp: Map<String, Int>,
    ): RecoveryResult {
        val offer = recoveryOffer(account, subjects, today)
        if (offer is RecoveryOffer.Unavailable) return RecoveryResult.Refused(offer.reason)
        val b = checkNotNull(account.pendingBreak)

        if (b.remaining.any { (name, need) -> (makeUp[name] ?: 0) < need }) {
            return RecoveryResult.Refused(RecoveryDenial.NOT_FINISHED)
        }

        account.overall += b.overallBefore
        account.longest = maxOf(account.longest, account.overall)

        val restored = mutableMapOf<String, Int>()
        for (subject in subjects) {
            val before = b.subjectStreaksBefore[subject.name] ?: continue
            subject.streak += before
            subject.longest = maxOf(subject.longest, subject.streak)
            restored[subject.name] = subject.streak
        }

        account.logs.firstOrNull { it.date == b.date }?.recoveredAt = clock.instant()
        account.recoveries += today
        account.pendingBreak = null
        return RecoveryResult.Recovered(b.date, account.overall, restored)
    }
}
