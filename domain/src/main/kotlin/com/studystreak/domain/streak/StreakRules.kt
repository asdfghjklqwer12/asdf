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

    /**
     * 48시간 복구를 쓸 수 있는 기간 — 끊긴 날의 **다음 이틀** (5.5).
     *
     * 기획서의 "48시간"을 실제 시각이 아니라 날짜로 센다. 실제 시각으로 재면 앱을 늦게
     * 여는 사용자가 이득을 본다 — 사흘 뒤에 열면 그때 끊김이 확정되고 거기서 48시간이
     * 시작되기 때문이다. 날짜로 세면 언제 열든 창이 같다.
     */
    const val RECOVERY_WINDOW_DAYS = 2

    /** 복구는 달력 월 기준 이만큼만 (5.5) */
    const val RECOVERIES_PER_MONTH = 1

    /**
     * 전체 프리즈가 △ 3개 초기화도 막는가. 기획 5.5는 ✕만 막게 했다.
     *
     * **재봤고 기각했다 (A7, `docs/측정-결과.md` 17절).** 켜도 얻는 게 없다. 켜지 마라.
     */
    const val FREEZE_BLOCKS_PARTIAL_RESET = false
}
