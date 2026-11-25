import csv, heapq, itertools, json, random
from datetime import datetime, timezone
rows=sorted(csv.DictReader(open("signal-results.csv")),key=lambda r:int(r["time"]))
data=[(int(r["time"]),r["side"],int(r["exitTime"] or 0),r["status"],float(r["returnPct"] or 0)) for r in rows]
# Features at entry use only completed minute candles.
from collections import deque
features={}
ema1=ema2=None
history=deque(maxlen=240)
prev=None
for line in open("annual-replay.csv"):
    a=line.strip().split(",")
    t=int(a[0]); close=float(a[4])
    if prev is not None and t-prev!=60000:
        history.clear();ema1=ema2=None
    if len(history)==240:
        trend=(ema1/ema2-1)*100
        vol=(max(history)/min(history)-1)*100
        features[t]=(trend,vol)
    ema1=close if ema1 is None else ema1+(close-ema1)*2/61
    ema2=close if ema2 is None else ema2+(close-ema2)*2/241
    history.append(close);prev=t
data=[(*r,*features.get(r[0],(float("nan"),float("nan")))) for r in data]
start=data[0][0]
names={int(r["time"]):r["pattern"].split(" · ")[0] for r in rows}
allowed={"Утренняя звезда","Пинцет: дно","Медвежье харами","Падающая звезда"}
assert allowed <= set(names.values()), "Allowed pattern names mismatch"
excluded=set(names.values())-allowed
assert excluded <= set(names.values()), "Pattern names mismatch"

month=365*86400000/12
def evaluate(p, exclude=False):
    nl,ns,gl,gs,cap,per_run,fl,fs,vmin,vmax=p
    pending=None; count=0; last={}; active=[]; run_count=0
    monthly=[[0.,0.] for _ in range(12)]
    counts=[[0,0] for _ in range(12)]
    for t,s,e,status,ret,trend,vol in data:
        if exclude and names[t] in excluded:continue
        if s!=pending: pending=s; count=0; run_count=0
        count+=1
        if count<(nl if s=='long' else ns): continue
        count=0
        f=fl if s=="long" else fs
        aligned=trend if s=="long" else -trend
        if not (vmin<=vol<=vmax): continue
        if f==1 and not aligned>0.15: continue
        if f==2 and not aligned<-.15: continue
        if f==3 and not abs(trend)<=.15: continue
        while active and active[0]<=t: heapq.heappop(active)
        if len(active)>=cap or run_count>=per_run or t-last.get(s,-10**18)<(gl if s=='long' else gs)*60000: continue
        last[s]=t;run_count+=1
        heapq.heappush(active,e+60000 if status in ("TP","SL","ambiguous") else 10**18)
        if status not in ("TP","SL","ambiguous"): continue
        m=min(11,int((t-start)/month)); k=int(s=="short")
        if m<6 and e+60000>=start+6*month: continue
        monthly[m][k]+=ret-.11;counts[m][k]+=1
    return monthly,counts
def score(m,c):
    a=m[:6]; b=c[:6]
    if min(sum(x[k] for x in b) for k in (0,1))<30:return (-100,0,0)
    return (sum(sum(x)>0 for x in a),min(sum(x[k] for x in a) for k in (0,1)),sum(map(sum,a)))
p=(2,3,15,15,5,10,2,0,1,2)
out=[]
for exclude,nl,ns in [(False,2,3)]+[(True,l,s) for l in (1,2,3) for s in (1,2,3)]:
    q=(nl,ns,*p[2:])
    m,c=evaluate(q,exclude)
    out.append(dict(params=q,excluded=sorted(excluded) if exclude else [],monthly=m,counts=c,training_score=score(m,c)))
with open("optimizer-results.json","w") as f:json.dump(out,f,indent=2,ensure_ascii=False)
print("FOUR PATTERN CONTROL",json.dumps(out,ensure_ascii=False))
