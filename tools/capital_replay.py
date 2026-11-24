#!/usr/bin/env python3
"""Capital-constrained, single-position BTCUSDT replay from existing signal artifacts."""
import csv, io, json, math, os, urllib.request, zipfile
from collections import Counter
from pathlib import Path

REPO = "charikovlealeksei1993-cmyk/cryptotrader"
ARTIFACTS = {"tp7-sl3":14948209623, "tp7-sl2":14949690261}
START_RUB = 10000.0
RUB_PER_USDT = 90.0  # explicit illustrative assumption, not historical FX
TAKER = 0.00055
SLIPPAGE = 0.0002  # per side, adverse
QTY_STEP = 0.001
MIN_QTY = 0.001
MIN_NOTIONAL = 5.0
LEVERAGE = 1.0
INITIAL = START_RUB / RUB_PER_USDT

def get_csv(artifact_id):
    if os.environ.get("LOCAL_RESULTS"):\n        return list(csv.DictReader(open("signal-results.csv", newline="")))\n    token = os.environ["GITHUB_TOKEN"]
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip",
        headers={"Authorization":f"Bearer {token}", "Accept":"application/vnd.github+json",
                 "X-GitHub-Api-Version":"2022-11-28"})
    with urllib.request.urlopen(req,timeout=120) as response:
        payload=response.read()
    with zipfile.ZipFile(io.BytesIO(payload)) as z:
        names=[n for n in z.namelist() if n.endswith("signal-results.csv")]
        if len(names)!=1: raise ValueError(f"Expected one signal-results.csv, got {names}")
        return list(csv.DictReader(io.StringIO(z.read(names[0]).decode("utf-8"))))

def simulate(rows, tf, sides):
    balance=INITIAL
    busy_until=-1
    counts=Counter()
    peak=balance
    max_drawdown=0.0
    total_fees=0.0
    trades=[]
    # One position for this timeframe+side selection, ordered by entry timestamp
    candidates=sorted((r for r in rows if int(r["tf"])==tf and r["side"] in sides),
                      key=lambda r:(int(r["time"]),r["side"]))
    for r in candidates:
        entry_time=int(r["time"])
        if entry_time<=busy_until:
            counts["busy_skipped"]+=1
            continue
        status=r["status"]
        if status not in ("TP","SL","ambiguous","gap","open"):
            counts["bad_status"]+=1
            continue
        entry=float(r["entry"])
        exit_price=float(r["exitPrice"])
        if entry<=0 or exit_price<=0:
            counts["bad_price"]+=1
            continue
        qty=max(MIN_QTY,math.ceil((MIN_NOTIONAL/entry-1e-12)/QTY_STEP)*QTY_STEP)
        qty=round(qty,8)
        side=1 if r["side"]=="long" else -1
        filled_entry=entry*(1+side*SLIPPAGE)
        filled_exit=exit_price*(1-side*SLIPPAGE)
        entry_fee=filled_entry*qty*TAKER
        exit_fee=filled_exit*qty*TAKER
        required_margin=filled_entry*qty/LEVERAGE
        # reserve margin and projected closing fee, no borrowing
        if required_margin+entry_fee+exit_fee>balance:
            counts["insufficient_balance"]+=1
            continue
        pnl=side*(filled_exit-filled_entry)*qty-entry_fee-exit_fee
        balance+=pnl
        total_fees+=entry_fee+exit_fee
        busy_until=int(r["exitTime"])
        counts["trades"]+=1
        counts["status_"+status]+=1
        counts["wins" if pnl>0 else "losses"]+=1
        peak=max(peak,balance)
        max_drawdown=max(max_drawdown,(peak-balance)/peak)
        trades.append({"entryTime":entry_time,"exitTime":busy_until,"tf":tf,
                       "side":r["side"],"status":status,"qty":qty,
                       "entry":entry,"exit":exit_price,"pnlUSDT":round(pnl,8),
                       "balanceUSDT":round(balance,8)})
    return {"tf":tf,"sides":"/".join(sorted(sides)),"initialUSDT":round(INITIAL,4),
            "finalUSDT":round(balance,4),"finalRUB_at_fixed_rate":round(balance*RUB_PER_USDT,2),
            "netRUB_at_fixed_rate":round((balance-INITIAL)*RUB_PER_USDT,2),
            "maxDrawdownPct":round(max_drawdown*100,3),
            "feesUSDT":round(total_fees,4),**dict(counts)},trades

def main():
    output=Path("capital-results")
    output.mkdir(exist_ok=True)
    summaries=[]
    for label,artifact_id in ARTIFACTS.items():\n        if os.environ.get("ONLY_VARIANT") and label != os.environ["ONLY_VARIANT"]:\n            continue
        rows=get_csv(artifact_id)
        if not rows: raise RuntimeError(f"Empty artifact {label}")
        for tf in (1,5,15,30,60):
            for sides in ({"long","short"},{"long"},{"short"}):
                result,trades=simulate(rows,tf,sides)
                result["variant"]=label
                summaries.append(result)
                with (output/f"{label}-tf{tf}-{result['sides'].replace('/','-')}.csv").open("w",newline="") as f:
                    fields=["entryTime","exitTime","tf","side","status","qty","entry","exit","pnlUSDT","balanceUSDT"]
                    writer=csv.DictWriter(f,fieldnames=fields)
                    writer.writeheader()
                    writer.writerows(trades)
    fields=["variant","tf","sides","initialUSDT","finalUSDT","finalRUB_at_fixed_rate",
            "netRUB_at_fixed_rate","maxDrawdownPct","feesUSDT","trades","wins","losses",
            "busy_skipped","insufficient_balance","status_TP","status_SL","status_ambiguous",
            "status_gap","status_open"]
    with (output/"summary.csv").open("w",newline="") as f:
        writer=csv.DictWriter(f,fieldnames=fields,extrasaction="ignore")
        writer.writeheader()
        writer.writerows(summaries)
    (output/"assumptions.json").write_text(json.dumps({
        "startingRUB":START_RUB,"rubPerUSDT":RUB_PER_USDT,"takerPerSide":TAKER,
        "adverseSlippagePerSide":SLIPPAGE,"leverage":LEVERAGE,"qtyStepBTC":QTY_STEP,
        "minQtyBTC":MIN_QTY,"minNotionalUSDT":MIN_NOTIONAL,
        "oneOpenPositionPerScenario":True,"fundingIncluded":False,
        "caveats":["No liquidation model beyond 1x margin check",
                   "Historical BTC signals may have lookahead/timing approximation",
                   "No intrabar reordering; ambiguous bars use prior replay's conservative SL exit",
                   "FX held constant; not a historical RUB conversion",
                   "Gap/open exits use recorded final price, not guaranteed executable"]
    },indent=2))
    print("variant,tf,sides,trades,netRUB,maxDD%,feesUSDT,skippedNoBalance")
    for r in summaries:
        print(f"{r['variant']},{r['tf']},{r['sides']},{r.get('trades',0)},{r['netRUB_at_fixed_rate']},{r['maxDrawdownPct']},{r['feesUSDT']},{r.get('insufficient_balance',0)}")

if __name__=="__main__": main()
