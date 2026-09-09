package com.studystreak.domain.streak

import java.time.Instant
import java.time.LocalDate

/** 그날의 전체 판정 (5.2) */
enum class Mark { FULL, PARTIAL, NONE, REST }

/**
 * 8장의 OverallLog. [closedAt] 이 찍혀 있으면 이미 마감된 날이라 다시 정산하지 않는다 (10장 멱등성).
 * [consumedAt] 은 초기화에 쓰여 소진된 △ 표시.
 */
class DayLog(
    val date: LocalDate,
    val mark: Mark,
    /** 그날 학습일인 필수 과목 수 */
    val due: Int,
    /** 그중 실제로 완료한 과목 수 (과목 프리즈로 방어만 된 건 미완료로 침) */
    val done: Int,
    /** 그 과목들의 태스크 중 체크한 개수 */
    val tasksChecked: Int,
    /** 그 과목들의 태스크 총 개수 */
    val tasksTotal: Int,
    val closedAt: Instant,
    var consumedAt: Instant? = null,
) {
    /** 초기화에 쓰인 △인가 */
    val consumed: Boolean get() = consumedAt != null
}
