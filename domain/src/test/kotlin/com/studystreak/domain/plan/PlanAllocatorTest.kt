package com.studystreak.domain.plan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * allocation.py 의 검증 16개를 그대로 옮긴 것.
 * 절 번호(1~7)와 순서는 파이썬 출력과 1:1 대응한다.
 */
class PlanAllocatorTest {

    /** 평일 */
    private val wd = setOf(
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
    )

    /** 1·5·6·7번이 공유하는 기본 케이스 — 정석 480페이지, 9/9 ~ 12/31, 평일 */
    private fun baseCase(): Allocation =
        allocate(480, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 12, 31), wd, dailyMax = 10)

    // ── 1. 기본 케이스 ────────────────────────────────────────────

    @Test
    fun `배분 총합이 총분량과 정확히 일치`() {
        val a = baseCase()
        assertEquals(480, a.plan.values.sum(), "합계 ${a.plan.values.sum()}")
    }

    @Test
    fun `버퍼일 수가 계산과 일치`() {
        val a = baseCase()
        val zeros = a.plan.values.count { it == 0 }
        assertEquals(a.bufferDayCount, zeros, "0인 날 ${zeros}일 / 버퍼 ${a.bufferDayCount}일")
    }

    @Test
    fun `경고 없음`() {
        val a = baseCase()
        assertTrue(a.warnings.isEmpty(), a.warnings.map { it.message }.toString())
    }

    // ── 2. 합계 정확성 — 무작위 200조합 ───────────────────────────

    @Test
    fun `200조합 모두 합계 일치`() {
        val cases = loadRandomCases()
        assertEquals(200, cases.size, "파이썬이 만든 200조합이 그대로 들어와야 한다")

        val bad = mutableListOf<String>()
        for (c in cases) {
            val a = try {
                allocate(c.total, c.start, c.end, c.weekdays)
            } catch (e: PlanException) {
                continue // 파이썬도 PlanError 는 건너뛴다
            }
            val sum = a.plan.values.sum()
            if (sum != c.total) bad += "$c → $sum"
        }
        assertTrue(bad.isEmpty(), bad.take(3).toString())
    }

    // ── 3. 엣지 케이스 ────────────────────────────────────────────

    @Test
    fun `학습 가능일 0일이면 막는다`() {
        // 월~금 플랜인데 기간이 주말뿐
        val e = planErrorFrom(
            100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11),
            setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
        )
        assertEquals("학습 가능일이 0일이다 — 요일 선택이나 기간을 다시 잡아야 한다", e.message)
    }

    @Test
    fun `학습일 1일이면 막는다 (버퍼가 전부 먹음)`() {
        val e = planErrorFrom(100, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7), wd)
        assertEquals("학습 가능일 1일 중 버퍼 1일을 빼면 배분할 날이 없다", e.message)
    }

    /**
     * assertThrows 는 inline 이라 호출한 함수 이름으로 클래스 파일을 하나 만든다.
     * 한글 이름 테스트 안에서 직접 부르면 ASCII 로케일 환경에서 그 파일을 못 써서 컴파일이 깨진다.
     * 그래서 ASCII 이름 도우미로 한 겹 감싼다.
     */
    private fun planErrorFrom(
        total: Int,
        start: LocalDate,
        end: LocalDate,
        weekdays: Set<DayOfWeek>,
    ): PlanException = assertThrows<PlanException> { allocate(total, start, end, weekdays) }

    @Test
    fun `총분량이 날짜보다 적으면 경고가 뜬다`() {
        val a = allocate(5, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 12, 31), wd)
        assertTrue(a.warnings.isNotEmpty(), a.warnings.map { it.message }.toString())
    }

    @Test
    fun `하루 상한 초과를 잡아낸다`() {
        val a = allocate(2000, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 10, 9), wd, dailyMax = 50)
        assertTrue(
            a.warnings.any { it is PlanWarning.DailyMaxExceeded },
            a.warnings.map { it.message }.toString(),
        )
    }

    // ── 4. 수정 ② 검증 — 분량 0인 날은 휴식일로 빠지는가 ──────────

    @Test
    fun `분량 0인 날이 전부 휴식일로 분류된다`() {
        val a = allocate(5, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 12, 31), wd)
        val study = a.days.filter { a.plan.getValue(it) > 0 }

        assertEquals(
            a.studyDayCount, study.size + a.restDays.size,
            "학습 ${study.size}일 + 휴식 ${a.restDays.size}일 / 전체 ${a.studyDayCount}일",
        )
        assertTrue(
            a.restDays.all { a.plan.getValue(it) == 0 },
            "휴식일로 분류됐는데 분량이 남아 있는 날이 있다",
        )
    }

    // ── 5. 재분배 — 3일 밀렸을 때 하루 분량이 유지되는가 ──────────

    @Test
    fun `버퍼로 흡수돼 하루 분량이 그대로`() {
        val a = baseCase()
        val days = a.days
        val cut = days[9] // 10일째까지
        val done = doneAmountForThreeMissedDays(a)
        val r = redistribute(a, cut, done, dailyMax = 10)

        val peakAfter = days.filter { it.isAfter(cut) }.maxOf { r.plan.getValue(it) }
        assertEquals(a.peak, peakAfter, "이전 ${a.peak} → 이후 $peakAfter")
    }

    @Test
    fun `남은 계획 합 = 총분량 − 실제 완료량`() {
        val a = baseCase()
        val days = a.days
        val cut = days[9]
        val done = doneAmountForThreeMissedDays(a)
        val r = redistribute(a, cut, done, dailyMax = 10)

        val remain = days.filter { it.isAfter(cut) }.sumOf { r.plan.getValue(it) }
        assertEquals(480 - done, remain, "남은 계획 $remain, 기대 ${480 - done}")
    }

    /** 10일째까지의 계획분 중 8·9·10일째 3일치를 안 한 상태 */
    private fun doneAmountForThreeMissedDays(a: Allocation): Int {
        val days = a.days
        val planned = days.take(10).sumOf { a.plan.getValue(it) }
        return planned - a.plan.getValue(days[7]) - a.plan.getValue(days[8]) - a.plan.getValue(days[9])
    }

    // ── 6. 재분배 — 버퍼를 다 쓸 만큼 크게 밀렸을 때 ──────────────

    @Test
    fun `상한 초과를 경고한다`() {
        val a = baseCase()
        val r = redistribute(a, a.days[39], doneAmount = 0, dailyMax = 10) // 40일간 아무것도 안 함

        assertTrue(
            r.warnings.any { it is PlanWarning.RedistributedDailyMaxExceeded },
            r.warnings.map { it.message }.toString(),
        )
    }

    @Test
    fun `남은 계획 합 = 총분량 − 실제 완료량(0)`() {
        val a = baseCase()
        val cut = a.days[39]
        val r = redistribute(a, cut, doneAmount = 0, dailyMax = 10)

        val remain = a.days.filter { it.isAfter(cut) }.sumOf { r.plan.getValue(it) }
        assertEquals(480, remain, "남은 계획 $remain")
    }

    // ── 7. 수정 ① 검증 — 버퍼일이 기간 전체에 흩어져 있는가 ───────

    @Test
    fun `첫 버퍼가 기간 앞부분(25% 이내)에 있다`() {
        val a = baseCase()
        val zeros = a.days.filter { a.plan.getValue(it) == 0 }
        val span = daysBetween(a.days.first(), a.days.last()).toDouble()
        val firstRatio = daysBetween(a.days.first(), zeros.first()) / span

        assertTrue(firstRatio <= 0.25, "${"%.0f".format(firstRatio * 100)}% 지점")
    }

    @Test
    fun `마지막 버퍼가 기간 뒷부분(75% 이후)에 있다`() {
        val a = baseCase()
        val zeros = a.days.filter { a.plan.getValue(it) == 0 }
        val span = daysBetween(a.days.first(), a.days.last()).toDouble()
        val lastRatio = daysBetween(a.days.first(), zeros.last()) / span

        assertTrue(lastRatio >= 0.75, "${"%.0f".format(lastRatio * 100)}% 지점")
    }

    @Test
    fun `버퍼가 한 달 넘게 비지 않는다`() {
        val a = baseCase()
        val zeros = a.days.filter { a.plan.getValue(it) == 0 }
        val gaps = zeros.zipWithNext { x, y -> daysBetween(x, y) }

        assertTrue(gaps.max() <= 31, "최대 간격 ${gaps.max()}일")
    }

    // ── 도우미 ────────────────────────────────────────────────────

    private fun daysBetween(from: LocalDate, to: LocalDate): Long =
        ChronoUnit.DAYS.between(from, to)

    private data class RandomCase(
        val total: Int,
        val start: LocalDate,
        val end: LocalDate,
        val weekdays: Set<DayOfWeek>,
    )

    private fun loadRandomCases(): List<RandomCase> {
        val text = checkNotNull(javaClass.getResourceAsStream("/allocation_random_cases.tsv")) {
            "allocation_random_cases.tsv 가 테스트 리소스에 없다"
        }.bufferedReader().readText()

        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (total, start, end, weekdays) = line.split("\t")
                RandomCase(
                    total = total.toInt(),
                    start = LocalDate.parse(start),
                    end = LocalDate.parse(end),
                    // 파이썬 weekday(): 0=월 … 6=일
                    weekdays = weekdays.split(",").map { DayOfWeek.of(it.toInt() + 1) }.toSet(),
                )
            }
            .toList()
    }
}
