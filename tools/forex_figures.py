import csv, datetime as dt, collections, statistics, json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
out=Path('forex-data/figures');out.mkdir(exist_ok=True)
rows=[]
with open('forex-data/eurusd-research-2020-2022.csv') as f:
 for r in csv.DictReader(f):
  t=dt.datetime.strptime(r['DateTime'],'%m/%d/%Y %H:%M:%S.%f')
  rows.append((t,int(r['SegmentId']),float(r['BidOpen']),float(r['BidHigh']),float(r['BidLow']),float(r['BidClose'])))
results=[]
for tf in (5,15,30,60):
 bars=[]
 for (seg,bucket),g in __import__('itertools').groupby(rows,lambda r:(r[1],r[0].replace(hour=r[0].hour if tf<60 else r[0].hour,minute=r[0].minute//tf*tf,second=0,microsecond=0))):
  v=list(g)
  if len(v)<tf*.8:continue
  bars.append((bucket,seg,v[0][2],max(x[3] for x in v),min(x[4] for x in v),v[-1][5]))
 piv=[]; pending=[]; last=-1
 for i in range(24,len(bars)):
  if bars[i][1]!=bars[i-1][1] or bars[i][0]-bars[i-1][0]!=dt.timedelta(minutes=tf):
   piv=[];pending=[];last=i;continue
  if i-last<24:continue
  k=i-3; window=bars[k-3:k+4]
  for typ,col,fn in [('H',3,max),('L',4,min)]:
   if bars[k][col]==fn(x[col] for x in window) and sum(x[col]==bars[k][col] for x in window)==1:
    p=(k,typ,bars[k][col])
    if piv and piv[-1][1]==typ:
     if (p[2]>piv[-1][2])==(typ=='H'):piv[-1]=p
    else:piv.append(p)
    if len(piv)>=3:
     a,b,c=piv[-3:]
     if a[1]==c[1] and 6<=c[0]-a[0]<=80:
      scale=statistics.median(x[3]-x[4] for x in bars[max(last,i-24):i])
      depth=abs(a[2]-b[2])
      prior=bars[max(last,a[0]-12):a[0]]
      trend=bool(prior) and (a[2]-min(x[4] for x in prior)>=3*scale if typ=='H' else max(x[3] for x in prior)-a[2]>=3*scale)
      if scale>0 and trend and depth>=3*scale and abs(a[2]-c[2])<=min(scale,depth*.25):
       pending.append((a,b,c,i))
  keep=[]
  for a,b,c,seen in pending:
   if i-seen>30:continue
   top=a[1]=='H'; close=bars[i][5]
   if (top and close>max(a[2],c[2])+abs(a[2]-b[2])*.25) or (not top and close<min(a[2],c[2])-abs(a[2]-b[2])*.25):continue
   if (top and close<b[2]) or (not top and close>b[2]):
    results.append(dict(tf=tf,time=str(bars[i][0]),pattern='double_top' if top else 'double_bottom',points=[str(bars[x[0]][0]) for x in (a,b,c)],neck=b[2]))
    name=results[-1]['pattern']
    if sum(x['tf']==tf and x['pattern']==name for x in results)<=8:
     lo=max(last,a[0]-12); hi=min(len(bars),i+11)
     from matplotlib.patches import Rectangle
     fig,ax=plt.subplots(figsize=(14,6))
     for n in range(lo,hi):
      _,_,o,h,l,cl=bars[n]
      color='#16865b' if cl>=o else '#d94b4b'
      ax.vlines(n,l,h,color=color,linewidth=1)
      ax.add_patch(Rectangle((n-.32,min(o,cl)),.64,max(abs(cl-o),.000001),facecolor=color,edgecolor=color))
     ax.plot([x[0] for x in (a,b,c)],[x[2] for x in (a,b,c)],'o-',color='#2357c6',linewidth=2)
     for point,label in zip((a,b,c),('Peak 1' if top else 'Bottom 1','Neckline','Peak 2' if top else 'Bottom 2')):
      ax.annotate(label,(point[0],point[2]),xytext=(0,15 if point[1]=='H' else -20),textcoords='offset points',ha='center',color='#2357c6')
     ax.hlines(b[2],b[0],hi-1,linestyle='--',color='#806000',label='Neckline')
     ax.axvline(i,color='#666666',linestyle=':',label='Confirmation candle')
     ax.annotate('SELL' if top else 'BUY',xy=(i,close),xytext=(i,close+(abs(a[2]-b[2])*.35 if top else -abs(a[2]-b[2])*.35)),arrowprops=dict(arrowstyle='->',color='#d94b4b' if top else '#16865b',lw=2),ha='center')
     ax.axvspan(i+.5,hi-.5,color='#aaaaaa',alpha=.12,label='After confirmation')
     ticks=list(range(lo,hi,max(1,(hi-lo)//7)))
     ax.set_xticks(ticks,[bars[n][0].strftime('%m-%d %H:%M') for n in ticks],rotation=25)
     ax.set_ylabel('EUR/USD bid price');ax.ticklabel_format(axis='y',style='plain',useOffset=False)
     ax.set_title(f'EUR/USD M{tf} | {"DOUBLE TOP - SELL" if top else "DOUBLE BOTTOM - BUY"} | {bars[i][0]}')
     ax.grid(alpha=.15);ax.legend(loc='best');fig.tight_layout()
     fig.savefig(out/f'{tf}-{name}-{i}.png',dpi=130);plt.close(fig)
   else:keep.append((a,b,c,seen))
  pending=keep
with open(out/'detections.json','w') as f:json.dump(results,f,indent=2)
print('FIGURES',collections.Counter((x['tf'],x['pattern']) for x in results))
