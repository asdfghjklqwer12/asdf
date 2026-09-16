package com.studystreak.domain

import com.studystreak.domain.plan.Allocation
import com.studystreak.domain.plan.Redistribution
import com.studystreak.domain.plan.redistribute
import com.studystreak.domain.streak.Subject
import java.time.LocalDate

/**
 * 밀린 날 사용자에게 주는 두 갈래.
 *
 * △가 뜬 날 정산이 [com.studystreak.domain.streak.SettleResult.carriedOver] 로 신호를 주면
 * 앱이 카드를 띄우고, 사용자가 둘 중 하나를 고른다.
 *
 * ```
 *   수학 2개를 못 했어요
 *   [ 계획 조정 ]            남은 기간에 나눠 담기
 *   [ 내일 같이 하기 ]       내일 하루에 몰아서
 * ```
 *
 * 둘 다 진도를 지키지만 값이 다르다 — 앞은 남은 날들이 조금씩 무거워지고,
 * 뒤는 내일 하루만 무거워진다. 어느 쪽이 나은지는 사용자만 안다.
 */
sealed interface CatchUpChoice {
    /**
     * **계획 조정** — 밀린 몫을 남은 학습일에 다시 편다 (기획 6.2).
     *
     * 이월은 지워진다. 안 지우면 같은 몫을 재분배와 이월 양쪽에서 두 번 요구하게 된다.
     */
    data object Replan : CatchUpChoice

    /**
     * **내일 같이 하기** — 밀린 몫이 다음 학습일에 그대로 얹힌다.
     *
     * 계획은 안 건드린다. 이월은 이미 쌓여 있으므로 아무것도 할 게 없다.
     */
    data object DoTomorrow : CatchUpChoice
}

/**
 * 사용자가 고른 대로 적용한다.
 *
 * [CatchUpChoice.Replan] 이면 재분배 결과를 돌려주고 [subject] 의 이월을 지운다.
 * [CatchUpChoice.DoTomorrow] 면 아무것도 바꾸지 않고 `null` 을 돌려준다.
 *
 * **재분배와 이월 지우기를 따로 부르지 마라.** 둘은 한 쌍이다 — 재분배만 하고 이월을 안
 * 지우면 밀린 몫이 남은 날들에 펴지면서 내일에도 그대로 얹혀, 사용자가 같은 분량을
 * 두 번 요구받는다.
 *
 * @param doneAmount **시작부터의 누적** 완료 분량. 언제나 원본 [allocation] 기준으로 센다
 *   (`redistribute` KDoc 참고)
 */
fun applyCatchUp(
    choice: CatchUpChoice,
    allocation: Allocation,
    subject: Subject,
    doneThrough: LocalDate,
    doneAmount: Int,
    dailyMax: Int? = null,
): Redistribution? = when (choice) {
    CatchUpChoice.DoTomorrow -> null
    CatchUpChoice.Replan -> redistribute(allocation, doneThrough, doneAmount, dailyMax)
        .also { subject.clearCarryOver() }
}
