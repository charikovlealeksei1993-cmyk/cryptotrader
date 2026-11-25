import csv, json, random, heapq
from collections import deque
rows=sorted(csv.DictReader(open("signal-results.csv")),key=lambda r:int(r["time"]))
wanted={int(r["time"]) for r in rows}
features={}
h=deque(maxlen=22)
for line in open("annual-replay.csv"):
    a=line.strip().split(","); t=int(a[0]); o,hi,lo,c=map(float,a[1:5])
    if h and t-h[-1][0]!=60000:h.clear()
    if t in wanted and len(h)>=22:
        _,po,ph,pl,pc=h[-2]; _,co,ch,cl,cc=h[-1]
        _,vo,vh,vl,vc=h[-3]
        rng=max(ph-pl,1e-12); body=abs(pc-po)
        upper=ph-max(po,pc); lower=min(po,pc)-pl
        features[t]=(body/rng,upper/rng,lower/rng,upper/max(body,1e-12),lower/max(body,1e-12),abs(pl-vl)/rng,abs(ph-vh)/rng,abs(cc-co)/max(ch-cl,1e-12),tuple(vc-h[-3-n][4] for n in (2,3,5,10,15)))
    h.append((t,o,hi,lo,c))
start=int(rows[0]["time"]); month=365*86400000/12
data=[]
for r in rows:
    t=int(r["time"])
    if t in features:data.append((t,r["side"],r["pattern"].split(" · ")[0],int(r["exitTime"] or 0),r["status"],float(r["returnPct"] or 0),features[t]))
def evaluate(p):
    wick,doji,tail,tweezer,trend,confirm=p
    monthly=[[0.,0.] for _ in range(12)]; counts=[[0,0] for _ in range(12)]
    train=[[0.,0.] for _ in range(6)]; tc=[[0,0] for _ in range(6)]
    active=[];last={}
    for t,s,name,e,status,ret,f in data:
        b,u,l,ur,lr,dl,dh,cb,dirs=f
        k=int(s=="short")
        if cb<confirm:continue
        if trend and (dirs[trend-1]<=0 if k else dirs[trend-1]>=0):continue
        if name in ("Молот","Падающая звезда") and (ur if k else lr)<wick:continue
        if name.startswith("Доджи") and (b>doji or (u if k else l)<tail):continue
        if name.startswith("Пинцет") and (dh if k else dl)>tweezer:continue
        while active and active[0]<=t:heapq.heappop(active)
        if len(active)>=5 or t-last.get(s,-10**18)<900000:continue
        last[s]=t
        heapq.heappush(active,e+60000)
        m=min(11,int((t-start)/month))
        if status not in ("TP","SL","ambiguous"):continue
        monthly[m][k]+=ret-.11;counts[m][k]+=1
        if m<6 and e+60000<start+6*month:
            train[m][k]+=ret-.11;tc[m][k]+=1
    a=[sum(x[k] for x in train) for k in (0,1)]
    score=(min(a)>0,sum(sum(x)>0 for x in train),min(a),sum(a)) if min(sum(x[k] for x in tc) for k in (0,1))>=15 else (-1,0,0,0)
    return dict(params=p,training_score=score,monthly=monthly,counts=counts)
axes=([2,2.5,3,4],[.03,.05,.07,.1],[.6,.7,.8,.85],[.02,.04,.06,.1],[0,1,2,3,4,5],[0,.2,.35,.5,.7])
rng=random.Random(42);seen={}
def test(p):
    if p not in seen:seen[p]=evaluate(p)
test((2,.1,.6,.1,0,0))
for _ in range(500):test(tuple(rng.choice(a) for a in axes))
for _ in range(3):
    for p in sorted(seen,key=lambda p:seen[p]["training_score"],reverse=True)[:5]:
        for i,a in enumerate(axes):
            for v in a:test(p[:i]+(v,)+p[i+1:])
out=sorted(seen.values(),key=lambda r:r["training_score"],reverse=True)
with open("optimizer-results.json","w") as f:json.dump(out,f,indent=2)
print("PATTERN OPTIMIZER",len(out),json.dumps(out[0]))
