package com.studystreak.domain.streak

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 과목 하나 = 플랜 하나 (4장). 기간 플랜이면 요일과 하루 태스크 수가 고정이고,
 * 당일 계획 과목이면 [daily] 에 그날그날 넣은 태스크 수가 들어간다 (6.3).
 *
 * ## 이월 (carry-over)
 *
 * [carryOver] 를 켜면 그날 못 한 태스크가 **다음 학습일로 넘어간다.** 넘어간 만큼
 * 다음 날 배정량이 늘어나고, 그걸 **전부** 체크해야 그 과목이 완료다.
 * 사용자가 계획을 다시 짜기로 하면 [clearCarryOver] 로 쌓인 몫을 지운다
 * (기간 플랜이면 `redistribute` 와 짝으로 쓴다).
 *
 * 기본값은 꺼짐이다. 이월은 나중에 얹은 규칙이라, 켜지 않으면 파이썬에서 옮겨온
 * 원래 판정 그대로 돈다.
 */
class Subject(
    val name: String,
    val required: Boolean,
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** 그날 배정되는 태스크 수 (기간 플랜) */
    val tasks: Int = 1,
    /** 당일 계획 과목인가 (그날그날 태스크를 직접 넣는다) */
    val sameDay: Boolean = false,
    /** 당일 계획: {날짜: 그날 넣은 태스크 수} */
    val daily: Map<LocalDate, Int> = emptyMap(),
    /** 못 한 태스크를 다음 학습일로 넘길 것인가 */
    val carryOver: Boolean = false,
    /**
     * **하루에 최대 며칠치까지** 나올 수 있나. `null` 이면 제한 없음.
     *
     * 기본값 2 = "밀려도 하루에 이틀치까지". 사용자가 화면에서 보는 숫자 그대로다 —
     * 하루 3개짜리 플랜이면 아무리 밀려도 하루에 6개를 넘지 않는다.
     *
     * 하루치는 원래 몫이므로 이월로 넘어오는 건 `(maxDaysWorth - 1)` 일치까지다.
     * 1이면 이월이 아예 없는 것과 같다.
     *
     * 제한을 풀면 못 할수록 다음 날이 무거워지고, 무거워질수록 또 못 하는 눈덩이가 된다 —
     * 밀린 걸 따라잡을 여력이 없는 사용자에게는 ✕가 96%까지 올라가고 streak이 아예 안 돈다
     * (`docs/측정-결과.md` 10절).
     *
     * **넘친 몫은 계획에서 사라진다.** 진도를 맞추는 건 이월이 아니라 재분배의 일이다
     * (기획 6.2). 사라진 몫은 [com.studystreak.domain.streak.SettleResult.carryDropped] 로
     * 알려주므로, 그때 재분배를 권하면 된다.
     */
    val maxDaysWorth: Int? = 2,
    var streak: Int = 0,
    var longest: Int = 0,
    var freeze: Int = 0,
    /** 다음 학습일로 넘어간 태스크 수. [carryOver] 가 꺼져 있으면 늘 0이다. */
    var carriedTasks: Int = 0,
) {
    init {
        require(maxDaysWorth == null || maxDaysWorth >= 1) {
            "maxDaysWorth 는 최소 1(하루치)이어야 한다 — 받은 값: $maxDaysWorth"
        }
    }

    /** 이월을 빼고, 그날 원래 배정된 태스크 수 */
    fun baseTasksOn(date: LocalDate): Int =
        if (sameDay) daily[date] ?: 0 else tasks

    /**
     * 그날 실제로 체크해야 하는 태스크 수 — **이월분을 포함한다.**
     *
     * 이월은 학습일에만 얹는다. 휴식일은 기획 5.4대로 중립이라, 밀린 몫은 건너뛰고
     * 다음 학습일로 간다.
     */
    fun tasksOn(date: LocalDate): Int {
        val base = baseTasksOn(date)
        if (!carryOver || !studiesOn(date)) return base
        return base + carriedTasks
    }

    fun studiesOn(date: LocalDate): Boolean {
        if (sameDay) {
            // 계획을 안 넣은 날은 학습일이 아니다 (휴식).
            // 안 그러면 계획을 안 짠 날마다 태스크 0개짜리 학습일이 되어 자동으로 ✕가 찍힌다.
            return baseTasksOn(date) > 0
        }
        return date.dayOfWeek in weekdays
    }

    /**
     * 사용자가 계획을 다시 짜기로 했을 때 쌓인 이월을 지운다.
     *
     * 기간 플랜이면 `redistribute` 로 남은 학습일에 다시 펼친 뒤 이걸 부른다 —
     * 안 그러면 밀린 몫을 이월과 재분배 양쪽에서 두 번 요구하게 된다.
     */
    fun clearCarryOver() {
        carriedTasks = 0
    }

    /** 이월 계산 결과 */
    internal class Carry(
        /** 다음 학습일로 넘길 태스크 수 */
        val carried: Int,
        /** 상한에 걸려 계획에서 사라진 태스크 수 */
        val dropped: Int,
    )

    /**
     * [date] 의 배정량이 [required] 일 때 [done] 개를 체크했다면 얼마가 넘어가고 얼마가 사라지나.
     *
     * [required] 를 인자로 받는 이유: 정산 도중에는 [carriedTasks] 가 갱신되는 중이라
     * [tasksOn] 을 다시 부르면 값이 흔들린다. 부르는 쪽이 갱신 전에 확정한 값을 넘긴다.
     */
    internal fun carryAfter(date: LocalDate, required: Int, done: Int): Carry {
        if (!carryOver) return Carry(0, 0)
        val left = (required - done.coerceAtLeast(0)).coerceAtLeast(0)
        val limit = maxDaysWorth ?: return Carry(left, 0)
        // 하루가 limit 일치를 넘지 않게. 그중 하루치는 원래 몫이니 이월은 (limit - 1)일치까지다
        val carried = minOf(left, (limit - 1) * baseTasksOn(date))
        return Carry(carried, left - carried)
    }
}
