import csv,json,datetime as dt,bisect,collections
from pathlib import Path
p=Path('forex-data')
rows=[]; times=[]
for r in csv.DictReader(open(p/'eurusd-research-2020-2022.csv')):
 t=dt.datetime.strptime(r['DateTime'],'%m/%d/%Y %H:%M:%S.%f')
 times.append(t);rows.append((r['SegmentId'],*[float(r[k]) for k in ('BidOpen','BidHigh','BidLow','BidClose','AskOpen','AskHigh','AskLow','AskClose')]))
out=[]
for c in json.load(open(p/'figures/candidates.json')):
 tf=c['tf']; step=dt.timedelta(minutes=tf); start=dt.datetime.fromisoformat(c['confirmed'])+step
 direction=1 if c['pattern']=='double_bottom' else -1
 neck=c['neck']; depth=c['depth']; first=bisect.bisect_left(times,start)
 if first>=len(rows) or times[first]!=start:continue
 seg=rows[first][0]; entries={'pivot':first}; broken=False
 for k in range(30):
  t=start+k*step; a=bisect.bisect_left(times,t); b=bisect.bisect_left(times,t+step)
  if b-a!=tf or times[a]!=t or times[b-1]!=t+step-dt.timedelta(minutes=1) or rows[b-1][0]!=seg:break
  close=rows[b-1][4]
  if direction*(close-c['extreme']) < -depth*.25:break
  if not broken and direction*(close-neck)>0:
   entries['break']=b;broken=True
  elif broken and direction*(close-neck)>0 and any(direction*(rows[j][3 if direction==1 else 2]-neck)<=depth*.1 for j in range(a,b)):
   entries['retest']=b;break
 for mode,a in entries.items():
  if a>=len(rows) or rows[a][0]!=seg:continue
  if a!=first and times[a]-times[a-1]!=dt.timedelta(minutes=1):continue
  entry=rows[a][5 if direction==1 else 1]
  stop=c['extreme']-direction*depth*.1
  risk=direction*(entry-stop)
  if risk<=0:continue
  for rr in (1,2,3):
   target=entry+direction*risk*rr; status='timeout'; pnl=None
   for j in range(a,min(len(rows),a+tf*40)):
    if rows[j][0]!=seg or (j>a and times[j]-times[j-1]!=dt.timedelta(minutes=1)):
     status='gap';break
    r=rows[j]; low,high=(r[3],r[2]) if direction==1 else (r[7],r[6])
    hitstop=low<=stop if direction==1 else high>=stop
    hittarget=high>=target if direction==1 else low<=target
    if hitstop:status='stop';pnl=-risk;break
    if hittarget:status='target';pnl=risk*rr;break
    if j==a+tf*40-1:pnl=direction*(r[4 if direction==1 else 8]-entry)
   out.append(dict(tf=tf,year=times[a].year,pattern=c['pattern'],mode=mode,rr=rr,status=status,pips=None if pnl is None else pnl*10000))
groups=collections.defaultdict(list)
for r in out:groups[(r['tf'],r['mode'],r['rr'])].append(r)
summary=[]
for k,v in sorted(groups.items()):
 x=[r['pips'] for r in v if r['pips'] is not None]
 summary.append(dict(tf=k[0],mode=k[1],rr=k[2],valid=len(x),excluded=len(v)-len(x),wins=sum(y>0 for y in x),netPips=round(sum(x),2),meanPips=round(sum(x)/max(1,len(x)),3)))
(p/'figure-trades.json').write_text(json.dumps(out))
(p/'figure-trade-summary.json').write_text(json.dumps(summary,indent=2))
print('FIGURE TRADE SUMMARY',json.dumps(summary))
