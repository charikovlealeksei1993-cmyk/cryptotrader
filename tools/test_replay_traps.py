#!/usr/bin/env python3
"""Deterministic smoke tests for replay_traps.py; no external packages."""
import unittest
from replay_traps import detect, replay

INTERVAL = 60_000


def candle(i, o=100, h=101, l=99, c=100, v=100, **overrides):
    row = dict(symbol="BTCUSDT", time=i * INTERVAL, open=o, high=h,
               low=l, close=c, volume=v, liq_total15m=20000,
               liq_long15m=18000, liq_short15m=2000,
               oi_previous=1000, oi_current=1003,
               aggressive_delta_ratio=-0.2, captured_at=(i + 1) * INTERVAL)
    row.update(overrides)
    return row


def long_series():
    series = [candle(i) for i in range(20)]
    series.append(candle(20, o=99, h=100, l=98, c=99.5, v=200))
    series.append(candle(21, o=99.5, h=101, l=99, c=100.5, v=150))
    series.extend(candle(i, o=100.5, h=102, l=100, c=101, v=100)
                  for i in range(22, 28))
    return series


class ReplayTests(unittest.TestCase):
    def test_confirmed_long(self):
        rows = long_series()
        self.assertEqual(detect(rows[:22], rows[21], 10000), "long")

    def test_missing_liquidations(self):
        rows = long_series()
        rows[21]["liq_total15m"] = 0
        self.assertEqual(detect(rows[:22], rows[21], 10000), "")

    def test_opposing_flow_required(self):
        rows = long_series()
        rows[21]["aggressive_delta_ratio"] = 0.2
        self.assertEqual(detect(rows[:22], rows[21], 10000), "")

    def test_oi_required(self):
        rows = long_series()
        rows[21]["oi_current"] = 1000
        self.assertEqual(detect(rows[:22], rows[21], 10000), "")

    def test_next_open_entry(self):
        rows = long_series()
        rows[22]["open"] = 101.25
        signals = replay(rows, 10000, 3, INTERVAL)
        self.assertEqual(len(signals), 1)
        self.assertEqual(signals[0]["entry"], 101.25)

    def test_gap_rejected(self):
        rows = long_series()
        rows[10]["time"] += 5
        self.assertEqual(replay(rows, 10000, 3, INTERVAL), [])

    def test_stale_snapshot_rejected(self):
        rows = long_series()
        rows[21]["captured_at"] = rows[21]["time"] + 3 * INTERVAL
        self.assertEqual(replay(rows, 10000, 3, INTERVAL), [])

    def test_duplicate_candle_does_not_overwrite(self):
        rows = long_series()
        duplicate = dict(rows[21], aggressive_delta_ratio=0.8,
                         captured_at=rows[21]["captured_at"] + 1000)
        rows.append(duplicate)
        self.assertEqual(len(replay(rows, 10000, 3, INTERVAL)), 1)

    def test_short(self):
        rows = [candle(i) for i in range(20)]
        rows.append(candle(20, o=101, h=102, l=100, c=100.5, v=200))
        rows.append(candle(21, o=100.5, h=101, l=99, c=99.5, v=150,
                           liq_long15m=2000, liq_short15m=18000,
                           aggressive_delta_ratio=0.2))
        self.assertEqual(detect(rows, rows[21], 10000), "short")


if __name__ == "__main__":
    unittest.main()
