"""Exploratory SHORT factor study, strictly using candles closed before entry.

Features: prior 60m price change, 60m volume spike vs preceding 6h,
and failure to break the prior 60m high in the last 15m.
Reports train/test separately (chronological split), no optimization claims.
"""
import csv
from collections import defaultdict

HISTORY="replay-input.csv"
SIGNALS="signal-results.csv"
OUTPUT="short-factor-results.csv"
SPLIT=1792886400000  # 2026-10-25 UTC
FEATURES=("price_rise_60m","volume_spike_60m","failed_high_15m","all_three")

def main():
    times, highs, closes, volumes = [], [], [], []
    with open(HISTORY) as f:
        for line in f:
            a=line.split(",",6)
            times.append(int(a[0]));highs.append(float(a[2]))
            closes.append(float(a[4]));volumes.append(float(a[5]))
    lookup={t:i for i,t in enumerate(times)}
    prefix=[0.0]
    for v in volumes:prefix.append(prefix[-1]+v)
    # For each (period, tf, factor, present/absent) collect outcomes.
    groups=defaultdict(lambda:[0,0,0,0.0])
    excluded=0
    with open(SIGNALS,newline="") as f:
        for row in csv.DictReader(f):
            if row["side"]!="short":continue
            i=lookup[int(row["time"])]
            if i<360:
                excluded+=1;continue
            # Use completed minutes only: indices < i.
            rise=closes[i-1]/closes[i-61]-1 >= .005
            recent=(prefix[i]-prefix[i-60])/60
            older=(prefix[i-60]-prefix[i-360])/300
            spike=older>0 and recent>=1.5*older
            # Previous 60m high established before last 15m;
            # failure means last 15m high did not exceed that earlier high.
            earlier_high=max(highs[i-60:i-15])
            recent_high=max(highs[i-15:i])
            failure=recent_high<=earlier_high
            flags=(rise,spike,failure,rise and spike and failure)
            period="train" if times[i]<SPLIT else "test"
            for feature,flag in zip(FEATURES,flags):
                s=groups[(period,row["tf"],feature,"yes" if flag else "no")]
                s[0]+=1;s[1]+=row["status"]=="TP";s[2]+=row["status"]=="SL"
                s[3]+=float(row["returnPct"])
    with open(OUTPUT,"w",newline="") as f:
        w=csv.writer(f)
        w.writerow(["period","tf","factor","condition","trades","tp","sl","win_rate_pct","pnl_usd_at_100_per_trade","expectancy_pct"])
        for (period,tf,feature,flag),(n,tp,sl,pnl) in sorted(groups.items()):
            w.writerow([period,tf,feature,flag,n,tp,sl,
                        round(100*tp/(tp+sl),2) if tp+sl else "",
                        round(pnl,2),round(pnl/n,4)])
    print(f"SHORT factor analysis: {len(groups)} groups; excluded={excluded}; split=2026-10-25 UTC")
    for period in ("train","test"):
        for feature in FEATURES:
            for flag in ("yes","no"):
                selected=[s for (p,tf,k,v),s in groups.items() if (p,k,v)==(period,feature,flag)]
                n=sum(s[0] for s in selected);pnl=sum(s[3] for s in selected)
                print(f"{period:5s} {feature:18s} {flag:3s} n={n:5d} avg={pnl/n:+.4f}% pnl={pnl:+.2f}" if n else f"{period} {feature} {flag}: none")
    print("Exploratory only: overlapping trades, zero fees, and multiple correlated timeframes; test period is not independent of research choices.")

if __name__=="__main__":
    main()
