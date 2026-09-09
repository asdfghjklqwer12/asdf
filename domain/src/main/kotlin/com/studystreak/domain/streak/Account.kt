package com.studystreak.domain.streak

import com.studystreak.domain.streak.StreakRules.AD_VIEWS_PER_DAY
import com.studystreak.domain.streak.StreakRules.AD_VIEWS_PER_SHARD
import com.studystreak.domain.streak.StreakRules.OVERALL_FREEZE_MAX
import com.studystreak.domain.streak.StreakRules.REST_SHARDS_PER_WEEK
import com.studystreak.domain.streak.StreakRules.SHARDS_PER_FREEZE
import com.studystreak.domain.streak.StreakRules.SHARD_CAP
import com.studystreak.domain.streak.StreakRules.WINDOW_DAYS
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** 조각을 얻은 경로 (8장 ShardLog.source) */
enum class ShardSource { REST_CHECK, AD }

data class ShardEvent(val date: LocalDate, val source: ShardSource)

/** 조각 지급 요청의 결과. [rejected] 가 true 면 아무것도 안 바뀐 것이다. */
sealed interface ShardResult {
    val rejected: Boolean get() = this is Rejected

    /** 조각 1개 지급 */
    data object ShardGranted : ShardResult

    /** 조각 1개 지급 → 6개가 모여 프리즈 1개로 바뀜 */
    data object FreezeEarned : ShardResult

    /** 광고를 봤지만 아직 조각이 안 됨 (2회 모여야 1개) */
    data class AdProgressed(val progress: Int, val needed: Int = AD_VIEWS_PER_SHARD) : ShardResult

    data class Rejected(val reason: Reason) : ShardResult {
        enum class Reason {
            /** 프리즈 1개 + 조각 5개로 가득 참 */
            FULL,

            /** 휴식일 조각 주 3개 상한 */
            WEEKLY_REST_LIMIT,

            /** 광고 하루 2회 상한 */
            DAILY_AD_LIMIT,
        }
    }
}

/**
 * 8장의 OverallStreak + 조각/광고 이력. 계정 하나에 하나다.
 */
class Account(
    var overall: Int = 0,
    var longest: Int = 0,
    var freeze: Int = 0,
    var shards: Int = 0,
    /** 조각까지 남은 광고 진행도 (0~1) */
    var adProgress: Int = 0,
) {
    val logs: MutableList<DayLog> = mutableListOf()

    /** 조각 지급 이력 — 휴식일 주 3개 제한을 검증하려면 남아 있어야 한다 */
    val shardEvents: MutableList<ShardEvent> = mutableListOf()

    /** 광고 시청 이력 — 하루 2회 제한 검증용 */
    val adViews: MutableList<LocalDate> = mutableListOf()

    var lastResetDate: LocalDate? = null
    var freezeUsedAt: LocalDate? = null

    // ---------- 조각 ----------

    private fun shardsThisWeek(date: LocalDate, source: ShardSource): Int {
        val monday = date.minusDays((date.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong())
        return shardEvents.count { it.source == source && it.date >= monday && it.date <= date }
    }

    private fun grantShard(date: LocalDate, source: ShardSource): ShardResult {
        // 프리즈를 이미 들고 있으면 조각은 5개에서 멈춘다.
        // 상한을 안 두면 조각을 미리 잔뜩 사두고 프리즈를 쓰는 족족 채울 수 있어서
        // "보유 상한 1개"라는 안전장치가 재충전 속도 쪽에서 뚫린다.
        if (freeze >= OVERALL_FREEZE_MAX && shards >= SHARD_CAP) {
            return ShardResult.Rejected(ShardResult.Rejected.Reason.FULL)
        }
        shardEvents += ShardEvent(date, source)
        shards += 1
        if (shards >= SHARDS_PER_FREEZE && freeze < OVERALL_FREEZE_MAX) {
            shards -= SHARDS_PER_FREEZE
            freeze += 1
            return ShardResult.FreezeEarned
        }
        return ShardResult.ShardGranted
    }

    /** 휴식일에 자발적으로 체크 → 조각 1개 (주 3개까지). */
    fun restCheck(date: LocalDate): ShardResult {
        if (shardsThisWeek(date, ShardSource.REST_CHECK) >= REST_SHARDS_PER_WEEK) {
            return ShardResult.Rejected(ShardResult.Rejected.Reason.WEEKLY_REST_LIMIT)
        }
        return grantShard(date, ShardSource.REST_CHECK)
    }

    /** 보상형 광고 1회 시청. 2회 모여야 조각 1개 (B안), 하루 2회까지. */
    fun watchAd(date: LocalDate): ShardResult {
        if (adViews.count { it == date } >= AD_VIEWS_PER_DAY) {
            return ShardResult.Rejected(ShardResult.Rejected.Reason.DAILY_AD_LIMIT)
        }
        adViews += date
        adProgress += 1
        if (adProgress >= AD_VIEWS_PER_SHARD) {
            adProgress = 0
            return grantShard(date, ShardSource.AD)
        }
        return ShardResult.AdProgressed(adProgress)
    }

    /**
     * 초기화가 일어나면 창 안의 △를 소진 처리한다.
     * 안 하면 초기화 직후에도 창에 △가 그대로 남아 새 streak이 곧바로 또 죽는다.
     */
    fun consumeRecentPartials(date: LocalDate, now: Instant) {
        val lo = date.minusDays((WINDOW_DAYS - 1).toLong())
        logs.filter { it.mark == Mark.PARTIAL && it.date >= lo && it.date <= date && !it.consumed }
            .forEach { it.consumedAt = now }
    }
}
