#!/usr/bin/env python3
"""Monthly stability of experiment 38, using its actual capital-trade artifacts.
No refitting or new signals; realised PnL attributed to UTC exit month.
"""
import csv
import io
import json
import os
import urllib.request
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

REPO = "charikovlealeksei1993-cmyk/cryptotrader"
RUN_ID = 45460493348
ARTIFACTS = {"tp7-sl2": 14951758568}
SCENARIOS = [
    ("short-30m-tp7-sl2", "tp7-sl2", "tp7-sl2-tf30-short.csv"),
]
INITIAL_USDT = 10000 / 90
RUB_PER_USDT = 90

def fetch(artifact_id):
    token = os.environ["GITHUB_TOKEN"]
    req = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=120) as response:
        raw = response.read()
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        result = {}
        for name in z.namelist():
            if name.endswith(".csv") and "capital-results/" in name:
                result[Path(name).name] = list(csv.DictReader(io.StringIO(z.read(name).decode())))
        if not result:
            raise RuntimeError(f"No capital-results CSV in artifact {artifact_id}; files={z.namelist()[:20]}")
        return result

def month(ms):
    return datetime.fromtimestamp(int(ms) / 1000, tz=timezone.utc).strftime("%Y-%m")

def analyse(label, trades):
    # Trade-level account equity from the original replay, not monthly resets.
    trades.sort(key=lambda x: (int(x["exitTime"]), int(x["entryTime"])))
    by_month = defaultdict(list)
    for t in trades:
        by_month[month(t["exitTime"])].append(t)
    months = sorted(by_month)
    rows = []
    balance = INITIAL_USDT
    global_peak = balance
    max_dd = 0.0
    for m in months:
        subset = by_month[m]
        start = balance
        monthly_peak = start
        monthly_dd = 0.0
        for t in subset:
            balance += float(t["pnlUSDT"])
            global_peak = max(global_peak, balance)
            max_dd = max(max_dd, (global_peak - balance) / global_peak)
            monthly_peak = max(monthly_peak, balance)
            monthly_dd = max(monthly_dd, (monthly_peak - balance) / monthly_peak)
        pnl = sum(float(t["pnlUSDT"]) for t in subset)
        rows.append({
            "scenario": label, "exitMonthUTC": m, "trades": len(subset),
            "wins": sum(float(t["pnlUSDT"]) > 0 for t in subset),
            "losses": sum(float(t["pnlUSDT"]) <= 0 for t in subset),
            "netRUB": round(pnl * RUB_PER_USDT, 2),
            "startBalanceRUB": round(start * RUB_PER_USDT, 2),
            "endBalanceRUB": round(balance * RUB_PER_USDT, 2),
            "withinMonthDrawdownPct": round(monthly_dd * 100, 3),
        })
    return rows, {
        "scenario": label, "trades": len(trades),
        "profitableMonths": sum(r["netRUB"] > 0 for r in rows),
        "losingMonths": sum(r["netRUB"] < 0 for r in rows),
        "activeMonths": len(rows),
        "netRUB": round((balance - INITIAL_USDT) * RUB_PER_USDT, 2),
        "maxDrawdownPct": round(max_dd * 100, 3),
        "bestMonthRUB": max((r["netRUB"] for r in rows), default=0),
        "worstMonthRUB": min((r["netRUB"] for r in rows), default=0),
    }

def main():
    datasets = {}
    for variant, artifact in ARTIFACTS.items():
        local = Path("source-capital-results")
        if local.is_dir():
            datasets[variant] = {
                p.name: list(csv.DictReader(p.open(newline="")))
                for p in local.rglob("*.csv")
            }
        else:
            datasets[variant] = fetch(artifact)
    out = Path("monthly-results")
    out.mkdir(exist_ok=True)
    all_months = []
    summaries = []
    for label, variant, filename in SCENARIOS:
        trades = datasets[variant].get(filename)
        if trades is None:
            raise RuntimeError(f"Missing {filename}; available {sorted(datasets[variant])}")
        rows, summary = analyse(label, trades)
        all_months.extend(rows)
        summaries.append(summary)
        print(json.dumps(summary, ensure_ascii=False))
    def write(name, rows):
        with (out / name).open("w", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0]))
            writer.writeheader()
            writer.writerows(rows)
    write("monthly.csv", all_months)
    write("summary.csv", summaries)
    (out / "assumptions.json").write_text(json.dumps({
        "sourceRun": RUN_ID, "sourceArtifacts": ARTIFACTS,
        "capitalModel": "Unchanged experiment 38 trade PnL and sizing",
        "attribution": "UTC month of trade exit; not entry month",
        "fx": "Fixed illustrative 90 RUB/USDT",
        "limitations": [
            "Closed-trade equity only; intra-trade drawdown excluded",
            "Historical signals may have timing approximations",
            "Funding excluded in source replay",
            "Same sample as optimization: monthly stability is NOT out-of-sample validation",
            "Capital replay assumes 0.001 BTC minimum quantity"
        ]}, indent=2))
if __name__ == "__main__":
    main()
