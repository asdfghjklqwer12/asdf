package com.studystreak.domain

import com.studystreak.domain.plan.Allocation
import com.studystreak.domain.streak.Subject
import java.time.LocalDate

/**
 * 분량 계획을 체크리스트로 바꾼다 — 도메인의 두 반쪽을 잇는 다리.
 *
 * `allocate()` 는 "9월 9일은 7페이지" 까지만 낸다. 화면에 그리려면 **체크박스 몇 줄**인지가
 * 있어야 하고, 판정도 페이지가 아니라 **체크한 줄 수**로 한다(기획 5.2의 30% 기준).
 *
 * 하루치는 **3줄**로 나눈다. 재어서 정한 값이다(`docs/측정-결과.md` 14절).
 *
 * 줄 수는 ○ 비율을 바꾸지 않는다 — 7페이지를 다 읽어야 ○인 건 몇 줄로 나누든 같다.
 * 줄 수가 정하는 건 **✕를 면하려면 몇 페이지를 읽어야 하나** 하나뿐이다.
 * 30% 기준이 줄 단위로 올림되기 때문이다.
 *
 * ```
 * 1줄  7        1줄 필요  →  7p (100%)   △가 아예 안 나온다
 * 2줄  4+3      1줄 필요  →  4p ( 57%)   3페이지를 읽어도 ✕다
 * 3줄  3+2+2    1줄 필요  →  3p ( 43%)   ← 기준 30% 에 가장 가깝다
 * 4줄  2+2+2+1  2줄 필요  →  4p ( 57%)
 * 7줄  1×7      3줄 필요  →  3p ( 43%)   3줄과 같지만 화면이 복잡하다
 * ```
 */

/** 기본 분할 — 하루치를 몇 줄로 나누나 */
const val DEFAULT_TASKS_PER_DAY = 3

/** 체크리스트의 한 줄. [from]~[to] 는 1부터 세는 누적 위치다 (페이지·문제·강). */
data class PlanTask(
    val date: LocalDate,
    /** 그날 몇 번째 줄인가 (0부터) */
    val order: Int,
    val from: Int,
    val to: Int,
) {
    val amount: Int get() = to - from + 1
}

/** 하루치. 태스크가 0개면 휴식일이다 (기획 6.2 수정 ②). */
data class PlanDay(
    val date: LocalDate,
    val amount: Int,
    val tasks: List<PlanTask>,
) {
    val isRestDay: Boolean get() = tasks.isEmpty()
}

/**
 * 분량 계획을 날짜별 체크리스트로 바꾼다.
 *
 * 하루치를 [tasksPerDay] 줄로 나누되, 나머지는 **앞줄이 갖는다** — 7페이지면 3p + 2p + 2p 다.
 * 분량이 줄 수보다 적으면 그만큼만 만든다(1페이지짜리 날은 한 줄).
 * 분량이 0인 날(버퍼일)은 태스크가 없어 휴식일이 된다.
 *
 * 위치는 플랜 전체에 걸쳐 이어진다 — 1~3p, 4~5p, 6~7p, 8~10p, …
 */
fun Allocation.toChecklist(tasksPerDay: Int = DEFAULT_TASKS_PER_DAY): List<PlanDay> {
    require(tasksPerDay >= 1) { "하루치는 최소 한 줄이어야 한다 — 받은 값: $tasksPerDay" }

    var cursor = 1
    return days.map { date ->
        val amount = plan.getValue(date)
        val count = minOf(tasksPerDay, amount)
        val tasks = if (count <= 0) emptyList() else {
            val base = amount / count
            val extra = amount % count // 앞에서부터 한 칸씩 더 갖는다
            (0 until count).map { order ->
                val size = base + if (order < extra) 1 else 0
                PlanTask(date, order, cursor, cursor + size - 1).also { cursor += size }
            }
        }
        PlanDay(date, amount, tasks)
    }
}

/**
 * 체크리스트를 streak 엔진이 쓰는 과목으로 바꾼다.
 *
 * 날짜마다 줄 수가 다르므로(버퍼일은 0줄, 나머지는 보통 3줄) 날짜별 태스크 수를 그대로 넘긴다.
 * 그러면 버퍼일이 저절로 휴식일이 되어 자동 ✕가 안 찍힌다(기획 6.2 수정 ②).
 */
fun List<PlanDay>.toSubject(
    name: String,
    required: Boolean,
    carryOver: Boolean = false,
    maxDaysWorth: Int? = 2,
): Subject = Subject(
    name = name,
    required = required,
    // 날짜별로 태스크 수가 정해지는 과목이라는 뜻이다. 당일 계획(6.3)과 같은 구조를 쓴다
    sameDay = true,
    daily = filter { it.tasks.isNotEmpty() }.associate { it.date to it.tasks.size },
    carryOver = carryOver,
    maxDaysWorth = maxDaysWorth,
)
