#!/usr/bin/env python3
"""Causal replay of recorded CryptoTrader trap inputs. Python 3 stdlib only.

CSV input, chronological per symbol, one row per CLOSED candle:
symbol,time,open,high,low,close,volume,liq_total15m,liq_long15m,liq_short15m,oi_previous,oi_current,aggressive_delta_ratio

The liquidation, OI and delta columns MUST be snapshots available at that
row's time, not values reconstructed using future data. Missing data = no signal.
This is a research harness, not a live trading simulator.
"""
import argparse
import csv
from collections import defaultdict
from pathlib import Path

FIELDS = ("open", "high", "low", "close", "volume",
          "liq_total15m", "liq_long15m", "liq_short15m",
          "oi_previous", "oi_current", "aggressive_delta_ratio")


def detect(history, row, threshold):
    if len(history) < 22:
        return ""
    sweep, confirm = history[-2], history[-1]
    reference = history[-22:-2]
    support = min(c["low"] for c in reference)
    resistance = max(c["high"] for c in reference)
    avg_volume = sum(c["volume"] for c in reference) / 20
    sweep_range = sweep["high"] - sweep["low"]
    if not (support > 0 and resistance > support and avg_volume > 0
            and sweep_range > 0 and sweep["volume"] >= 1.5 * avg_volume
            and confirm["volume"] >= 1.1 * avg_volume):
        return ""
    if row["liq_total15m"] < threshold or row["liq_total15m"] <= 0:
        return ""
    long_pct = row["liq_long15m"] / row["liq_total15m"] * 100
    short_pct = row["liq_short15m"] / row["liq_total15m"] * 100
    oi_prev, oi_cur = row["oi_previous"], row["oi_current"]
    if oi_prev <= 0 or (oi_cur - oi_prev) / oi_prev * 100 < 0.15:
        return ""
    delta = row["aggressive_delta_ratio"]
    if (long_pct >= 60 and sweep["low"] < support * 0.9995
            and sweep["close"] > sweep["low"] + sweep_range * 0.45
            and confirm["close"] > support and confirm["close"] > sweep["close"]
            and confirm["close"] > confirm["open"] and delta <= -0.10):
        return "long"
    if (short_pct >= 60 and sweep["high"] > resistance * 1.0005
            and sweep["close"] < sweep["high"] - sweep_range * 0.45
            and confirm["close"] < resistance and confirm["close"] < sweep["close"]
            and confirm["close"] < confirm["open"] and delta >= 0.10):
        return "short"
    return ""


def replay(rows, threshold, horizon):
    by_symbol = defaultdict(list)
    for row in rows:
        by_symbol[row["symbol"]].append(row)
    results = []
    for symbol, series in by_symbol.items():
        series.sort(key=lambda x: x["time"])
        for i, row in enumerate(series):
            # The current CSV row is a closed candle. Mimic app's trailing
            # unclosed candle by using closed history through this row.
            history = series[:i + 1]
            direction = detect(history, row, threshold)
            if not direction:
                continue
            if i + horizon >= len(series):
                continue
            entry = row["close"]
            exit_price = series[i + horizon]["close"]
            gross_pct = (exit_price / entry - 1) * 100
            if direction == "short":
                gross_pct = -gross_pct
            results.append(dict(symbol=symbol, time=row["time"], side=direction,
                                entry=entry, exit=exit_price, gross_pct=round(gross_pct, 5)))
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("trap_replay_results.csv"))
    parser.add_argument("--threshold", type=float, default=10000)
    parser.add_argument("--horizon", type=int, default=15, help="number of closed candles after signal")
    args = parser.parse_args()
    if args.horizon < 1:
        parser.error("--horizon must be positive")
    with args.input.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        required = set(FIELDS) | {"symbol", "time"}
        if not required.issubset(reader.fieldnames or []):
            parser.error("Missing columns: " + ", ".join(sorted(required - set(reader.fieldnames or []))))
        rows = []
        for raw in reader:
            try:
                row = {key: float(raw[key]) for key in FIELDS}
                row.update(symbol=raw["symbol"], time=int(raw["time"]))
            except (ValueError, TypeError):
                continue  # No fabricated data for missing snapshots
            rows.append(row)
    results = replay(rows, args.threshold, args.horizon)
    with args.output.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["symbol", "time", "side", "entry", "exit", "gross_pct"])
        writer.writeheader()
        writer.writerows(results)
    print(f"Valid rows: {len(rows)}; signals with outcomes: {len(results)}")
    print("Gross directional returns only; excludes fees, spread, slippage and stops.")
    print(f"Saved: {args.output}")


if __name__ == "__main__":
    main()
