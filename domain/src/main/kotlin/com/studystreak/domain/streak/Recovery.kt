package com.studystreak.domain.streak

import java.time.LocalDate

/**
 * 48시간 복구 (기획 5.5) — 끊긴 streak을 한 번 무를 기회.
 *
 * 끊긴 날의 **밀린 하루치**를 다음 이틀 안에 다 하면 숫자가 돌아온다. 달력 월 1회.
 *
 * 기존 규칙과 같은 모양이다 — `SettleEvent.FreezeDefended`("✕를 전체 프리즈로 막은 날은
 * 그 자리에 멈춘다")처럼, 복구된 날도 **+1이 아니라 그 자리에 멈춘 것**이 된다.
 */

/**
 * 되돌릴 수 있는 끊김 하나. 정산이 초기화를 낼 때마다 새로 덮어쓴다 — 되돌릴 수 있는 건
 * 언제나 **가장 최근 끊김**이다.
 */
class Break(
    /** 끊긴 날 */
    val date: LocalDate,
    /** 그날 초기화되기 **직전**의 전체 streak. 복구하면 이 값이 되살아난다 */
    val overallBefore: Int,
    /** 무엇이 끊었나 — △ 3개인가 ✕인가 */
    val cause: SettleEvent,
    /** 그날 필수 과목의 배정량 {과목명: 개수} */
    val required: Map<String, Int>,
    /** 그중 그날 실제로 체크한 수 */
    val done: Map<String, Int>,
    /** 그날 같이 끊긴 과목 streak {과목명: 끊기기 직전 값} */
    val subjectStreaksBefore: Map<String, Int>,
) {
    /** 복구하려면 아직 해야 하는 몫 {과목명: 남은 개수} */
    val remaining: Map<String, Int> =
        required.mapValues { (name, need) -> (need - (done[name] ?: 0)).coerceAtLeast(0) }
            .filterValues { it > 0 }

    /** 복구를 쓸 수 있는 마지막 날 */
    val deadline: LocalDate = date.plusDays(StreakRules.RECOVERY_WINDOW_DAYS.toLong())
}

/** 왜 복구를 못 하는가 */
enum class RecoveryDenial {
    /** 되돌릴 끊김이 없다 */
    NO_BREAK,

    /** 끊기기 전 streak이 0이라 되돌려도 달라지는 게 없다 */
    NOTHING_TO_RESTORE,

    /** 아직 끊긴 날이 마감되기 전이다 (복구는 다음 날부터) */
    TOO_EARLY,

    /** 48시간이 지났다 */
    EXPIRED,

    /** 이번 달에 이미 썼다 */
    USED_THIS_MONTH,

    /** 밀린 하루치를 아직 다 못 했다 */
    NOT_FINISHED,
}

/** 지금 복구할 수 있는가 (`StreakEngine.recoveryOffer`). */
sealed interface RecoveryOffer {
    /**
     * 쓸 수 있다. [remaining] 을 다 하면 전체 streak이 [restoresTo] 로 돌아간다.
     *
     * [alreadyCarried] 는 **이월이 켜져 있어 밀린 몫이 이미 다음 학습일 배정에 들어간** 과목이다.
     * 그 과목은 다음 날을 끝내는 것으로 이미 밀린 몫을 한 것이므로, 앱이 같은 일을 두 번
     * 시키지 않게 이 목록을 보고 카드를 그려야 한다.
     */
    data class Available(
        val brokenOn: LocalDate,
        val deadline: LocalDate,
        val restoresTo: Int,
        val remaining: Map<String, Int>,
        val alreadyCarried: Set<String>,
    ) : RecoveryOffer

    data class Unavailable(val reason: RecoveryDenial) : RecoveryOffer
}

/** 복구 시도의 결과 (`StreakEngine.recover`). */
sealed interface RecoveryResult {
    /** 돌아왔다. [overall] 은 복구 뒤의 전체 streak, [subjects] 는 같이 돌아온 과목 streak. */
    data class Recovered(
        val brokenOn: LocalDate,
        val overall: Int,
        val subjects: Map<String, Int>,
    ) : RecoveryResult

    data class Refused(val reason: RecoveryDenial) : RecoveryResult
}
