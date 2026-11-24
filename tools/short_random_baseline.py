"""Matched random SHORT baseline for the actual historical detector replay.

For every detector SHORT, choose a random eligible entry in the same timeframe,
trailing-24h regime and UTC hour, excluding the signal's exact minute. Repeat
with independent seeds. Each entry is at the next timeframe bar open.
SL=2%, TP=5%, 1m high/low execution; SL wins intraminute ties.
Results are independent overlapping $100 notionals with no fees.
"""
import bisect
import csv
import random
from collections import defaultdict

HISTORY = "replay-input.csv"
SIGNALS = "signal-results.csv"
OUT = "short-random-baseline.csv"
SEEDS = (11, 29, 47)
LOOKBACK = 1440
ENTRY_END = 1794182400000  # 2026-11-09 00:00 UTC
TFS = (1, 5, 15, 30, 60)

def regime(closes, i):
    # Last completed minute before entry, 24h before that.
    if i <= LOOKBACK:
        return None
    change = 100 * (closes[i-1] / closes[i-LOOKBACK] - 1)
    return "rising" if change >= 2 else "falling" if change <= -2 else "sideways"

def result(i, entry, highs, lows, final_close):
    stop, take = entry * 1.02, entry * .95
    for j in range(i, len(highs)):
        if highs[j] >= stop:
            return -2.0, "SL"
        if lows[j] <= take:
            return 5.0, "TP"
    return (entry / final_close - 1) * 100, "open"

def main():
    times, opens, highs, lows, closes = [], [], [], [], []
    with open(HISTORY) as f:
        for line in f:
            a = line.split(",", 6)
            times.append(int(a[0]))
            opens.append(float(a[1]))
            highs.append(float(a[2]))
            lows.append(float(a[3]))
            closes.append(float(a[4]))
    index = {t:i for i,t in enumerate(times)}
    pools = defaultdict(list)
    # Matching includes the same trailing regime, timeframe and UTC hour.
    for i,t in enumerate(times):
        if t >= ENTRY_END: break
        r = regime(closes, i)
        if r is None: continue
        hour = (t // 3600000) % 24
        for tf in TFS:
            if i % tf == 0 and i >= tf*8:
                pools[(str(tf),r,hour)].append(i)
    actual = []
    with open(SIGNALS,newline="") as f:
        for row in csv.DictReader(f):
            if row["side"] != "short": continue
            i = index[int(row["time"])]
            r = regime(closes,i)
            if r is None: continue
            actual.append((row["tf"],r,(times[i]//3600000)%24,i,float(row["returnPct"]),row["status"]))
    # Fairness: compare identical signal counts and regime/time-of-day distributions.
    stats = defaultdict(lambda: [0,0.0,0,0])
    for tf,r,hour,i,ret,status in actual:
        key = ("detector",tf,r)
        s=stats[key];s[0]+=1;s[1]+=ret;s[2]+=status=="TP";s[3]+=status=="SL"
    for seed in SEEDS:
        rng = random.Random(seed)
        for tf,r,hour,i,_,_ in actual:
            options = pools[(tf,r,hour)]
            if len(options) < 2: continue
            j = options[rng.randrange(len(options))]
            if j == i:
                j = options[(options.index(j)+1)%len(options)]
            ret,status = result(j,opens[j],highs,lows,closes[-1])
            s=stats[(f"random_{seed}",tf,r)]
            s[0]+=1;s[1]+=ret;s[2]+=status=="TP";s[3]+=status=="SL"
    with open(OUT,"w",newline="") as f:
        w=csv.writer(f)
        w.writerow(["method","tf","regime","trades","tp","sl","win_rate_pct","pnl_usd_at_100_per_trade","expectancy_pct"])
        for (method,tf,r), (n,pnl,tp,sl) in sorted(stats.items()):
            w.writerow([method,tf,r,n,tp,sl,round(100*tp/(tp+sl),3) if tp+sl else "",round(pnl,3),round(pnl/n,5) if n else ""])
    for r in ("rising","falling","sideways"):
        for method in ("detector",)+tuple(f"random_{s}" for s in SEEDS):
            selected=[v for (m,tf,rr),v in stats.items() if m==method and rr==r]
            n=sum(x[0] for x in selected); pnl=sum(x[1] for x in selected)
            print(f"{r:8s} {method:10s} n={n:5d} pnl={pnl:+.2f} avg={pnl/n:+.4f}%" if n else f"{r} {method}: no entries")
    print("Caveat: in-sample matching; no trading costs, overlapping positions, or capital constraints.")

if __name__ == "__main__":
    main()
