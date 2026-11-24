#!/usr/bin/env python3
"""Experiment 39: verify historical BTC data readiness for trap A/B replay."""
import csv
import json
import sys
from pathlib import Path

# Verified against tools/SignalReplay.kt and experiment-37 workflow.
ANNUAL_FIELDS = ["time", "open", "high", "low", "close", "volume",
                 "flow_delta_proxy", "flow_cvd_proxy", "flow_speed_proxy"]
TRAP_EXTRA = ["liq_total15m", "liq_long15m", "liq_short15m",
              "oi_previous", "oi_current", "aggressive_delta_ratio", "captured_at"]

def audit(path):
    with path.open(newline="", encoding="utf-8") as f:
        first = next(csv.reader(f), [])
        f.seek(0)
        headered = bool(first and first[0].strip().lower() == "time")
        reader = csv.DictReader(f) if headered else csv.reader(f)
        count = 0
        widths = {}
        for row in reader:
            count += 1
            width = len(row) if not isinstance(row, dict) else len(row)
            widths[str(width)] = widths.get(str(width), 0) + 1
        fields = list(first) if headered else ANNUAL_FIELDS[:len(first)]
    return {
        "file": str(path), "rows": count, "has_header": headered,
        "first_row_width": len(first), "row_width_counts": widths,
        "known_columns": fields,
        "flow_columns_are_proxies_not_aggressive_trade_snapshots": True,
        "missing_for_trap_comparison": [x for x in TRAP_EXTRA if x not in fields],
        "can_run_honest_trap_ab": all(x in fields for x in TRAP_EXTRA),
    }

def main():
    results = [audit(Path(p)) for p in sys.argv[1:] if Path(p).is_file()]
    ready = bool(results) and all(x["can_run_honest_trap_ab"] for x in results)
    report = {
        "experiment": 39,
        "status": "READY" if ready else "NOT_COMPARABLE_YET",
        "inputs": results,
        "note": ("Annual BTC file contains timestamped OHLCV and three derived flow "
                 "proxies, not recorded point-in-time liquidation, OI and aggressive "
                 "trade snapshots. Do not substitute proxies for those fields."),
        "next": ("Collect timestamped real liquidation/OI/trade-flow snapshots; "
                 "then compare matched trades using identical capital replay."),
    }
    Path("experiment-39-audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
if __name__ == "__main__":
    main()
