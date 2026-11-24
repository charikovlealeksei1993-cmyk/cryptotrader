"""Sweep the exact SL/TP button values from CryptoTrader without redetecting signals.

Signals are read from the unchanged detector replay. Each signal is an independent
$100-notional trade. Intraminute SL+TP ties are resolved against the position.
"""
import csv
from collections import defaultdict

STOPS = (0.5, 1.0, 2.0, 3.0, 5.0)
TAKES = (1.0, 2.0, 3.0, 5.0, 10.0)
HISTORY = "replay-input.csv"
SIGNALS = "signal-results.csv"
OUTPUT = "parameter-sweep.csv"


def load_history():
    times, highs, lows, closes = [], [], [], []
    with open(HISTORY, newline="") as f:
        for row in f:
            # Split just the first 5 commas; the very large trades field is unused.
            a = row.rstrip("\n").split(",", 6)
            times.append(int(a[0]))
            highs.append(float(a[2]))
            lows.append(float(a[3]))
            closes.append(float(a[4]))
    return times, highs, lows, closes


def first_hits(start, entry, is_long, highs, lows):
    sl_times = [None] * len(STOPS)
    tp_times = [None] * len(TAKES)
    for j in range(start, len(highs)):
        high, low = highs[j], lows[j]
        for k, pct in enumerate(STOPS):
            if sl_times[k] is None and (
                low <= entry * (1 - pct / 100) if is_long
                else high >= entry * (1 + pct / 100)
            ):
                sl_times[k] = j
        for k, pct in enumerate(TAKES):
            if tp_times[k] is None and (
                high >= entry * (1 + pct / 100) if is_long
                else low <= entry * (1 - pct / 100)
            ):
                tp_times[k] = j
        if all(t is not None for t in sl_times + tp_times):
            break
    return sl_times, tp_times


def main():
    times, highs, lows, closes = load_history()
    lookup = {t: i for i, t in enumerate(times)}
    # [count, tp, sl, open, pnl, gross_profit, gross_loss]
    stats = defaultdict(lambda: [0, 0, 0, 0, 0.0, 0.0, 0.0])
    with open(SIGNALS, newline="") as f:
        for row in csv.DictReader(f):
            tf, strength, side = row["tf"], row["strength"], row["side"]
            entry = float(row["entry"])
            start = lookup[int(row["time"])]
            sl_hits, tp_hits = first_hits(start, entry, side == "long", highs, lows)
            for si, sl in enumerate(STOPS):
                for ti, tp in enumerate(TAKES):
                    a, b = sl_hits[si], tp_hits[ti]
                    # If both touch in the same minute, assume the stop was first.
                    stop = a is not None and (b is None or a <= b)
                    take = b is not None and (a is None or b < a)
                    ret = -sl if stop else tp if take else (
                        (closes[-1] / entry - 1) * 100 * (1 if side == "long" else -1)
                    )
                    s = stats[(tf, strength, side, sl, tp)]
                    s[0] += 1
                    s[1] += int(take)
                    s[2] += int(stop)
                    s[3] += int(not take and not stop)
                    s[4] += ret
                    s[5] += max(ret, 0)
                    s[6] += min(ret, 0)
    with open(OUTPUT, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(("tf", "strength", "side", "sl_pct", "tp_pct", "trades",
                    "tp", "sl", "open", "win_rate_pct", "pnl_usd_at_100_per_trade",
                    "expectancy_pct", "profit_factor"))
        for (tf, strength, side, sl, tp), s in sorted(stats.items(), key=lambda x:
                (int(x[0][0]), x[0][1], x[0][2], x[0][3], x[0][4])):
            n, wins, losses, opened, pnl, gross_win, gross_loss = s
            pf = gross_win / -gross_loss if gross_loss else float("inf")
            w.writerow((tf, strength, side, sl, tp, n, wins, losses, opened,
                        round(100 * wins / (wins + losses), 3) if wins + losses else "",
                        round(pnl, 3), round(pnl / n, 5), round(pf, 5)))
    print(f"Parameter sweep: {len(stats)} combinations -> {OUTPUT}")
    print("Baseline check (SL 2%, TP 5%):")
    baseline = defaultdict(float)
    for (tf, strength, side, sl, tp), s in stats.items():
        if sl == 2.0 and tp == 5.0:
            baseline[tf] += s[4]
    for tf in sorted(baseline, key=int):
        print(f"TF={tf} pnl_usd={baseline[tf]:.2f}")
    assert {tf: round(pnl) for tf, pnl in baseline.items()} == {
        "1": -3412, "5": -771, "15": -226, "30": -141, "60": -86
    } if set(baseline) == {"1", "5", "15", "30", "60"} else True


if __name__ == "__main__":
    main()
