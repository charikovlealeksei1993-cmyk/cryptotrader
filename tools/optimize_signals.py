import csv, heapq, itertools, json, random
from datetime import datetime, timezone
rows=sorted(csv.DictReader(open("signal-results.csv")),key=lambda r:int(r["time"]))
data=[(int(r["time"]),r["side"],int(r["exitTime"] or 0),r["status"],float(r["returnPct"] or 0)) for r in rows]
# Features at entry use only completed minute candles.
from collections import deque
features={}
ct=[]; highs=[]; lows=[]
ema1=ema2=None
history=deque(maxlen=240)
prev=None
for line in open("annual-replay.csv"):
    a=line.strip().split(",")
    t=int(a[0]); close=float(a[4])
    ct.append(t); highs.append(float(a[2])); lows.append(float(a[3]))
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
# Segment tree finds first threshold crossing in O(log N).
size=1
while size<len(ct):size*=2
hi=[-float("inf")]*(2*size);lo=[float("inf")]*(2*size)
hi[size:size+len(ct)]=highs;lo[size:size+len(ct)]=lows
for i in range(size-1,0,-1):
    hi[i]=max(hi[2*i],hi[2*i+1]);lo[i]=min(lo[2*i],lo[2*i+1])
def first(j,upper,lower,node=1,l=0,r=size):
    if r<=j or (hi[node]<upper and lo[node]>lower):return len(ct)
    if r-l==1:return l
    mid=(l+r)//2
    x=first(j,upper,lower,node*2,l,mid)
    return x if x<len(ct) else first(j,upper,lower,node*2+1,mid,r)
idx={t:i for i,t in enumerate(ct)}
ends=[len(ct)]*len(ct)
for i in range(len(ct)-2,-1,-1):
    ends[i]=i+1 if ct[i+1]-ct[i]!=60000 else ends[i+1]
from functools import lru_cache
@lru_cache(maxsize=32)
def outcomes(side,tp,sl):
    out={}
    for row in rows:
        if row["side"]!=side:continue
        t=int(row["time"]); j=idx[t]; entry=float(row["entry"])
        long=side=="long"
        upper=entry*(1+(tp if long else sl)/100)
        lower=entry*(1-(sl if long else tp)/100)
        k=first(j,upper,lower)
        if k>=ends[j]:out[t]=(10**18,"open",0);continue
        stop=lows[k]<=lower if long else highs[k]>=upper
        out[t]=(ct[k],"SL" if stop else "TP",-sl if stop else tp)
    return out
start=data[0][0]
month=365*86400000/12
def evaluate(p):
    nl,ns,gl,gs,cap,per_run,fl,fs,vmin,vmax,tl,sl,ts,ss=p
    exits={'long':outcomes('long',tl,sl),'short':outcomes('short',ts,ss)}
    pending=None; count=0; last={}; active=[]; run_count=0
    monthly=[[0.,0.] for _ in range(12)]
    counts=[[0,0] for _ in range(12)]
    for t,s,e,status,ret,trend,vol in data:
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
        e,status,ret=exits[s][t]
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
axes=([1,2,3,4,5,8],[1,2,3,4,5,8],[0,5,15,60,240,1440],[0,5,15,60,240,1440],[1,2,3,5],[1,2,3,5,10],[0,1,2,3],[0,1,2,3],[0,.5,1,2],[2,4,8,1000],[2,4,7,10],[1,2,3,5],[2,4,7,10],[1,2,3,5])
rng=random.Random(42)
grid=[tuple(rng.choice(a) for a in axes) for _ in range(2500)]
grid.insert(0,(1,1,0,5,3,10,0,0,0,1000,7,3,7,3))
grid.insert(0,(2,3,15,15,5,10,2,0,1,2,7,3,7,3))
seen={};best=[]
def test(p):
    if p in seen or p[8]>=p[9]:return
    m,c=evaluate(p);seen[p]=(score(m,c),m,c)
for p in grid:test(p)
for round in range(3):
    best=sorted(seen,key=lambda p:seen[p][0],reverse=True)[:8]
    for p in best:
        for i,values in enumerate(axes):
            for v in values:test(p[:i]+(v,)+p[i+1:])
best=sorted(seen,key=lambda p:seen[p][0],reverse=True)
out=[dict(params=p,training_score=seen[p][0],monthly=seen[p][1],counts=seen[p][2]) for p in best]
with open("optimizer-results.json","w") as f:json.dump(out,f,indent=2)
print("OPTIMIZER tested",len(out),"selected on training only",json.dumps(out[0]))
