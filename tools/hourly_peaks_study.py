#!/usr/bin/env python3
"""Identify significant hourly BTC highs and describe the peak candle itself.
No preceding/following market indicators or trading signals are studied.
"""
import csv,json,statistics
from datetime import datetime,timezone
from pathlib import Path
HOUR=3600000
def dt(t):return datetime.fromtimestamp(t/1000,timezone.utc).strftime("%Y-%m-%d %H:%M")
def main():
    hours=[];bucket=[]
    with open("annual-replay.csv",newline="") as f:
        for row in csv.reader(f):
            t=int(row[0])
            if bucket and t//HOUR!=bucket[0][0]//HOUR:
                if len(bucket)==60 and all(bucket[i][0]-bucket[i-1][0]==60000 for i in range(1,60)):
                    hours.append((bucket[0][0],bucket[0][1],max(x[2] for x in bucket),min(x[3] for x in bucket),bucket[-1][4],sum(x[5] for x in bucket)))
                bucket=[]
            bucket.append((t,*map(float,row[1:6])))
    # A retrospective 12-hour-local peak is a definition of a significant extremum,
    # not a live recognition rule. Neighbouring bars only locate peaks.
    peaks=[]
    for i in range(12,len(hours)-12):
        p=hours[i]
        if any(x[2]>=p[2] for x in hours[i-12:i]):continue
        if any(x[2]>p[2] for x in hours[i+1:i+13]):continue
        rng=p[2]-p[3]
        body=p[4]-p[1]
        upper=p[2]-max(p[1],p[4])
        lower=min(p[1],p[4])-p[3]
        peaks.append({"timeUTC":dt(p[0]),"high":round(p[2],2),
                      "open":round(p[1],2),"low":round(p[3],2),"close":round(p[4],2),
                      "volume":round(p[5],4),
                      "candleChangePct":round(body/p[1]*100,4) if p[1] else None,
                      "rangePct":round(rng/p[1]*100,4) if p[1] else None,
                      "upperWickSharePct":round(upper/rng*100,2) if rng else None,
                      "lowerWickSharePct":round(lower/rng*100,2) if rng else None,
                      "closePositionInRangePct":round((p[4]-p[3])/rng*100,2) if rng else None})
    out=Path("hourly-peaks-results");out.mkdir(exist_ok=True)
    with (out/"peaks.csv").open("w",newline="") as f:
        w=csv.DictWriter(f,fieldnames=["timeUTC","high","open","low","close","volume","candleChangePct","rangePct","upperWickSharePct","lowerWickSharePct","closePositionInRangePct"])
        w.writeheader();w.writerows(peaks)
    summary={"hours":len(hours),"peaks":len(peaks),
             "highestPeak":max(peaks,key=lambda x:x["high"]) if peaks else None,
             "definition":"Local 1h high: greater than highs of previous 12 bars and at least as high as highs of next 12 bars; future bars used ONLY to label historical extrema.",
             "scope":"Only the peak candle's own OHLCV and shape are compared; no before/after features, no SHORT signals.",
             "limitations":["Historical peaks cannot be identified at their exact occurrence without later confirmation using this retrospective definition.",
                            "Cached history has no genuine liquidation, OI or order-book snapshots."]}
    for field in ("volume","candleChangePct","rangePct","upperWickSharePct","lowerWickSharePct","closePositionInRangePct"):
        vals=[p[field] for p in peaks if p[field] is not None]
        if vals:summary[field]={"median":round(statistics.median(vals),3),"min":min(vals),"max":max(vals)}
    (out/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    print(json.dumps(summary,ensure_ascii=False))
if __name__=="__main__":main()
