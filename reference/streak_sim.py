"""
학습 streak 앱 — streak 판정 로직 시뮬레이션
기획 노트 5장 규칙을 그대로 구현하고, 60일치 가상 데이터로 돌려서
○/△/✕ 판정 · 초기화 시점 · 프리즈 · 조각이 의도대로 움직이는지 확인한다.
"""
from dataclasses import dataclass, field
from datetime import date, timedelta

WINDOW = 7          # △를 세는 창 (오늘 포함 최근 7일)
TRI_LIMIT = 3       # △가 이만큼 쌓이면 전체 초기화
SHARDS_PER_FREEZE = 6
SHARD_CAP = SHARDS_PER_FREEZE - 1   # 프리즈를 보유 중이면 조각은 5개까지만 (결제로도 못 넘김)
AD_VIEWS_PER_SHARD = 2     # 광고 2회를 봐야 조각 1개 (B안)
AD_VIEWS_PER_DAY = 2       # 광고는 하루 2회까지 → 하루 최대 조각 1개
REST_SHARDS_PER_WEEK = 3
SUBJ_FREEZE_MAX = 2
OVERALL_FREEZE_MAX = 1
PARTIAL_MIN_RATIO = 0.30   # 그날 태스크의 이 비율 이상 해야 △, 못 미치면 ✕


@dataclass
class Subject:
    name: str
    required: bool
    weekdays: set          # 0=월 ... 6=일
    tasks: int = 1         # 그날 배정되는 태스크 수 (기간 플랜)
    same_day: bool = False # 당일 계획 과목인가 (그날그날 태스크를 직접 넣는다)
    daily: dict = field(default_factory=dict)   # 당일 계획: {날짜: 그날 넣은 태스크 수}
    streak: int = 0
    longest: int = 0
    freeze: int = 0

    def tasks_on(self, d):
        return self.daily.get(d, 0) if self.same_day else self.tasks

    def studies_on(self, d):
        if self.same_day:
            # 계획을 안 넣은 날은 학습일이 아니다 (휴식).
            # 안 그러면 계획을 안 짠 날마다 태스크 0개짜리 학습일이 되어 자동으로 ✕가 찍힌다.
            return self.tasks_on(d) > 0
        return d.weekday() in self.weekdays


@dataclass
class DayLog:
    d: date
    mark: str             # full / partial / none / rest
    due: int
    done: int
    consumed: bool = False   # 초기화에 쓰인 △인가


@dataclass
class Account:
    overall: int = 0
    longest: int = 0
    freeze: int = 0
    shards: int = 0
    logs: list = field(default_factory=list)
    events: list = field(default_factory=list)   # (날짜, 출처) — 조각 지급 이력
    ad_views: list = field(default_factory=list) # 광고 시청 이력
    ad_progress: int = 0                         # 조각까지 남은 광고 진행도 (0~1)

    # ---------- 조각 ----------
    def _shards_this_week(self, d, source):
        monday = d - timedelta(days=d.weekday())
        return sum(1 for (ed, es) in self.events
                   if es == source and monday <= ed <= d)

    def _grant_shard(self, d, source):
        # 프리즈를 이미 들고 있으면 조각은 5개에서 멈춘다.
        # 상한을 안 두면 조각을 미리 잔뜩 사두고 프리즈를 쓰는 족족 채울 수 있어서
        # "보유 상한 1개"라는 안전장치가 재충전 속도 쪽에서 뚫린다.
        if self.freeze >= OVERALL_FREEZE_MAX and self.shards >= SHARD_CAP:
            return "거절(가득 참 — 프리즈 1개 + 조각 5개)"
        self.events.append((d, source))
        self.shards += 1
        if self.shards >= SHARDS_PER_FREEZE and self.freeze < OVERALL_FREEZE_MAX:
            self.shards -= SHARDS_PER_FREEZE
            self.freeze += 1
            return "조각+1 → 프리즈 획득"
        return "조각+1"

    def rest_check(self, d):
        """휴식일에 자발적으로 체크 → 조각 1개 (주 3개까지)."""
        if self._shards_this_week(d, "rest") >= REST_SHARDS_PER_WEEK:
            return "거절(주 상한)"
        return self._grant_shard(d, "rest")

    def watch_ad(self, d):
        """보상형 광고 1회 시청. 2회 모여야 조각 1개 (B안), 하루 2회까지."""
        if sum(1 for ed in self.ad_views if ed == d) >= AD_VIEWS_PER_DAY:
            return "거절(일 상한)"
        self.ad_views.append(d)
        self.ad_progress += 1
        if self.ad_progress >= AD_VIEWS_PER_SHARD:
            self.ad_progress = 0
            return self._grant_shard(d, "ad")
        return f"광고 {self.ad_progress}/{AD_VIEWS_PER_SHARD} (1회 더 보면 조각)"

    def give_shard(self, d, source):     # 이전 이름 호환
        return self.rest_check(d) if source == "rest" else self.watch_ad(d)

    def consume_recent_partials(self, d):
        """초기화가 일어나면 창 안의 △를 소진 처리한다.
        안 하면 초기화 직후에도 창에 △가 그대로 남아 새 streak이 곧바로 또 죽는다."""
        lo = d - timedelta(days=WINDOW - 1)
        for l in self.logs:
            if l.mark == "partial" and lo <= l.d <= d and not l.consumed:
                l.consumed = True


def settle(acc, subjects, d, done_tasks, note=""):
    """하루 마감. done_tasks = {과목명: 그날 체크한 태스크 수}."""
    really_done = set()

    # 1단계 — 과목별 (과목 완료 = 그날 태스크 전부 체크, 5.1)
    for s in subjects:
        if not s.studies_on(d):
            continue
        if done_tasks.get(s.name, 0) >= s.tasks_on(d):
            s.streak += 1
            s.longest = max(s.longest, s.streak)
            if s.streak % 7 == 0 and s.freeze < SUBJ_FREEZE_MAX:
                s.freeze += 1
            really_done.add(s.name)
        else:
            if s.freeze > 0:
                s.freeze -= 1          # 과목 프리즈가 과목 streak만 지켜준다
            else:
                s.streak = 0
            # 전체 판정에서는 완료로 치지 않는다 → really_done 에 넣지 않음

    # 2단계 — 전체 (△/✕는 태스크 단위로 가른다)
    due = [s for s in subjects if s.required and s.studies_on(d)]
    done = [s for s in due if s.name in really_done]
    n_due, n_done = len(due), len(done)
    checked = sum(min(done_tasks.get(s.name, 0), s.tasks_on(d)) for s in due)
    total = sum(s.tasks_on(d) for s in due)
    ratio = (checked / total) if total else 0
    event = ""

    if n_due == 0:
        mark = "rest"
    elif n_done == n_due:
        mark = "full"
        acc.overall += 1
    elif ratio >= PARTIAL_MIN_RATIO:   # 그날 태스크의 30% 이상 했으면 △
        mark = "partial"
        acc.overall += 1
    else:                              # 30%에 못 미치면 ✕ (0%도 포함)
        mark = "none"

    log = DayLog(d, mark, n_due, n_done)
    acc.logs.append(log)

    # △ 트랙
    if mark == "partial":
        lo = d - timedelta(days=WINDOW - 1)
        recent = [l for l in acc.logs
                  if l.mark == "partial" and lo <= l.d <= d and not l.consumed]
        if len(recent) >= TRI_LIMIT:
            acc.overall = 0
            acc.consume_recent_partials(d)
            event = f"△ {len(recent)}개 → 초기화"

    # ✕ 트랙
    if mark == "none":
        if acc.freeze > 0:
            acc.freeze -= 1
            event = "프리즈로 방어 (숫자 유지)"
        else:
            acc.overall = 0
            acc.consume_recent_partials(d)   # △발 초기화와 똑같이 창을 비워준다
            event = "프리즈 없음 → 초기화"

    acc.longest = max(acc.longest, acc.overall)
    return log, event, note


# ────────────────────────────────────────────────
# 엣지 케이스 검증
# ────────────────────────────────────────────────
def edge_tests():
    out = []

    def fresh():
        subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=2),
                Subject("영어", True, {0, 1, 2, 3, 4}, tasks=2)]
        return Account(), subs

    D2 = {"수학": 2, "영어": 2}          # 둘 다 전부 완료
    D_MATH = {"수학": 2}                 # 수학만 완료

    # 1) 초기화 직후 △ 소진 처리가 되는가 (안 되면 새 streak이 △ 한 번에 또 죽는다)
    acc, subs = fresh()
    start = date(2026, 9, 7)                       # 월요일
    for i in range(3):                              # 월화수 모두 △
        settle(acc, subs, start + timedelta(days=i), D_MATH)
    after_reset = acc.overall
    settle(acc, subs, start + timedelta(days=3), D2)   # 목 ○
    settle(acc, subs, start + timedelta(days=4), D_MATH)           # 금 △
    out.append(("초기화 직후 △ 1개로 또 죽지 않는다",
                after_reset == 0 and acc.overall == 2,
                f"초기화 후 {after_reset} → ○ → △ 이면 2여야 함, 실제 {acc.overall}"))

    # 2) ✕ 프리즈: 1회 방어는 숫자 유지(+1 아님), 다음 ✕는 즉시 초기화
    acc, subs = fresh()
    for i in range(3):
        settle(acc, subs, start + timedelta(days=i), D2)
    before = acc.overall
    acc.freeze = 1
    settle(acc, subs, start + timedelta(days=3), {})              # 목 ✕ (방어)
    held = acc.overall
    settle(acc, subs, start + timedelta(days=4), {})              # 금 ✕ (초기화)
    out.append(("✕ 방어는 +1이 아니라 그 자리 유지",
                held == before, f"{before} → {held}"))
    out.append(("프리즈 소진 후 다음 ✕는 즉시 0",
                acc.overall == 0, f"실제 {acc.overall}"))

    # 3) 휴식일은 중립 — ○도 △도 아니고 끊기지도 않는다
    acc, subs = fresh()
    for i in range(3):
        settle(acc, subs, start + timedelta(days=i), D2)
    before = acc.overall
    sat = start + timedelta(days=5)
    log, _, _ = settle(acc, subs, sat, {})
    out.append(("휴식일은 rest, 숫자 변화 없음",
                log.mark == "rest" and acc.overall == before,
                f"{log.mark}, {before}→{acc.overall}"))

    # 4) 선택 과목은 전체 판정에서 빠진다
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=2),
            Subject("한국사", False, {0, 1, 2, 3, 4}, tasks=1)]
    log, _, _ = settle(acc, subs, start, {"수학": 2})   # 필수만 완료, 선택은 안 함
    out.append(("선택 과목 미완은 △를 만들지 않는다",
                log.mark == "full", f"실제 {log.mark}"))

    # 5) 과목 프리즈가 지켜준 과목은 전체 판정에서 완료가 아니다
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=2, freeze=1),
            Subject("영어", True, {0, 1, 2, 3, 4}, tasks=2)]
    log, _, _ = settle(acc, subs, start, {"영어": 2})
    out.append(("과목 프리즈로 지켜진 날은 전체에서 △",
                log.mark == "partial" and subs[0].streak == 0,
                f"mark={log.mark}, 수학 streak={subs[0].streak}"))

    # 6) 광고: 하루 2회까지, 2회당 조각 1개
    acc = Account()
    r = [acc.watch_ad(start) for _ in range(3)]
    out.append(("광고는 하루 2회까지", r[2].startswith("거절"), str(r)))
    out.append(("광고 2회 = 조각 1개 (하루 최대 1조각)",
                acc.shards == 1, f"shards={acc.shards}"))

    acc2 = Account()
    for i in range(6):
        for _ in range(2):
            acc2.watch_ad(start + timedelta(days=i))
    out.append(("광고만으로는 6일 걸려 프리즈 1개",
                acc2.freeze == 1 and acc2.shards == 0,
                f"freeze={acc2.freeze}, shards={acc2.shards}"))

    # 7) 프리즈를 이미 보유하면 조각은 5개에서 멈춘다
    acc3 = Account(freeze=1)
    for i in range(8):
        for _ in range(2):
            acc3.watch_ad(start + timedelta(days=i))
    out.append(("프리즈 보유 중엔 조각이 5개에서 멈춘다",
                acc3.freeze == 1 and acc3.shards == SHARD_CAP,
                f"freeze={acc3.freeze}, shards={acc3.shards}"))

    # 7b) 프리즈를 쓰고 나면 조각 1개로 바로 다시 채워진다 (대기 상태가 안 생긴다)
    acc3.freeze = 0                       # ✕ 방어로 소진했다고 치자
    msg = acc3.rest_check(start + timedelta(days=20))
    out.append(("프리즈 소진 후 조각 1개로 즉시 재충전",
                acc3.freeze == 1 and acc3.shards == 0,
                f"{msg} / freeze={acc3.freeze}, shards={acc3.shards}"))

    # 7c) ✕로 초기화될 때도 창의 △가 소진 처리된다
    acc5, subs5 = fresh()
    for i in range(2):                                     # 월화 △
        settle(acc5, subs5, start + timedelta(days=i), D_MATH)
    settle(acc5, subs5, start + timedelta(days=2), {})      # 수 ✕ → 초기화
    settle(acc5, subs5, start + timedelta(days=3), D2)      # 목 ○
    settle(acc5, subs5, start + timedelta(days=4), D_MATH)  # 금 △
    out.append(("✕로 초기화된 뒤에도 △ 1개로 또 죽지 않는다",
                acc5.overall == 2, f"실제 {acc5.overall} (2여야 함)"))

    # 8) 휴식일 조각은 주 3개까지
    acc4 = Account()
    monday = date(2026, 9, 7)
    res = [acc4.rest_check(monday + timedelta(days=i)) for i in range(4)]
    out.append(("휴식일 조각은 주 3개까지",
                res[3].startswith("거절"), str(res)))

    # 9) 필수 1과목이어도 태스크를 일부 했으면 △ (✕가 아님)
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=3)]
    log, _, _ = settle(acc, subs, start, {"수학": 1})
    out.append(("필수 1과목 + 태스크 일부 완료 → ✕가 아니라 △",
                log.mark == "partial", f"실제 {log.mark}"))

    # 10) 정말 아무것도 안 했을 때만 ✕
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=3)]
    log, _, _ = settle(acc, subs, start, {})
    out.append(("태스크를 하나도 안 하면 ✕",
                log.mark == "none", f"실제 {log.mark}"))

    # 11) 30% 미만만 하면 △가 아니라 ✕
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=10)]
    log, _, _ = settle(acc, subs, start, {"수학": 1})       # 10%
    out.append(("태스크 10% 완료는 △가 아니라 ✕",
                log.mark == "none", f"실제 {log.mark}"))

    # 12) 30% 딱 채우면 △
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=10)]
    log, _, _ = settle(acc, subs, start, {"수학": 3})       # 30%
    out.append(("태스크 30% 완료는 △",
                log.mark == "partial", f"실제 {log.mark}"))

    # 13) 여러 과목 합산 비율로 판정한다
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=4),
            Subject("영어", True, {0, 1, 2, 3, 4}, tasks=4)]
    log, _, _ = settle(acc, subs, start, {"수학": 1})       # 1/8 = 12.5%
    out.append(("과목 합산 12.5%는 ✕",
                log.mark == "none", f"실제 {log.mark}"))

    # 14) 당일 계획: 계획을 안 넣은 날은 휴식 — 끊기지도, 늘지도 않는다
    acc = Account()
    today_plan = Subject("오늘 계획", True, set(), same_day=True,
                         daily={start: 3, start + timedelta(days=2): 2})
    subs = [today_plan]
    settle(acc, subs, start, {"오늘 계획": 3})                    # 월: 3개 넣고 다 함 → ○
    a1 = acc.overall
    log2, _, _ = settle(acc, subs, start + timedelta(days=1), {})  # 화: 계획 안 넣음
    a2 = acc.overall
    out.append(("당일 계획을 안 넣은 날은 휴식 (streak 유지)",
                log2.mark == "rest" and a2 == a1 == 1,
                f"mark={log2.mark}, {a1}→{a2}"))

    settle(acc, subs, start + timedelta(days=2), {"오늘 계획": 2})  # 수: 2개 넣고 다 함 → ○
    out.append(("계획을 다시 넣은 날 streak이 이어진다",
                acc.overall == 2 and today_plan.streak == 2,
                f"전체 {acc.overall}, 과목 {today_plan.streak}"))

    # 15) 당일 계획도 30% 규칙이 그대로 적용된다
    acc = Account()
    tp = Subject("오늘 계획", True, set(), same_day=True, daily={start: 10})
    log, _, _ = settle(acc, [tp], start, {"오늘 계획": 2})          # 20%
    out.append(("당일 계획도 30% 미만이면 ✕", log.mark == "none", f"실제 {log.mark}"))

    # 16) 기간 플랜과 당일 계획을 같이 돌리면 태스크가 합산된다
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=2),
            Subject("오늘 계획", True, set(), same_day=True, daily={start: 2})]
    log, _, _ = settle(acc, subs, start, {"수학": 2})               # 2/4 = 50%
    out.append(("기간 플랜 + 당일 계획은 태스크 합산으로 판정",
                log.mark == "partial" and log.due == 2,
                f"mark={log.mark}, due={log.due}"))

    return out


# ────────────────────────────────────────────────
# 60일 시나리오
# ────────────────────────────────────────────────
def scenario():
    acc = Account()
    subs = [Subject("수학", True, {0, 1, 2, 3, 4}, tasks=2),
            Subject("영어", True, {0, 1, 2, 3, 4}, tasks=1),
            Subject("국어", True, {0, 2, 4}, tasks=2),          # 월수금
            Subject("한국사", False, {0, 1, 2, 3, 4}, tasks=1)]  # 선택
    by = {s.name: s for s in subs}

    # 하루별 시나리오: 요일/상황에 따라 무엇을 완료했는지
    import random
    random.seed(7)
    start = date(2026, 9, 7)
    rows = []

    for i in range(60):
        d = start + timedelta(days=i)
        due = [s.name for s in subs if s.required and s.studies_on(d)]
        note = ""

        # 구간별 성실도를 다르게 줘서 여러 상황을 만든다
        if i < 14:
            p = 0.95            # 초반: 아주 성실
        elif i < 24:
            p = 0.62            # 중반: 흔들림 (△가 자주 나오는 구간)
        elif i < 30:
            p = 0.20            # 슬럼프 (✕가 나오는 구간)
        else:
            p = 0.88            # 회복

        done = {}
        for n in due:
            done[n] = sum(1 for _ in range(by[n].tasks) if random.random() < p)
        # 선택 과목은 절반쯤
        if by["한국사"].studies_on(d) and random.random() < 0.5:
            done["한국사"] = 1

        ev_shard = ""
        # 휴식일에 자발적으로 체크하는 경우
        if not due and random.random() < 0.6:
            ev_shard = acc.rest_check(d)
            note = "휴식일 자발 체크"
        # streak이 위태로우면 광고를 본다 (프리즈가 없고 최근에 흔들렸을 때)
        elif acc.freeze == 0 and acc.overall >= 5 and random.random() < 0.35:
            ev_shard = acc.watch_ad(d)
            note = "광고 시청"

        log, event, _ = settle(acc, subs, d, done)

        lo = d - timedelta(days=WINDOW - 1)
        tri = sum(1 for l in acc.logs
                  if l.mark == "partial" and lo <= l.d <= d and not l.consumed)

        rows.append((d, log, acc.overall, tri, acc.freeze, acc.shards,
                     event or ev_shard, note))

    return acc, subs, rows


MARK = {"full": "○", "partial": "△", "none": "✕", "rest": "·"}
WD = "월화수목금토일"

if __name__ == "__main__":
    print("=" * 74)
    print("엣지 케이스 검증")
    print("=" * 74)
    ok_all = True
    for name, ok, detail in edge_tests():
        ok_all &= ok
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print(f"         → {detail}")
    print(f"\n  전체: {'모두 통과' if ok_all else '실패 있음'}\n")

    acc, subs, rows = scenario()
    print("=" * 74)
    print("60일 시나리오")
    print("=" * 74)
    print(f"{'날짜':<12}{'요일':<4}{'판정':<5}{'완료':<7}{'연속':<5}{'△':<4}{'프리즈':<6}{'조각':<5}비고")
    print("-" * 74)
    for d, log, ov, tri, fz, sh, event, note in rows:
        done_s = f"{log.done}/{log.due}" if log.due else "—"
        msg = event if event else note
        print(f"{d.isoformat():<12}{WD[d.weekday()]:<4}{MARK[log.mark]:<5}"
              f"{done_s:<7}{ov:<5}{tri:<4}{fz:<6}{sh:<5}{msg}")

    print("-" * 74)
    n = {k: sum(1 for r in rows if r[1].mark == k) for k in MARK}
    print(f"○ {n['full']}일 · △ {n['partial']}일 · ✕ {n['none']}일 · 휴식 {n['rest']}일")
    print(f"전체 연속: 현재 {acc.overall}일 / 최장 {acc.longest}일")
    for s in subs:
        tag = "필수" if s.required else "선택"
        print(f"  {s.name}({tag}) 현재 {s.streak}일 / 최장 {s.longest}일 / 과목프리즈 {s.freeze}")
