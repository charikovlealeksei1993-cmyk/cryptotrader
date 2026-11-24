"""Independent minute-by-minute SHORT absorption hypothesis, exploratory."""
import csv
from collections import defaultdict
from datetime import datetime, timezone
SPLIT=1792886400000
END=int(datetime(2026,11,9,tzinfo=timezone.utc).timestamp()*1000)
H=1440
t=[]; o=[]; c=[]; v=[]
with open("replay-input.csv") as f:
    for line in f:
        a=line.split(",",6)
        t.append(int(a[0])); o.append(float(a[1])); c.append(float(a[4])); v.append(float(a[5]))
p=[0.]
for x in v:p.append(p[-1]+x)
groups=defaultdict(lambda:[0,0.,0.])
# Fixed 30m impulse, split into two 15m segments; compare all eligible
# impulse entries with and without increasing volume / decelerating price.
# One entry per 24h per cohort, fixed 24h exit, no overlapping trades within cohort.
last={}
for i in range(360,len(t)-H):
    if t[i]>=END:break
    a=c[i-16]/c[i-31]-1
    b=c[i-1]/c[i-16]-1
    recent=(p[i]-p[i-30])/30
    base=(p[i-30]-p[i-360])/330
    if base<=0 or a+b+a*b<.003 or recent<1.5*base or a<=0:continue
    rising_volume=p[i]-p[i-15]>p[i-15]-p[i-30]
    slowing=b<a*.5
    label="absorption_proxy" if rising_volume and slowing else "other_impulse"
    period="train" if t[i]<SPLIT else "test"
    if period=="train" and t[i+H]>=SPLIT:continue
    for cohort in (label,"all_impulses"):
        key=(period,cohort)
        if i<last.get(key,0):continue
        last[key]=i+H
        ret=100*(1-o[i+H]/o[i])-.11
        g=groups[key];g[0]+=1;g[1]+=ret;g[2]+=ret>0
with open("absorption-results.csv","w",newline="") as f:
    w=csv.writer(f);w.writerow(["period","cohort","trades","mean_net_pct","positive_pct"])
    for (period,cohort),(n,s,win) in sorted(groups.items()):
        row=[period,cohort,n,round(s/n,4),round(100*win/n,2)]
        w.writerow(row);print(row)
print("Exploratory BTC only; unsigned volume proxy, no direct absorption proof; 24h fixed exit, assumed 0.11% fee, no funding/slippage.")
