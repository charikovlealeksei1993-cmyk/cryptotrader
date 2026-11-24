#!/usr/bin/env python3
"""Visual exploratory test of fixed, early-estimated 1m BTC trajectories.
No lookahead, wave classification, or trading signals. Illustrative windows only.\nTriggered from the cached BTC minute history in GitHub Actions.
"""
import csv, json, math
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

out=Path("wave-visual-results");out.mkdir(exist_ok=True)
rows=[]
with open("annual-replay.csv",newline="") as f:
    for row in csv.reader(f):
        if len(row)<6:continue
        try:rows.append((int(row[0]),float(row[1]),float(row[2]),float(row[3]),float(row[4])))
        except ValueError:continue
# Spread the examples over the history; not cherry-picked for success.
window=120; lead=20
starts=[int((len(rows)-window)*f) for f in (0.12,0.34,0.58,0.82)]
reports=[]
for n,start in enumerate(starts,1):
    seg=rows[start:start+window]
    if len(seg)!=window or any(seg[i][0]-seg[i-1][0]!=60000 for i in range(1,len(seg))):continue
    price=[r[4] for r in seg]
    # Fit only first 20 close prices, never the later 100.
    y=[100*(p/price[0]-1) for p in price]
    xm=(lead-1)/2;ym=sum(y[:lead])/lead
    slope=sum((i-xm)*(y[i]-ym) for i in range(lead))/sum((i-xm)**2 for i in range(lead))
    intercept=ym-slope*xm
    axis=[intercept+slope*i for i in range(window)]
    residual=[y[i]-axis[i] for i in range(lead)]
    band=max(0.02,(sum(x*x for x in residual)/lead)**.5*2)
    # Mark threshold departures and subsequent returns; labels describe the fixed fit only.
    departures=[];returns=[];outside=False
    for i in range(lead,window):
        isout=abs(y[i]-axis[i])>band
        if isout and not outside:departures.append(i)
        if outside and not isout:returns.append(i)
        outside=isout
    fig,ax=plt.subplots(figsize=(12,5))
    ax.plot(range(window),y,color="#b8c4d5",linewidth=1.4,label="BTC close (% from first candle)")
    ax.plot(range(window),axis,color="#23ba70",linewidth=2,label="Fixed axis (first 20 min)")
    ax.fill_between(range(window),[a-band for a in axis],[a+band for a in axis],color="#f1b82d",alpha=.17,label="Initial residual band")
    ax.axvline(lead-1,color="white",linestyle=":",alpha=.75,label="Slope fixed here")
    ax.scatter(departures,[y[i] for i in departures],c="#e75a5a",s=24,zorder=4,label="Departure")
    ax.scatter(returns,[y[i] for i in returns],c="#4c9af1",s=24,zorder=4,label="Return")
    ax.set(xlabel="Minutes from start",ylabel="Price change, %",title=f"BTC 1m / example {n} / slope {slope:.4f}% per minute")
    ax.set_facecolor("#111927");fig.patch.set_facecolor("#111927")
    ax.tick_params(colors="white");ax.xaxis.label.set_color("white");ax.yaxis.label.set_color("white");ax.title.set_color("white")
    for sp in ax.spines.values():sp.set_color("#657083")
    ax.grid(alpha=.15);ax.legend(fontsize=8,loc="best");fig.tight_layout()
    fig.savefig(out/f"wave-example-{n}.png",dpi=140);plt.close(fig)
    reports.append({"example":n,"startTimestampMs":seg[0][0],"slopePctPerMinute":round(slope,6),"bandPct":round(band,5),"departures":len(departures),"returns":len(returns)})
(out/"summary.json").write_text(json.dumps({"scope":"Illustrative fixed-slope visualizations, not validated wave detection.","leadMinutes":lead,"windowMinutes":window,"examples":reports},indent=2))
print(json.dumps(reports))
