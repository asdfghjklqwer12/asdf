package com.studystreak.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.ceil

/** 밀림 흡수용 예비일 비율 (6.2) */
const val BUFFER_RATIO = 0.15

/** 저장하면 안 되는 입력 (6.2 "막아야 하는 입력") */
class PlanException(message: String) : Exception(message)

/** 사용자에게 보여줄 경고. 계산은 그대로 되지만 플랜이 지켜지기 어렵다는 신호다. */
sealed interface PlanWarning {
    val message: String

    /** 총분량이 배분일 수보다 적어 분량 0인 날이 잔뜩 생긴다 */
    data class TotalBelowAllocDays(
        val total: Int,
        val allocDays: Int,
        val studyDays: Int,
        val remainder: Int,
    ) : PlanWarning {
        override val message: String
            get() = "총분량($total)이 배분일($allocDays)보다 적다 — 실제로 공부하는 날은 ${remainder}일뿐이고 " +
                "나머지 ${studyDays - remainder}일은 휴식일이 된다"
    }

    /** 하루치가 사용자가 정한 하루 최대 분량을 넘는다 */
    data class DailyMaxExceeded(val peak: Int, val dailyMax: Int) : PlanWarning {
        override val message: String
            get() = "하루 분량 ${peak}이 상한 ${dailyMax}을 넘는다 — 목표일 연장이나 분량 축소가 필요하다"
    }

    /** 재분배할 남은 날이 아예 없다 */
    data object NoDaysLeftToRedistribute : PlanWarning {
        override val message: String get() = "남은 날이 없다 — 목표일을 늘리는 수밖에 없다"
    }

    /** 재분배 후 하루치가 상한을 넘는다 */
    data class RedistributedDailyMaxExceeded(val peak: Int, val dailyMax: Int) : PlanWarning {
        override val message: String
            get() = "재분배 후 하루 $peak — 상한 $dailyMax 초과, 목표일 연장 제안 필요"
    }
}

/**
 * [allocate] 의 결과. [plan] 은 학습 가능일 전체를 날짜 순으로 담으며 분량이 0인 날도 들어 있다.
 */
data class Allocation(
    val plan: Map<LocalDate, Int>,
    /** 기간 안의 학습 가능일 전체 */
    val days: List<LocalDate>,
    /** D — 학습 가능일 수 */
    val studyDayCount: Int,
    /** B — 버퍼일 수 */
    val bufferDayCount: Int,
    /** N — 배분일 수 */
    val allocDayCount: Int,
    /** 기본 하루치 */
    val base: Int,
    /** 나머지 — 앞쪽 이만큼의 날에 +1 */
    val remainder: Int,
    /** 하루 최대 분량 */
    val peak: Int,
    val warnings: List<PlanWarning>,
    /** 분량이 0인 날. 학습일이 아니라 휴식일이다 (수정 ②) */
    val restDays: List<LocalDate>,
    /** 일부러 비워둔 버퍼일 */
    val bufferDays: List<LocalDate>,
)

/** [redistribute] 의 결과. */
data class Redistribution(
    val plan: Map<LocalDate, Int>,
    /** 밀린 분량 */
    val behind: Int,
    /** 흡수에 쓴 버퍼일 수 */
    val usedBufferDays: Int,
    /** 남은 학습일에 하루씩 추가한 양 */
    val addedPerDay: Int,
    val warnings: List<PlanWarning>,
)

/** 기간 안에서 실제 학습일 날짜 목록. */
fun studyDays(
    start: LocalDate,
    end: LocalDate,
    weekdays: Set<DayOfWeek>,
    excluded: Set<LocalDate> = emptySet(),
): List<LocalDate> {
    val days = mutableListOf<LocalDate>()
    var d = start
    while (!d.isAfter(end)) {
        if (d.dayOfWeek in weekdays && d !in excluded) days += d
        d = d.plusDays(1)
    }
    return days
}

/** 6.2의 계산식. {날짜: 분량} 과 진단 정보를 낸다. */
fun allocate(
    total: Int,
    start: LocalDate,
    end: LocalDate,
    weekdays: Set<DayOfWeek>,
    excluded: Set<LocalDate> = emptySet(),
    dailyMax: Int? = null,
): Allocation {
    val days = studyDays(start, end, weekdays, excluded)
    val d = days.size
    if (d == 0) throw PlanException("학습 가능일이 0일이다 — 요일 선택이나 기간을 다시 잡아야 한다")

    val b = ceil(d * BUFFER_RATIO).toInt()
    val n = d - b
    if (n <= 0) throw PlanException("학습 가능일 ${d}일 중 버퍼 ${b}일을 빼면 배분할 날이 없다")

    // 수정 ① 버퍼일을 기간 전체에 균등하게 흩는다 (뒤에 몰지 않는다)
    val bufferIdx = mutableSetOf<Int>()
    for (k in 0 until b) {
        bufferIdx += minOf(d - 1, ((k + 0.5) * d / b).toInt())
    }
    for (i in 0 until d) { // 반올림 충돌로 모자라면 뒤에서 채운다
        if (bufferIdx.size >= b) break
        if (i !in bufferIdx) bufferIdx += i
    }

    val allocDays = days.filterIndexed { i, _ -> i !in bufferIdx }
    val base = Math.floorDiv(total, n)
    val r = total - base * n // 앞쪽 R일에 +1

    val plan = LinkedHashMap<LocalDate, Int>()
    for (day in days) plan[day] = 0
    allocDays.forEachIndexed { j, day -> plan[day] = base + if (j < r) 1 else 0 }

    // 수정 ② 분량 0인 날은 학습일이 아니라 휴식일이다 (그냥 두면 자동으로 ✕가 찍힌다)
    val restDays = days.filter { plan.getValue(it) == 0 }

    val warnings = mutableListOf<PlanWarning>()
    if (base == 0) warnings += PlanWarning.TotalBelowAllocDays(total, n, d, r)
    val peak = plan.values.max()
    if (dailyMax != null && peak > dailyMax) warnings += PlanWarning.DailyMaxExceeded(peak, dailyMax)

    return Allocation(
        plan = plan,
        days = days,
        studyDayCount = d,
        bufferDayCount = b,
        allocDayCount = n,
        base = base,
        remainder = r,
        peak = peak,
        warnings = warnings,
        restDays = restDays,
        bufferDays = bufferIdx.sorted().map { days[it] },
    )
}

/**
 * [doneThrough] 날짜까지 [doneAmount] 만큼만 했을 때 남은 일정을 다시 편다.
 * 버퍼일부터 채우고, 그래도 모자라면 남은 학습일에 균등 추가한다.
 */
fun redistribute(
    allocation: Allocation,
    doneThrough: LocalDate,
    doneAmount: Int,
    dailyMax: Int? = null,
): Redistribution {
    val plan = allocation.plan
    val days = allocation.days
    val plannedSoFar = plan.entries.filter { !it.key.isAfter(doneThrough) }.sumOf { it.value }
    val behind = plannedSoFar - doneAmount
    if (behind <= 0) {
        return Redistribution(LinkedHashMap(plan), behind = 0, usedBufferDays = 0, addedPerDay = 0, warnings = emptyList())
    }

    val new = LinkedHashMap(plan)
    val rest = days.filter { it.isAfter(doneThrough) }
    val buffers = rest.filter { plan.getValue(it) == 0 }
    val actives = rest.filter { plan.getValue(it) > 0 }

    var left = behind
    var usedBuffer = 0
    // 1) 버퍼일에 평상시 하루치만큼 채워 넣는다 — 하루 분량이 안 늘어난다
    val unit = if (allocation.base > 0) allocation.base else 1
    for (day in buffers) {
        if (left <= 0) break
        val put = minOf(unit, left)
        new[day] = put
        left -= put
        usedBuffer += 1
    }

    val warnings = mutableListOf<PlanWarning>()
    var added = 0
    // 2) 그래도 남으면 남은 학습일에 균등 추가 → 하루 분량이 늘어난다
    if (left > 0) {
        val targets = actives + buffers.filter { new.getValue(it) > 0 }
        if (targets.isEmpty()) {
            warnings += PlanWarning.NoDaysLeftToRedistribute
        } else {
            added = ceil(left.toDouble() / targets.size).toInt()
            for (day in targets) {
                val give = minOf(added, left)
                new[day] = new.getValue(day) + give
                left -= give
                if (left <= 0) break
            }
            val peak = if (rest.isNotEmpty()) rest.maxOf { new.getValue(it) } else 0
            if (dailyMax != null && peak > dailyMax) {
                warnings += PlanWarning.RedistributedDailyMaxExceeded(peak, dailyMax)
            }
        }
    }

    return Redistribution(new, behind, usedBuffer, added, warnings)
}
