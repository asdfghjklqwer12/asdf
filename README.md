# 학습 streak 앱 — 규칙 엔진

기획 노트 5장(streak 규칙)과 6장(플랜 생성)을 Kotlin으로 옮긴 것.
UI를 붙이기 전에 규칙만 먼저 고정하는 단계라, 이 저장소에는 아직 순수 로직과 테스트만 있다.

## 구조

```
:domain          Android 의존성이 없는 순수 Kotlin 모듈 (java-library + kotlin-jvm)
  streak/        ○△✕ 판정, △ 유예, 프리즈, 조각        ← streak_sim.py 이식
  plan/          자동 분배와 재분배                     ← allocation.py 이식

:sim             규칙을 60일 돌려보고 표로 찍는 실행기 (application)
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
| `SettlementContractTest` | 7 | 파이썬에 없는 이식 조건 — 정산 멱등성, `Clock` 주입 |

## 규칙을 눈으로 보기

```
./gradlew :sim:run
```

수학·영어·국어(필수)와 한국사(선택)로 60일을 돌려서, 하루씩 ○ △ ✕ 판정과
전체 streak·△ 개수·프리즈·조각이 어떻게 움직이는지 표로 찍는다.
초기화가 언제 일어나는지, 조각 6개가 프리즈로 바뀌는 순간이 어디인지 그대로 보인다.

씨앗이 고정돼 있어 돌릴 때마다 같은 결과가 나온다. 다른 전개를 보려면 `--args="42"`.

검증이 아니라 관찰용이다 — 규칙이 맞는지는 아래 테스트가 본다.

## 규칙에 손대지 않았다는 것의 의미

파이썬 구현이 정답이라는 전제로 옮겼고, 옮긴 뒤 두 구현의 출력을 직접 맞대어 확인했다.

- 분배: 무작위 200조합의 `allocate` + 그 위에서 돌린 `redistribute` 2,400건 →
  하루치 배열·버퍼일·휴식일·경고까지 파이썬과 완전히 동일
- streak: 30개 시나리오 × 120일(5,346줄) →
  ○△✕ 판정·전체/과목 streak·프리즈·조각·초기화 시점까지 파이썬과 완전히 동일

## 시각 판정

`LocalDate.now()` 를 직접 부르는 곳이 없다. `StreakEngine` 이 `Clock` 을 주입받고,
학습일 경계는 `currentStudyDate(zone)` 이 계산한다 — 하루 마감 시각(기본 새벽 3시)과
**계정에 저장된** 타임존 기준이다(기획 노트 5.3). 나중에 서버 시각으로 갈아끼우면 된다.

## 빌드 환경 메모

테스트 이름이 한글이라 빌드 JVM 로케일이 UTF-8이어야 한다(`LANG=C.UTF-8` 등).
ASCII 로케일에서는 컴파일러가 한글 이름 클래스 파일을 쓰지 못해 빌드가 깨진다.
