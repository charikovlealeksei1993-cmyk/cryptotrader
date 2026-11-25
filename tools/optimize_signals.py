import csv, heapq, itertools, json, random
from datetime import datetime, timezone
rows=sorted(csv.DictReader(open("signal-results.csv")),key=lambda r:int(r["time"]))
data=[(int(r["time"]),r["side"],int(r["exitTime"] or 0),r["status"],float(r["returnPct"] or 0)) for r in rows]
start=data[0][0]
month=365*86400000/12
def evaluate(p):
    nl,ns,gl,gs,cap,per_run=p
    pending=None; count=0; last={}; active=[]; run_count=0
    monthly=[[0.,0.] for _ in range(12)]
    counts=[[0,0] for _ in range(12)]
    for t,s,e,status,ret in data:
        if s!=pending: pending=s; count=0; run_count=0
        count+=1
        if count<(nl if s=='long' else ns): continue
        count=0
        while active and active[0]<=t: heapq.heappop(active)
        if len(active)>=cap or run_count>=per_run or t-last.get(s,-10**18)<(gl if s=='long' else gs)*60000: continue
        last[s]=t;run_count+=1
        heapq.heappush(active,e if status in ("TP","SL","ambiguous") else 10**18)
        if status not in ("TP","SL","ambiguous"): continue
        m=min(11,int((t-start)/month)); k=int(s=="short")
        if m<6 and e+60000>=start+6*month: continue
        monthly[m][k]+=ret-.11;counts[m][k]+=1
    return monthly,counts
def score(m,c):
    a=m[:6]; b=c[:6]
    if min(sum(x[k] for x in b) for k in (0,1))<30:return (-100,0,0)
    return (sum(sum(x)>0 for x in a),min(sum(x[k] for x in a) for k in (0,1)),sum(map(sum,a)))
axes=([1,2,3,4,5,8],[1,2,3,4,5,8],[0,5,15,60,240,1440],[0,5,15,60,240,1440],[1,2,3,5],[1,2,3,5,10])
grid=list(itertools.product(*axes))
random.Random(42).shuffle(grid)
seen={};best=[]
def test(p):
    if p in seen:return
    m,c=evaluate(p);seen[p]=(score(m,c),m,c)
for p in grid[:1200]:test(p)
for round in range(3):
    best=sorted(seen,key=lambda p:seen[p][0],reverse=True)[:8]
    for p in best:
        for i,values in enumerate(axes):
            for v in values:test(p[:i]+(v,)+p[i+1:])
best=sorted(seen,key=lambda p:seen[p][0],reverse=True)
out=[dict(params=p,training_score=seen[p][0],monthly=seen[p][1],counts=seen[p][2]) for p in best]
with open("optimizer-results.json","w") as f:json.dump(out,f,indent=2)
print("OPTIMIZER tested",len(out),"selected on training only",json.dumps(out[0]))
