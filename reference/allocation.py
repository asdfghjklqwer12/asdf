"""
학습 streak 앱 — 자동 분배 로직 검증
기획 노트 6.2의 계산식을 그대로 구현하고, 여러 입력값으로 돌려서
① 합계가 정확한지 ② 엣지 케이스에서 안 깨지는지 ③ 재분배가 의도대로 되는지 확인한다.
"""
import math
from datetime import date, timedelta

BUFFER_RATIO = 0.15


class PlanError(Exception):
    pass


def study_days(start, end, weekdays, excluded=()):
    """기간 안에서 실제 학습일 날짜 목록."""
    excluded = set(excluded)
    days, d = [], start
    while d <= end:
        if d.weekday() in weekdays and d not in excluded:
            days.append(d)
        d += timedelta(days=1)
    return days


def allocate(total, start, end, weekdays, excluded=(), daily_max=None):
    """6.2의 계산식. {날짜: 분량} 과 진단 정보를 낸다."""
    days = study_days(start, end, weekdays, excluded)
    D = len(days)
    if D == 0:
        raise PlanError("학습 가능일이 0일이다 — 요일 선택이나 기간을 다시 잡아야 한다")

    B = math.ceil(D * BUFFER_RATIO)
    N = D - B
    if N <= 0:
        raise PlanError(f"학습 가능일 {D}일 중 버퍼 {B}일을 빼면 배분할 날이 없다")

    # 수정 ① 버퍼일을 기간 전체에 균등하게 흩는다 (뒤에 몰지 않는다)
    buffer_idx = set()
    for k in range(B):
        buffer_idx.add(min(D - 1, int((k + 0.5) * D / B)))
    for i in range(D):                       # 반올림 충돌로 모자라면 뒤에서 채운다
        if len(buffer_idx) >= B:
            break
        if i not in buffer_idx:
            buffer_idx.add(i)

    alloc_days = [d for i, d in enumerate(days) if i not in buffer_idx]
    base = total // N
    R = total - base * N            # 앞쪽 R일에 +1

    plan = {d: 0 for d in days}
    for j, d in enumerate(alloc_days):
        plan[d] = base + (1 if j < R else 0)

    # 수정 ② 분량 0인 날은 학습일이 아니라 휴식일이다 (그냥 두면 자동으로 ✕가 찍힌다)
    rest_days = [d for d in days if plan[d] == 0]

    warn = []
    if base == 0:
        warn.append(f"총분량({total})이 배분일({N})보다 적다 — 실제로 공부하는 날은 {R}일뿐이고 "
                    f"나머지 {D - R}일은 휴식일이 된다")
    peak = max(plan.values())
    if daily_max is not None and peak > daily_max:
        warn.append(f"하루 분량 {peak}이 상한 {daily_max}을 넘는다 — 목표일 연장이나 분량 축소가 필요하다")

    return plan, {"days": days, "D": D, "B": B, "N": N, "base": base, "R": R,
                  "peak": peak, "warn": warn, "rest_days": rest_days,
                  "buffer_days": [days[i] for i in sorted(buffer_idx)]}


def redistribute(plan, info, done_through, done_amount, daily_max=None):
    """done_through 날짜까지 done_amount 만큼만 했을 때 남은 일정을 다시 편다.
    버퍼일부터 채우고, 그래도 모자라면 남은 학습일에 균등 추가한다."""
    days = info["days"]
    planned_so_far = sum(v for d, v in plan.items() if d <= done_through)
    behind = planned_so_far - done_amount
    if behind <= 0:
        return dict(plan), {"behind": 0, "used_buffer": 0, "added_per_day": 0, "warn": []}

    new = dict(plan)
    rest = [d for d in days if d > done_through]
    buffers = [d for d in rest if plan[d] == 0]
    actives = [d for d in rest if plan[d] > 0]

    left = behind
    used_buffer = 0
    # 1) 버퍼일에 평상시 하루치만큼 채워 넣는다 — 하루 분량이 안 늘어난다
    unit = info["base"] if info["base"] > 0 else 1
    for d in buffers:
        if left <= 0:
            break
        put = min(unit, left)
        new[d] = put
        left -= put
        used_buffer += 1

    warn, added = [], 0
    # 2) 그래도 남으면 남은 학습일에 균등 추가 → 하루 분량이 늘어난다
    if left > 0:
        targets = actives + [d for d in buffers if new[d] > 0]
        if not targets:
            warn.append("남은 날이 없다 — 목표일을 늘리는 수밖에 없다")
        else:
            added = math.ceil(left / len(targets))
            for i, d in enumerate(targets):
                give = min(added, left)
                new[d] += give
                left -= give
                if left <= 0:
                    break
            peak = max(new[d] for d in rest) if rest else 0
            if daily_max is not None and peak > daily_max:
                warn.append(f"재분배 후 하루 {peak} — 상한 {daily_max} 초과, 목표일 연장 제안 필요")

    return new, {"behind": behind, "used_buffer": used_buffer,
                 "added_per_day": added, "warn": warn}


# ────────────────────────────────────────────────
def check(name, cond, detail=""):
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}")
    if not cond and detail:
        print(f"         → {detail}")
    return cond


if __name__ == "__main__":
    WD = {0, 1, 2, 3, 4}
    ok = True

    print("=" * 72)
    print("1. 기본 케이스 — 정석 480페이지, 9/9 ~ 12/31, 평일")
    print("=" * 72)
    plan, info = allocate(480, date(2026, 9, 9), date(2026, 12, 31), WD, daily_max=10)
    print(f"  학습 가능일 {info['D']}일 · 버퍼 {info['B']}일 · 배분일 {info['N']}일")
    print(f"  하루 {info['base']}페이지 (앞 {info['R']}일은 +1) · 최대 {info['peak']}페이지")
    ok &= check("배분 총합이 총분량과 정확히 일치", sum(plan.values()) == 480,
                f"합계 {sum(plan.values())}")
    ok &= check("버퍼일 수가 계산과 일치",
                sum(1 for v in plan.values() if v == 0) == info["B"],
                f"0인 날 {sum(1 for v in plan.values() if v == 0)}일 / 버퍼 {info['B']}일")
    ok &= check("경고 없음", not info["warn"], str(info["warn"]))

    print("\n" + "=" * 72)
    print("2. 합계 정확성 — 무작위 200조합")
    print("=" * 72)
    import random
    rng = random.Random(3)
    bad = []
    for _ in range(200):
        total = rng.randint(10, 5000)
        span = rng.randint(20, 400)
        s = date(2026, 1, 1) + timedelta(days=rng.randint(0, 300))
        wd = set(rng.sample(range(7), rng.randint(1, 7)))
        try:
            p, i = allocate(total, s, s + timedelta(days=span), wd)
            if sum(p.values()) != total:
                bad.append((total, span, sorted(wd), sum(p.values())))
        except PlanError:
            pass
    ok &= check("200조합 모두 합계 일치", not bad, str(bad[:3]))

    print("\n" + "=" * 72)
    print("3. 엣지 케이스")
    print("=" * 72)
    try:
        allocate(100, date(2026, 9, 7), date(2026, 9, 11), {5, 6})
        ok &= check("학습 가능일 0일이면 막는다", False, "예외가 안 났다")
    except PlanError as e:
        ok &= check("학습 가능일 0일이면 막는다", True)
        print(f"         메시지: {e}")

    try:
        allocate(100, date(2026, 9, 7), date(2026, 9, 7), WD)
        ok &= check("학습일 1일이면 막는다 (버퍼가 전부 먹음)", False, "예외가 안 났다")
    except PlanError as e:
        ok &= check("학습일 1일이면 막는다 (버퍼가 전부 먹음)", True)
        print(f"         메시지: {e}")

    p, i = allocate(5, date(2026, 9, 7), date(2026, 12, 31), WD)
    zero_days = sum(1 for d in i["days"][:i["N"]] if p[d] == 0)
    ok &= check("총분량이 날짜보다 적으면 경고가 뜬다", bool(i["warn"]), str(i["warn"]))
    print(f"         → 분량 0인 학습일이 {zero_days}일 생긴다 (아래 4번 참고)")

    p, i = allocate(2000, date(2026, 9, 9), date(2026, 10, 9), WD, daily_max=50)
    ok &= check("하루 상한 초과를 잡아낸다", any("상한" in w for w in i["warn"]),
                str(i["warn"]))
    print(f"         → 하루 {i['peak']}, 상한 50")

    print("\n" + "=" * 72)
    print("4. 수정 ② 검증 — 분량 0인 날은 휴식일로 빠지는가")
    print("=" * 72)
    p, i = allocate(5, date(2026, 9, 7), date(2026, 12, 31), WD)
    study = [d for d in i["days"] if p[d] > 0]
    ok &= check("분량 0인 날이 전부 휴식일로 분류된다",
                len(study) + len(i["rest_days"]) == i["D"]
                and all(p[d] == 0 for d in i["rest_days"]),
                f"학습 {len(study)}일 + 휴식 {len(i['rest_days'])}일 / 전체 {i['D']}일")
    print(f"         → 실제로 공부하는 날 {len(study)}일, 나머지 {len(i['rest_days'])}일은 휴식일")
    print("         → 이 날들은 ○△✕ 판정에 안 들어가므로 자동 ✕가 안 찍힌다")

    print("\n" + "=" * 72)
    print("5. 재분배 — 3일 밀렸을 때 하루 분량이 유지되는가")
    print("=" * 72)
    plan, info = allocate(480, date(2026, 9, 9), date(2026, 12, 31), WD, daily_max=10)
    days = info["days"]
    cut = days[9]                      # 10일째까지
    planned = sum(plan[d] for d in days[:10])
    done = planned - plan[days[7]] - plan[days[8]] - plan[days[9]]   # 3일치 안 함
    new, r = redistribute(plan, info, cut, done, daily_max=10)
    print(f"  밀린 분량 {r['behind']}페이지 · 버퍼일 {r['used_buffer']}일 사용 "
          f"· 하루 추가 {r['added_per_day']}페이지")
    peak_after = max(new[d] for d in days if d > cut)
    ok &= check("버퍼로 흡수돼 하루 분량이 그대로", peak_after == info["peak"],
                f"이전 {info['peak']} → 이후 {peak_after}")
    remain = sum(new[d] for d in days if d > cut)
    ok &= check("남은 계획 합 = 총분량 − 실제 완료량", remain == 480 - done,
                f"남은 계획 {remain}, 기대 {480 - done}")

    print("\n" + "=" * 72)
    print("6. 재분배 — 버퍼를 다 쓸 만큼 크게 밀렸을 때")
    print("=" * 72)
    new2, r2 = redistribute(plan, info, days[39], 0, daily_max=10)   # 40일간 아무것도 안 함
    peak2 = max(new2[d] for d in days if d > days[39])
    print(f"  밀린 분량 {r2['behind']}페이지 · 버퍼일 {r2['used_buffer']}일 사용 "
          f"· 하루 추가 {r2['added_per_day']}페이지 → 하루 {peak2}페이지")
    ok &= check("상한 초과를 경고한다", any("초과" in w for w in r2["warn"]), str(r2["warn"]))
    remain2 = sum(new2[d] for d in days if d > days[39])
    ok &= check("남은 계획 합 = 총분량 − 실제 완료량(0)", remain2 == 480,
                f"남은 계획 {remain2}")

    print("\n" + "=" * 72)
    print("7. 수정 ① 검증 — 버퍼일이 기간 전체에 흩어져 있는가")
    print("=" * 72)
    zeros = [d for d in days if plan[d] == 0]
    gaps = [(zeros[k + 1] - zeros[k]).days for k in range(len(zeros) - 1)]
    print(f"  버퍼일 {len(zeros)}일: {zeros[0]} ~ {zeros[-1]}")
    print(f"  전체 기간: {days[0]} ~ {days[-1]}")
    print(f"  버퍼 사이 간격: 최소 {min(gaps)}일 · 최대 {max(gaps)}일 · 평균 {sum(gaps)/len(gaps):.1f}일")
    first_ratio = (zeros[0] - days[0]).days / (days[-1] - days[0]).days
    last_ratio = (zeros[-1] - days[0]).days / (days[-1] - days[0]).days
    ok &= check("첫 버퍼가 기간 앞부분(25% 이내)에 있다", first_ratio <= 0.25,
                f"{first_ratio:.0%} 지점")
    ok &= check("마지막 버퍼가 기간 뒷부분(75% 이후)에 있다", last_ratio >= 0.75,
                f"{last_ratio:.0%} 지점")
    ok &= check("버퍼가 한 달 넘게 비지 않는다", max(gaps) <= 31, f"최대 간격 {max(gaps)}일")
    print("         → 밀린 몫을 그 주 안에서 흡수할 수 있다")

    print("\n" + "=" * 72)
    print("모두 통과" if ok else "실패 있음 — 위 FAIL 확인")
    print("=" * 72)
