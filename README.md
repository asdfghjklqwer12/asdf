# 학습 streak 앱 — 규칙 엔진

기획 노트 5장(streak 규칙)과 6장(플랜 생성)을 Kotlin으로 옮긴 것.
UI를 붙이기 전에 규칙만 먼저 고정하는 단계라, 이 저장소에는 아직 순수 로직과 테스트만 있다.

## 구조

```
:domain          Android 의존성이 없는 순수 Kotlin 모듈 (java-library + kotlin-jvm)
  streak/        ○△✕ 판정, △ 유예, 프리즈, 조각        ← streak_sim.py 이식
  plan/          자동 분배와 재분배                     ← allocation.py 이식

:sim             규칙을 돌려보고 표로 찍는 실행기 (application)

docs/기획노트.md     확정된 기획. 규칙의 출처
docs/측정-결과.md    이식한 엔진으로 규칙을 재어본 결과
docs/남은-일.md      다음 할 일과 아직 답이 안 정해진 것
reference/           원본 파이썬 구현 — 규칙이 갈리면 이쪽이 맞다
```

둘 다 Compose 도 Android SDK 도 참조하지 않아 Android SDK 없이 빌드·실행된다.
Compose UI 모듈(`:app`)은 아직 없다 — `settings.gradle.kts` 의 TODO 참고.

의존 방향은 단방향이다: `:sim` → `:domain`. `:domain` 은 자기를 쓰는 쪽을 모른다.

## 테스트

```
./gradlew :domain:test
```

| 테스트 | 개수 | 출처 |
|---|---|---|
| `StreakEngineTest` | 22 | `streak_sim.py` 의 `edge_tests()` 를 1:1 이식 |
| `PlanAllocatorTest` | 16 | `allocation.py` 의 검증 1~7절을 1:1 이식 |
| `SettlementContractTest` | 8 | 파이썬에 없는 이식 조건 — 멱등성, `Clock` 주입, 7일 창 |
| `StreakBranchTest` | 14 | 파이썬이 단언하지 않은 분기 + 30% 경계 |
| `PlanBranchTest` | 10 | 같음 — 제외일, 남은 날 없음, 연쇄 재분배 등 |
| `CarryOverTest` | 22 | 이월 규칙 — 파이썬에 없던 새 규칙 |

## 규칙을 눈으로 보기

```
./gradlew :sim:run                     60일 시나리오 (씨앗 7)
./gradlew :sim:run --args="42"         씨앗을 바꿔서
./gradlew :sim:run --args="measure"    기획 노트 5.2 숫자 재측정
./gradlew :sim:run --args="rates"      완료율에 따른 ○ / △ / ✕ 분포
./gradlew :sim:run --args="skips"      빼먹는 날이 섞였을 때 ✕ 빈도
./gradlew :sim:run --args="carry"      이월이 만드는 눈덩이
./gradlew :sim:run --args="churn"      이탈 위험으로 본 이월 설정
./gradlew :sim:run --args="censoring"  측정이 시행 길이에 흔들리는지
```

첫 번째는 수학·영어·국어(필수)와 한국사(선택)로 60일을 돌려서, 하루씩 판정과
전체 streak·△ 개수·프리즈·조각이 어떻게 움직이는지 표로 찍는다. 씨앗이 고정돼 있어
돌릴 때마다 같은 결과가 나온다.

나머지는 규칙을 통계적으로 재는 것이다. 결과 해석은 `docs/측정-결과.md` 에 있다.

전부 검증이 아니라 관찰용이다 — 규칙이 맞는지는 아래 테스트가 본다.

## 규칙에 손대지 않았다는 것의 의미

파이썬 구현이 정답이라는 전제로 옮겼고, 옮긴 뒤 두 구현의 출력을 직접 맞대어 확인했다.

- 분배: 무작위 1,500조합(제외일·음수 분량·극단 기간 포함) → `allocate` 1,123건 +
  `redistribute` 17,600건, **19,100줄이 완전히 동일**. 파이썬 검증이 한 번도 안 밟는
  경로(`남은 날 없음` 2,126회, `behind<=0` 9,262회)까지 포함
- streak: 시나리오 129개 × 최대 200일 → 정산 24,201건 + 조각·광고 13,403건,
  **37,604줄이 완전히 동일**. ○△✕ 네 판정, △발/✕발 초기화, 프리즈 방어, 조각 상한 전부
- 멱등성: 같은 날짜 재정산 2,892회를 전부 차단하고 상태가 안 바뀌는 것 확인

- 30% 경계 전수 검사: 태스크 1~300개 × 체크 0~전부 **45,450조합 동일**
- 버퍼 인덱스 공식 전수 검사: 학습 가능일 2~2000일 **동일**
- 연쇄 재분배 1,200건 **동일**

합쳐서 **10만 줄 넘는 출력을 맞대어 차이가 하나도 없었다.**
`reference/` 에 원본 파이썬을 넣어뒀으므로 언제든 다시 맞대어 볼 수 있다.

## 시각 판정

`LocalDate.now()` 를 직접 부르는 곳이 없다. `StreakEngine` 이 `Clock` 을 주입받고,
학습일 경계는 `currentStudyDate(zone)` 이 계산한다 — 하루 마감 시각(기본 새벽 3시)과
**계정에 저장된** 타임존 기준이다(기획 노트 5.3). 나중에 서버 시각으로 갈아끼우면 된다.

## 빌드 환경 메모

테스트 이름이 한글이라 빌드 JVM 로케일이 UTF-8이어야 한다(`LANG=C.UTF-8` 등).
ASCII 로케일에서는 컴파일러가 한글 이름 클래스 파일을 쓰지 못해 빌드가 깨진다.
