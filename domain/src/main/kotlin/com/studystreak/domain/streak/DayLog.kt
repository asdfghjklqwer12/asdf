package com.studystreak.domain.streak

import java.time.Instant
import java.time.LocalDate

/** 그날의 전체 판정 (5.2) */
enum class Mark { FULL, PARTIAL, NONE, REST }

/**
 * 8장의 OverallLog. [closedAt] 이 찍혀 있으면 이미 마감된 날이라 다시 정산하지 않는다 (10장 멱등성).
 * [consumedAt] 은 초기화에 쓰여 소진된 △ 표시.
 * [recoveredAt] 은 48시간 복구(5.5)로 무른 날 표시 — **[mark] 는 안 바뀐다.**
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
    /**
     * 48시간 복구로 이 날의 초기화를 물린 시각 (5.5).
     *
     * [mark] 와 [tasksChecked] 는 **그대로 둔다.** 그날 실제로 한 건 그만큼이고, 밀린 몫은
     * 나중에 한 것이기 때문이다. 기록 화면은 ✕/△ 위에 복구 도장을 얹어 그리면 된다.
     */
    var recoveredAt: Instant? = null,
) {
    /** 초기화에 쓰인 △인가 */
    val consumed: Boolean get() = consumedAt != null

    /** 48시간 복구로 물린 날인가 */
    val recovered: Boolean get() = recoveredAt != null
}
