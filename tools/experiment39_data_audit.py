#!/usr/bin/env python3
"""Experiment 39: audit whether annual replay can be compared with trap detector.
No synthetic liquidation/OI/delta values; no fabricated PnL.
"""
import csv
import json
import sys
from pathlib import Path

BASE_FIELDS = {"time", "open", "high", "low", "close", "volume"}
TRAP_FIELDS = {"symbol", "time", "open", "high", "low", "close", "volume",
               "liq_total15m", "liq_long15m", "liq_short15m",
               "oi_previous", "oi_current", "aggressive_delta_ratio",
               "captured_at"}
CAPITAL_FIELDS = {"tf", "side", "time", "entry", "exitPrice", "exitTime", "status"}

def audit(path):
    with open(path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        columns = set(reader.fieldnames or [])
        rows = sum(1 for _ in reader)
    return {"file": str(path), "rows": rows, "columns": sorted(columns),
            "missing_trap_fields": sorted(TRAP_FIELDS - columns),
            "missing_capital_fields": sorted(CAPITAL_FIELDS - columns),
            "trap_replay_ready": TRAP_FIELDS <= columns,
            "capital_replay_ready": CAPITAL_FIELDS <= columns}

def main():
    files = [Path(p) for p in sys.argv[1:]]
    results = [audit(p) for p in files if p.is_file()]
    report = {
        "experiment": 39,
        "question": "Does the trap filter improve identical annual BTC trades?",
        "status": "NOT_COMPARABLE_YET",
        "explanation": ("Trap unit tests use synthetic candles; annual capital replay uses "
                        "historical signal rows. A valid A/B comparison needs real point-in-time "
                        "liquidation, open-interest and aggressive-flow snapshots aligned to "
                        "each closed candle, plus identical entries/exits, fees and sizing."),
        "inputs": results,
        "required_next_step": ("Record or obtain timestamped historical trap inputs, then "
                               "run both strategies over the same dates and capital model."),
    }
    Path("experiment-39-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    # Audit succeeds, but does not assert strategy profitability.
if __name__ == "__main__":
    main()
