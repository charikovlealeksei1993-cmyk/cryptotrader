#!/usr/bin/env python3
"""Descriptive study of BTC 1h swing highs, not a trading backtest."""
import csv,json,statistics
from collections import deque
from datetime import datetime,timezone
from pathlib import Path
HOUR=3600000
def dt(t):return datetime.fromtimestamp(t/1000,timezone.utc).strftime("%Y-%m-%d %H:%M")
def main():
    hours=[];bucket=[]
    with open("annual-replay.csv",newline="") as f:
        for row in csv.reader(f):
            t=int(row[0])
            if bucket and (t//HOUR)!=(bucket[0][0]//HOUR):
                if len(bucket)==60 and all(bucket[i][0]-bucket[i-1][0]==60000 for i in range(1,60)):
                    hours.append((bucket[0][0],bucket[0][1],max(x[2] for x in bucket),min(x[3] for x in bucket),bucket[-1][4],sum(x[5] for x in bucket)))
                bucket=[]
            bucket.append((t,*map(float,row[1:6])))
    # 12-hour confirmed swing peaks, separated by at least 12h.
    peaks=[]
    for i in range(36,len(hours)-12):
        window=hours[i-12:i+13]
        if hours[i][2]!=max(x[2] for x in window):continue
        if any(x[2]>=hours[i][2] for x in hours[i-12:i]):continue
        if any(x[2]>hours[i][2] for x in hours[i+1:i+13]):continue
        before=hours[i-24:i]
        priorhigh=max(x[2] for x in hours[i-12:i])
        avgvol=sum(x[5] for x in before)/24
        recentvol=sum(x[5] for x in hours[i-3:i])/3
        recentclose=hours[i-1][4]
        p=hours[i]
        features={"timeUTC":dt(p[0]),"high":round(p[2],2),
                  "volumeRatio3hVs24h":round(recentvol/avgvol,3) if avgvol else None,
                  "rise6hPct":round((recentclose/hours[i-7][4]-1)*100,3),
                  "rise24hPct":round((recentclose/hours[i-25][4]-1)*100,3),
                  "prior12hHigh":round(priorhigh,2),
                  "peakCandleBodyPct":round((p[4]-p[1])/p[1]*100,3),
                  "peakUpperWickPct":round((p[2]-max(p[1],p[4]))/p[1]*100,3),
                  "future12hDropPct":round((p[2]-min(x[3] for x in hours[i+1:i+13]))/p[2]*100,3)}
        peaks.append(features)
    out=Path("hourly-peaks-results");out.mkdir(exist_ok=True)
    with (out/"peaks.csv").open("w",newline="") as f:
        if peaks:
            w=csv.DictWriter(f,fieldnames=list(peaks[0]));w.writeheader();w.writerows(peaks)
    summary={"hours":len(hours),"peaks":len(peaks),"definition":"1h swing high higher than previous 12h and no higher high in following 12h; 12h future is used ONLY to label a peak, never as a predictive input.",
             "caveats":["Features before peak use completed hourly candles only; peak candle is descriptive and not available until its close.","Annual cached data has OHLCV and candle-derived flow proxies; no real OI, order book or liquidation history.","Selection of peaks is retrospective; no claim of predictive edge."]}
    for field in ("volumeRatio3hVs24h","rise6hPct","rise24hPct","peakUpperWickPct","future12hDropPct"):
        v=[p[field] for p in peaks if p[field] is not None]
        if v:summary[field]={"median":round(statistics.median(v),3),"p25":round(sorted(v)[len(v)//4],3),"p75":round(sorted(v)[3*len(v)//4],3)}
    (out/"summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2))
    print(json.dumps(summary,ensure_ascii=False))
if __name__=="__main__":main()
