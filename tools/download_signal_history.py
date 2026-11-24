import csv, gzip, urllib.request, datetime, collections, sys
# Bybit public linear BTCUSDT trade archive; UTC dates, no credentials.
days = [datetime.date.fromisoformat("2026-11-20") + datetime.timedelta(days=i) for i in range(3)]
with open("replay-input.csv","w") as out:
    w=csv.writer(out)
    for day in days:
        url=f"https://public.bybit.com/trading/BTCUSDT/BTCUSDT{day}.csv.gz"
        print("Downloading",url,flush=True)
        buckets={}
        with urllib.request.urlopen(url,timeout=180) as response:
            import io
            for row in csv.DictReader(io.TextIOWrapper(gzip.GzipFile(fileobj=response))):
                t=int(float(row["timestamp"])//60)*60000
                p=float(row["price"]); q=float(row["size"]); usd=p*q
                b=buckets.get(t)
                if b is None:
                    b=[p,p,p,p,0.,collections.deque(maxlen=5000)]
                    buckets[t]=b
                b[1]=max(b[1],p); b[2]=min(b[2],p); b[3]=p; b[4]+=q
                b[5].append((usd,usd if row["side"]=="Buy" else -usd))
        for t,b in sorted(buckets.items()):
            # Retain the final 5000 trades per minute; Kotlin runner applies
            # the shared 5000-trade cap across the previous and current minute.
            w.writerow([t,*b[:5],len(b[5]),";".join(f"{u}:{d}" for u,d in b[5])])
        print(day,len(buckets),"minutes",flush=True)
