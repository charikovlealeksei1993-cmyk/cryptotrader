import csv,json,datetime as dt,bisect,collections,os
holdout=os.getenv('FOREX_HOLDOUT')=='1'
from pathlib import Path
p=Path('forex-data')
rows=[]; times=[]
for r in csv.DictReader(open(p/'eurusd-research-2020-2022.csv')):
 t=dt.datetime.strptime(r['DateTime'],'%m/%d/%Y %H:%M:%S.%f')
 times.append(t);rows.append((r['SegmentId'],*[float(r[k]) for k in ('BidOpen','BidHigh','BidLow','BidClose','AskOpen','AskHigh','AskLow','AskClose')]))
out=[]
for c in json.load(open(p/'figures/candidates.json')):
 tf=c['tf']; start=dt.datetime.fromisoformat(c['confirmed'])+dt.timedelta(minutes=tf)
 direction=1 if c['pattern']=='double_bottom' else -1
 a=bisect.bisect_left(times,start)
 rec=dict(tf=tf,year=start.year,pattern=c['pattern'],status='gap',pips=None)
 out.append(rec)
 if a>=len(rows) or times[a]!=start:continue
 seg=rows[a][0]; entry=rows[a][5 if direction==1 else 1]
 stop=c['extreme']-direction*c['depth']*.1; target=c['neck']
 if direction*(target-entry)<=0 or direction*(entry-stop)<=0:
  rec['status']='late_or_invalid';continue
 # Reject if neckline already touched after second pivot bar and before entry.
 prev=bisect.bisect_left(times,dt.datetime.fromisoformat(c['points'][2])+dt.timedelta(minutes=tf))
 if any((rows[j][2]>=target if direction==1 else rows[j][3]<=target) for j in range(prev,a)):
  rec['status']='late_or_invalid';continue
 for j in range(a,min(len(rows),a+tf*40)):
  if rows[j][0]!=seg or (j>a and times[j]-times[j-1]!=dt.timedelta(minutes=1)):break
  r=rows[j]; op,low,high,close=(r[1],r[3],r[2],r[4]) if direction==1 else (r[5],r[7],r[6],r[8])
  if direction*(op-stop)<=0:exitprice=op;status='stop'
  elif direction*(op-target)>=0:exitprice=target;status='target'
  elif (low<=stop if direction==1 else high>=stop):exitprice=stop;status='stop'
  elif (high>=target if direction==1 else low<=target):exitprice=target;status='target'
  elif j==a+tf*40-1:exitprice=close;status='timeout'
  else:continue
  rec.update(status=status,pips=direction*(exitprice-entry)*10000);break
report=[]
for tf in sorted(set(r['tf'] for r in out)):
 for year in sorted(set(r['year'] for r in out)):
  v=[r for r in out if r['tf']==tf and r['year']==year];x=[r['pips'] for r in v if r['pips'] is not None]
  report.append(dict(tf=tf,year=year,valid=len(x),wins=sum(z>0 for z in x),net=round(sum(x),2),statuses=dict(collections.Counter(r['status'] for r in v))))
(p/'neckline-trades.json').write_text(json.dumps(out))
(p/'neckline-summary.json').write_text(json.dumps(report,indent=2))
print('NECKLINE SUMMARY',json.dumps(report))
