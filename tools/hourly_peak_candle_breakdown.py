#!/usr/bin/env python3
"""Detailed descriptive statistics of peak candles only; no predictive or trading rules."""
import csv,json,statistics
from pathlib import Path
p=Path("hourly-peaks-results/peaks.csv")
rows=list(csv.DictReader(p.open()))
def v(r,k):return float(r[k]) if r[k] not in ("",None) else None
def pct(n):return round(100*n/len(rows),2) if rows else 0
def group(name,fn):
    selected=[r for r in rows if fn(r)]
    return {"count":len(selected),"percent":pct(len(selected))}
def summary(vals):
    vals=sorted(x for x in vals if x is not None)
    if not vals:return None
    return {"min":round(vals[0],3),"p10":round(vals[int((len(vals)-1)*.1)],3),
            "p25":round(vals[int((len(vals)-1)*.25)],3),"median":round(statistics.median(vals),3),
            "p75":round(vals[int((len(vals)-1)*.75)],3),"p90":round(vals[int((len(vals)-1)*.9)],3),
            "max":round(vals[-1],3)}
def color(r):
    x=v(r,"close")-v(r,"open")
    if abs(x)/v(r,"open")<=0.001:return "doji_0.1pct"
    return "green" if x>0 else "red"
upper=lambda r:v(r,"upperWickSharePct")
lower=lambda r:v(r,"lowerWickSharePct")
bodyshare=lambda r:abs(v(r,"close")-v(r,"open"))/(v(r,"high")-v(r,"low"))*100 if v(r,"high")>v(r,"low") else 0
report={"count":len(rows),"scope":"Only OHLCV and peak candle morphology; no preceding/following price, no strategy.",
"upper_wick":{"below25":group("x",lambda r:upper(r)<25),
              "25to50":group("x",lambda r:25<=upper(r)<50),
              "50to75":group("x",lambda r:50<=upper(r)<75),
              "75plus":group("x",lambda r:upper(r)>=75)},
"lower_wick":{"below10":group("x",lambda r:lower(r)<10),"10to25":group("x",lambda r:10<=lower(r)<25),
              "25plus":group("x",lambda r:lower(r)>=25)},
"candle_color":{k:group(k,lambda r,k=k:color(r)==k) for k in ("green","red","doji_0.1pct")},
"body_share":{"below10":group("x",lambda r:bodyshare(r)<10),
              "10to25":group("x",lambda r:10<=bodyshare(r)<25),
              "25to50":group("x",lambda r:25<=bodyshare(r)<50),
              "50plus":group("x",lambda r:bodyshare(r)>=50)},
"combinations":{
 "upper50_and_smallbody25":group("x",lambda r:upper(r)>=50 and bodyshare(r)<25),
 "upper50_and_red":group("x",lambda r:upper(r)>=50 and color(r)=="red"),
 "upper50_and_doji":group("x",lambda r:upper(r)>=50 and color(r)=="doji_0.1pct"),
 "upper50_and_lower25":group("x",lambda r:upper(r)>=50 and lower(r)<25),
 "upper50_and_smallbody25_and_lower25":group("x",lambda r:upper(r)>=50 and bodyshare(r)<25 and lower(r)<25)},
"distribution":{k:summary([v(r,k) for r in rows]) for k in ("high","volume","rangePct","candleChangePct","upperWickSharePct","lowerWickSharePct","closePositionInRangePct")},
"body_share_distribution":summary([bodyshare(r) for r in rows]),
"caveats":["These peaks were selected retrospectively using 12 later hourly bars; not live-identifiable at the same instant.","Counts describe only the selected peaks, not whether a pattern is unique to peaks.","Volume is absolute BTC volume, not volume relative to surrounding hours."]}
out=Path("hourly-peaks-results");out.mkdir(exist_ok=True)
(out/"candle-breakdown.json").write_text(json.dumps(report,ensure_ascii=False,indent=2))
with (out/"candle-breakdown.csv").open("w",newline="") as f:
    w=csv.writer(f);w.writerow(["timeUTC","high","volume","color","upperWickSharePct","lowerWickSharePct","bodySharePct","rangePct"])
    for r in rows:w.writerow([r["timeUTC"],r["high"],r["volume"],color(r),r["upperWickSharePct"],r["lowerWickSharePct"],round(bodyshare(r),3),r["rangePct"]])
print(json.dumps(report,ensure_ascii=False))
