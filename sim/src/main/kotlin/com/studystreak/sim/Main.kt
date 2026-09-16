package com.studystreak.sim

/**
 * :sim 진입점.
 *
 *   ./gradlew :sim:run                     60일 시나리오 (씨앗 7)
 *   ./gradlew :sim:run --args="42"         60일 시나리오 (씨앗 42)
 *   ./gradlew :sim:run --args="measure"    기획 노트 5.2 숫자 재측정
 *   ./gradlew :sim:run --args="rates"      완료율에 따른 ○ / △ / ✕ 분포
 *   ./gradlew :sim:run --args="skips"      빼먹는 날이 섞였을 때 ✕ 빈도
 *   ./gradlew :sim:run --args="tasks"      하루치를 태스크 몇 개로 쪼갤 것인가
 *   ./gradlew :sim:run --args="buffer"     버퍼 비율 튜닝
 *   ./gradlew :sim:run --args="freeze"     프리즈가 실제로 막아주는 비율
 *   ./gradlew :sim:run --args="carry"      이월 규칙이 만드는 눈덩이
 *   ./gradlew :sim:run --args="censoring"  측정이 시행 길이에 흔들리는지 확인
 */
fun main(args: Array<String>) {
    when (val arg = args.firstOrNull()) {
        null -> printScenario(seed = 7)
        "measure" -> printMeasurement()
        "rates" -> printRateSweep()
        "skips" -> printSkipSweep()
        "tasks" -> printTaskSplit()
        "buffer" -> printBufferTuning()
        "freeze" -> printFreezeCoverage()
        "carry" -> printCarryOver()
        "censoring" -> printCensoringCheck()
        else -> {
            val seed = arg.toIntOrNull()
            if (seed == null) {
                println("쓰는 법: (없음) | <씨앗 숫자> | measure | rates | skips | tasks | buffer | freeze | carry | censoring")
            } else {
                printScenario(seed)
            }
        }
    }
}
