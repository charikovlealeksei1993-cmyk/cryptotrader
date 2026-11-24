#!/usr/bin/env python3
"""Experimental SHORT absorption proxy on existing 30m signals.
IMPORTANT: annual flow_delta_proxy is NOT actual buyer-initiated trade delta.
All context bars must close before the candidate entry timestamp.
"""
import csv
import json
from collections import deque
from pathlib import Path
from capital_replay import simulate

def main():
    signals=list(csv.DictReader(open("signal-results.csv",newline="")))
    candidates=[r for r in signals if r["tf"]=="30" and r["side"]=="short"]
    candidates.sort(key=lambda r:int(r["time"]))
    accepted=[]
    history=deque(maxlen=45)
    index=0
    with open("annual-replay.csv",newline="") as f:
        source=csv.reader(f)
        row=next(source,None)
        def readrow(a):
            return (int(a[0]),float(a[1]),float(a[2]),float(a[3]),float(a[4]),float(a[5]),float(a[6]))
        nextbar=readrow(row) if row else None
        for candidate in candidates:
            entry=int(candidate["time"])
            while nextbar and nextbar[0]+60000<=entry:
                history.append(nextbar)
                row=next(source,None)
                nextbar=readrow(row) if row else None
            # 30 minute context: strong buying proxy in the last 10m,
            # but price fails to extend above its prior 20m high.
            if len(history)<35:continue
            bars=list(history)
            recent=bars[-10:]
            previous=bars[-30:-10]
            avgvol=sum(x[5] for x in previous)/len(previous)
            buying=sum(x[6] for x in recent)
            buybars=sum(x[6]>0 for x in recent)
            recentvol=sum(x[5] for x in recent)/len(recent)
            priorhigh=max(x[2] for x in previous)
            recenthigh=max(x[2] for x in recent)
            # No lookahead: entry is AFTER all context candles.
            # Conservative, simple predeclared conditions; no parameter optimization.
            if (buying>0 and buybars>=6 and recentvol>=avgvol*1.2
                and recenthigh>=priorhigh and recent[-1][4]<priorhigh):
                accepted.append(candidate)
    baseline,baseline_trades=simulate(candidates,30,{"short"})
    filtered,filtered_trades=simulate(accepted,30,{"short"})
    out=Path("absorption-results");out.mkdir(exist_ok=True)
    report={"baseline":baseline,"absorption_proxy":filtered,
            "candidateCount":len(candidates),"acceptedCandidates":len(accepted),
            "definition":"Last 10 M1 bars: positive summed candle-derived flow proxy, >=6 positive proxy bars, volume >=1.2x previous 20m, high breaks previous 20m high but last close falls below that high.",
            "warnings":["This is NOT real aggressive buying; annual flow_delta_proxy is candle-derived.",
                        "Same historical sample as prior experiments; no out-of-sample validation.",
                        "No true OI or liquidations in cached annual data.",
                        "Signals and exits inherit replay timing assumptions."]}
    (out/"report.json").write_text(json.dumps(report,indent=2,ensure_ascii=False))
    for name,rows in [("baseline",baseline_trades),("absorption_proxy",filtered_trades)]:
        with (out/f"{name}_trades.csv").open("w",newline="") as f:
            w=csv.DictWriter(f,fieldnames=["entryTime","exitTime","tf","side","status","qty","entry","exit","pnlUSDT","balanceUSDT"])
            w.writeheader();w.writerows(rows)
    print(json.dumps(report,ensure_ascii=False))
if __name__=="__main__":main()
