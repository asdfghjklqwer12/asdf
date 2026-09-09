package com.studystreak.domain.streak

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 과목 하나 = 플랜 하나 (4장). 기간 플랜이면 요일과 하루 태스크 수가 고정이고,
 * 당일 계획 과목이면 [daily] 에 그날그날 넣은 태스크 수가 들어간다 (6.3).
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
    var streak: Int = 0,
    var longest: Int = 0,
    var freeze: Int = 0,
) {
    fun tasksOn(date: LocalDate): Int =
        if (sameDay) daily[date] ?: 0 else tasks

    fun studiesOn(date: LocalDate): Boolean {
        if (sameDay) {
            // 계획을 안 넣은 날은 학습일이 아니다 (휴식).
            // 안 그러면 계획을 안 짠 날마다 태스크 0개짜리 학습일이 되어 자동으로 ✕가 찍힌다.
            return tasksOn(date) > 0
        }
        return date.dayOfWeek in weekdays
    }
}
