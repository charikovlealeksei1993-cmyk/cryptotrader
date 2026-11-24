#!/usr/bin/env python3
"""Audit the headerless annual BTC replay input without inventing trap snapshots."""
import csv
import json
import sys
from pathlib import Path

ANNUAL_COLUMNS = ["time", "open", "high", "low", "close", "volume",
                  "delta", "cvd", "speed"]
TRAP_REQUIRED = ["liq_total15m", "liq_long15m", "liq_short15m",
                 "oi_previous", "oi_current", "aggressive_delta_ratio",
                 "captured_at"]
def audit(path):
    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        first = next(reader, [])
        count = 1 if first else 0
        lengths = {len(first): count} if first else {}
        for row in reader:
            count += 1
            lengths[len(row)] = lengths.get(len(row), 0) + 1
    is_headerless = bool(first) and first[0].isdigit()
    columns = ANNUAL_COLUMNS if is_headerless and len(first) == 9 else first
    return {
        "file": str(path), "rows": count, "headerless": is_headerless,
        "first_row_fields": len(first), "row_width_counts": lengths,
        "interpreted_columns": columns,
        "candles_available": all(x in columns for x in ANNUAL_COLUMNS[:6]),
        "existing_flow_columns": [x for x in ("delta", "cvd", "speed") if x in columns],
        "missing_trap_snapshots": TRAP_REQUIRED,
        "ready_for_causal_trap_comparison": False,
        "notes": [
            "Annual input is nine positional fields, no CSV header; time is candle timestamp in ms.",
            "delta/cvd/speed are detector flow features, NOT replacements for liquidation/OI snapshots.",
            "Do not infer unavailable historical liquidation or OI values from OHLCV."
        ]
    }
def main():
    results = [audit(Path(p)) for p in sys.argv[1:] if Path(p).is_file()]
    report = {
        "experiment": 39, "status": "MISSING_POINT_IN_TIME_TRAP_DATA",
        "inputs": results,
        "next_step": "Collect timestamped liquidation, OI, aggressive flow and capture times; align with candle closes before A/B replay.",
    }
    Path("experiment-39-audit.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
if __name__ == "__main__":
    main()
