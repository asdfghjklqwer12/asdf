package com.studystreak.domain.streak

/**
 * 기획 노트 5장의 숫자들. 여기 값을 바꾸는 것 = 규칙을 바꾸는 것이므로 한곳에 모아둔다.
 */
object StreakRules {
    /** △를 세는 창 (오늘 포함 최근 7일) */
    const val WINDOW_DAYS = 7

    /** △가 이만큼 쌓이면 전체 초기화 */
    const val PARTIAL_RESET_AT = 3

    const val SHARDS_PER_FREEZE = 6

    /** 프리즈를 보유 중이면 조각은 5개까지만 (결제로도 못 넘김) */
    const val SHARD_CAP = SHARDS_PER_FREEZE - 1

    /** 광고 2회를 봐야 조각 1개 (B안) */
    const val AD_VIEWS_PER_SHARD = 2

    /** 광고는 하루 2회까지 → 하루 최대 조각 1개 */
    const val AD_VIEWS_PER_DAY = 2

    const val REST_SHARDS_PER_WEEK = 3

    const val SUBJECT_FREEZE_MAX = 2

    const val OVERALL_FREEZE_MAX = 1

    /** 그날 태스크의 이 비율 이상 해야 △, 못 미치면 ✕ */
    const val PARTIAL_MIN_RATIO = 0.30

    /** 하루 마감 시각 기본값 — 새벽 3시 (5.3) */
    const val DEFAULT_DAY_CUTOFF_HOUR = 3
}
