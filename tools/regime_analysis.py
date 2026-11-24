"""Segment actual BTCUSDT detector replay by PRIOR 24-hour market direction.

Classification is known at entry: trailing 24h close-to-close return.
>= +2% rising, <= -2% falling, otherwise sideways. The first 24 hours
are excluded (insufficient lookback). Signals use the original 2% SL / 5% TP
replay outcome; no lookahead in regime labels. Overlapping $100 trades and
zero fees remain limitations of the historical replay.
"""
import bisect
import csv
from collections import defaultdict

HISTORY = "replay-input.csv"
SIGNALS = "signal-results.csv"
OUTPUT = "regime-results.csv"
LOOKBACK_MS = 24 * 60 * 60 * 1000
THRESHOLD_PCT = 2.0

def main():
    times, closes = [], []
    with open(HISTORY, newline="") as f:
        for line in f:
            parts = line.split(",", 6)
            times.append(int(parts[0]))
            closes.append(float(parts[4]))
    # [trades, tp, sl, open, pnl]
    stats = defaultdict(lambda: [0, 0, 0, 0, 0.0])
    skipped = 0
    with open(SIGNALS, newline="") as f:
        for row in csv.DictReader(f):
            t = int(row["time"])
            prior = bisect.bisect_right(times, t - LOOKBACK_MS) - 1
            if prior < 0:
                skipped += 1
                continue
            current = bisect.bisect_right(times, t) - 1
            if current < 0:
                skipped += 1
                continue
            # Use PREVIOUS fully closed minute, not a candle still forming at entry.
            current = max(prior, current - 1)
            change = 100 * (closes[current] / closes[prior] - 1)
            regime = "rising" if change >= THRESHOLD_PCT else (
                "falling" if change <= -THRESHOLD_PCT else "sideways"
            )
            key = (regime, row["tf"], row["side"], row["strength"])
            s = stats[key]
            s[0] += 1
            s[1] += int(row["status"] == "TP")
            s[2] += int(row["status"] == "SL")
            s[3] += int(row["status"] not in ("TP", "SL"))
            s[4] += float(row["returnPct"])
    with open(OUTPUT, "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["regime", "tf", "side", "strength", "trades",
                         "tp", "sl", "open", "win_rate_pct",
                         "pnl_usd_at_100_per_trade", "expectancy_pct"])
        for (regime, tf, side, strength), s in sorted(
            stats.items(), key=lambda item: (item[0][0], int(item[0][1]), item[0][2], item[0][3])
        ):
            n, tp, sl, opened, pnl = s
            writer.writerow([regime, tf, side, strength, n, tp, sl, opened,
                             round(100 * tp / (tp + sl), 3) if tp + sl else "",
                             round(pnl, 3), round(pnl / n, 5)])
    print(f"Regime analysis: {len(stats)} groups; {skipped} signals excluded (first 24h).")
    for regime in ("rising", "falling", "sideways"):
        for side in ("long", "short"):
            selected = [s for (r, tf, d, strength), s in stats.items()
                        if r == regime and d == side]
            n = sum(s[0] for s in selected)
            pnl = sum(s[4] for s in selected)
            print(f"{regime:8s} {side:5s}: trades={n:5d} pnl_usd={pnl:+.2f}"
                  f" expectancy_pct={pnl/n:+.4f}" if n else
                  f"{regime:8s} {side:5s}: no trades")

if __name__ == "__main__":
    main()
