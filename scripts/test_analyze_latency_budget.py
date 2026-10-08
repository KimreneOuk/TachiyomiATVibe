#!/usr/bin/env python3
"""
Unit and integration tests for scripts/analyze_latency_budget.py
"""

import io
import os
import subprocess
import sys
import tempfile
import unittest

# Ensure scripts directory is on sys.path
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from analyze_latency_budget import (
    analyze_lines,
    analyze_log,
    extract_kv_pairs,
    parse_arrival_wait,
    parse_budget_summary,
)


class TestAnalyzeLatencyBudget(unittest.TestCase):

    def test_extract_kv_pairs(self):
        line = "ts=2026-10-08T12:00:00.000 monoMs=123 domain=native_vision event=budget_summary pageKey=p1.jpg totalNativeMs=1500.00 budgetMet=true"
        kv = extract_kv_pairs(line)
        self.assertEqual(kv.get("domain"), "native_vision")
        self.assertEqual(kv.get("event"), "budget_summary")
        self.assertEqual(kv.get("pageKey"), "p1.jpg")
        self.assertEqual(kv.get("totalNativeMs"), "1500.00")
        self.assertEqual(kv.get("budgetMet"), "true")

    def test_parse_budget_summary_pass(self):
        # NativeVisionTelemetry format: budgetMet is before detMs
        line = (
            "10-08 12:00:00.000 10654 10700 D TachiyomiAT.Native_vision: "
            "ts=2026-10-08T12:00:00.000 monoMs=500000 domain=native_vision event=budget_summary "
            "pageKey=ch1_p001.jpg targetMs=2000 totalNativeMs=1250.50 budgetMet=true "
            "detMs=200.00 segMs=15.00 ocrMs=450.50 inpaintMs=585.00 leafCount=10 boxCount=6 provider=NNAPI"
        )
        ev = parse_budget_summary(line)
        self.assertIsNotNone(ev)
        self.assertEqual(ev["page"], "ch1_p001.jpg")
        self.assertAlmostEqual(ev["total"], 1250.50)
        self.assertAlmostEqual(ev["det"], 200.00)
        self.assertAlmostEqual(ev["ocr"], 450.50)
        self.assertAlmostEqual(ev["inpaint"], 585.00)
        self.assertTrue(ev["budgetMet"])

    def test_parse_budget_summary_fail(self):
        line = (
            "10-08 12:00:01.000 10654 10700 D TachiyomiAT.Native_vision: "
            "ts=2026-10-08T12:00:01.000 monoMs=501000 domain=native_vision event=budget_summary "
            "pageKey=ch1_p002.jpg targetMs=2000 totalNativeMs=2450.00 budgetMet=false "
            "detMs=350.00 segMs=50.00 ocrMs=1100.00 inpaintMs=950.00 leafCount=20 boxCount=12 provider=CPU"
        )
        ev = parse_budget_summary(line)
        self.assertIsNotNone(ev)
        self.assertEqual(ev["page"], "ch1_p002.jpg")
        self.assertAlmostEqual(ev["total"], 2450.00)
        self.assertAlmostEqual(ev["det"], 350.00)
        self.assertAlmostEqual(ev["ocr"], 1100.00)
        self.assertAlmostEqual(ev["inpaint"], 950.00)
        self.assertFalse(ev["budgetMet"])

    def test_parse_budget_summary_alternative_field_order(self):
        # Format where budgetMet is at the end
        line = (
            "domain=native_vision event=budget_summary pageKey=ch1_p003.jpg "
            "totalNativeMs=1800.00 detMs=220.00 ocrMs=600.00 inpaintMs=980.00 budgetMet=true"
        )
        ev = parse_budget_summary(line)
        self.assertIsNotNone(ev)
        self.assertEqual(ev["page"], "ch1_p003.jpg")
        self.assertAlmostEqual(ev["total"], 1800.00)
        self.assertTrue(ev["budgetMet"])

    def test_parse_arrival_wait_no_stutter(self):
        line = (
            "10-08 12:00:02.000 10654 10700 D TachiyomiAT.Auto: "
            "ts=2026-10-08T12:00:02.000 monoMs=502000 domain=auto event=arrival_wait_recorded "
            "pageIndex=0 pageKey=ch1_p001.jpg waitMs=0.00 hadToWait=false"
        )
        aw = parse_arrival_wait(line)
        self.assertIsNotNone(aw)
        self.assertEqual(aw["page"], "ch1_p001.jpg")
        self.assertAlmostEqual(aw["waitMs"], 0.0)
        self.assertFalse(aw["hadToWait"])

    def test_parse_arrival_wait_with_stutter(self):
        line = (
            "10-08 12:00:03.000 10654 10700 D TachiyomiAT.Auto: "
            "ts=2026-10-08T12:00:03.000 monoMs=503000 domain=auto event=arrival_wait_recorded "
            "pageIndex=1 pageKey=ch1_p002.jpg waitMs=145.50 hadToWait=true"
        )
        aw = parse_arrival_wait(line)
        self.assertIsNotNone(aw)
        self.assertEqual(aw["page"], "ch1_p002.jpg")
        self.assertAlmostEqual(aw["waitMs"], 145.50)
        self.assertTrue(aw["hadToWait"])

    def test_empty_and_non_matching_logs(self):
        # Empty input
        res_empty = analyze_lines([])
        self.assertEqual(res_empty["total_pages"], 0)
        self.assertEqual(res_empty["budget_met_pct"], 0.0)
        self.assertEqual(res_empty["total_arrivals"], 0)
        self.assertEqual(res_empty["stutter_count"], 0)
        self.assertEqual(res_empty["avg_arrival_wait"], 0.0)

        # OEM junk / non-matching logs
        spam_lines = [
            "10-08 12:00:00.000 1000 1000 D MediaProvider: scanning file /sdcard/Download/test.jpg",
            "10-08 12:00:00.001 1000 1000 D DatabaseUtils: executing query...",
            "10-08 12:00:00.002 10654 10654 D ViewRootImpl: Relayout returned: old=(0,0,1080,2400)",
            "10-08 12:00:00.003 10654 10654 D ColorOS: Animation frame skipped",
            "Random unformatted error line with no telemetry",
        ]
        res_spam = analyze_lines(spam_lines)
        self.assertEqual(res_spam["total_pages"], 0)
        self.assertEqual(res_spam["total_arrivals"], 0)

    def test_budget_percentage_calculation(self):
        # 4 pages: 3 meet budget (<= 2.0s), 1 exceeds (> 2.0s) -> 75.0%
        lines = [
            "domain=native_vision event=budget_summary pageKey=p1.jpg totalNativeMs=1200.00 detMs=200.00 ocrMs=400.00 inpaintMs=600.00 budgetMet=true",
            "domain=native_vision event=budget_summary pageKey=p2.jpg totalNativeMs=1950.00 detMs=250.00 ocrMs=700.00 inpaintMs=1000.00 budgetMet=true",
            "domain=native_vision event=budget_summary pageKey=p3.jpg totalNativeMs=2300.00 detMs=300.00 ocrMs=1000.00 inpaintMs=1000.00 budgetMet=false",
            "domain=native_vision event=budget_summary pageKey=p4.jpg totalNativeMs=900.00 detMs=100.00 ocrMs=300.00 inpaintMs=500.00 budgetMet=true",
        ]
        res = analyze_lines(lines)
        self.assertEqual(res["total_pages"], 4)
        self.assertEqual(res["budget_met_count"], 3)
        self.assertAlmostEqual(res["budget_met_pct"], 75.0)

    def test_arrival_wait_and_stutter_aggregation(self):
        # 3 arrivals: waitMs = 0.0, 100.0, 200.0. Average = 100.0 ms. Stutters = 2 (waited > 0).
        lines = [
            "domain=auto event=arrival_wait_recorded pageKey=p1.jpg waitMs=0.00 hadToWait=false",
            "domain=auto event=arrival_wait_recorded pageKey=p2.jpg waitMs=100.00 hadToWait=true",
            "domain=auto event=arrival_wait_recorded pageKey=p3.jpg waitMs=200.00 hadToWait=true",
        ]
        res = analyze_lines(lines)
        self.assertEqual(res["total_arrivals"], 3)
        self.assertEqual(res["stutter_count"], 2)
        self.assertAlmostEqual(res["avg_arrival_wait"], 100.0)

    def test_cli_execution_with_fixture(self):
        fixture_content = (
            "10-08 12:00:00.000 10654 10700 D TachiyomiAT.Native_vision: "
            "ts=2026-10-08T12:00:00.000 monoMs=500000 domain=native_vision event=budget_summary "
            "pageKey=page_001.jpg targetMs=2000 totalNativeMs=1296.00 budgetMet=true "
            "detMs=210.50 segMs=15.20 ocrMs=450.30 inpaintMs=620.00 leafCount=12 boxCount=8 provider=NNAPI\n"
            "10-08 12:00:01.000 10654 10700 D TachiyomiAT.Native_vision: "
            "ts=2026-10-08T12:00:01.000 monoMs=501000 domain=native_vision event=budget_summary "
            "pageKey=page_002.jpg targetMs=2000 totalNativeMs=2450.00 budgetMet=false "
            "detMs=350.00 segMs=50.00 ocrMs=1100.00 inpaintMs=950.00 leafCount=20 boxCount=12 provider=CPU\n"
            "10-08 12:00:02.000 10654 10700 D TachiyomiAT.Auto: "
            "ts=2026-10-08T12:00:02.000 monoMs=502000 domain=auto event=arrival_wait_recorded "
            "pageIndex=0 pageKey=page_001.jpg waitMs=0.00 hadToWait=false\n"
            "10-08 12:00:03.000 10654 10700 D TachiyomiAT.Auto: "
            "ts=2026-10-08T12:00:03.000 monoMs=503000 domain=auto event=arrival_wait_recorded "
            "pageIndex=1 pageKey=page_002.jpg waitMs=450.00 hadToWait=true\n"
        )
        with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as f:
            f.write(fixture_content)
            temp_path = f.name

        try:
            # Test programmatic invocation
            res = analyze_log(temp_path)
            self.assertEqual(res["total_pages"], 2)
            self.assertEqual(res["budget_met_count"], 1)
            self.assertAlmostEqual(res["budget_met_pct"], 50.0)
            self.assertEqual(res["total_arrivals"], 2)
            self.assertEqual(res["stutter_count"], 1)
            self.assertAlmostEqual(res["avg_arrival_wait"], 225.0)

            # Test subprocess CLI invocation
            proc = subprocess.run(
                [sys.executable, os.path.join(os.path.dirname(__file__), "analyze_latency_budget.py"), temp_path],
                capture_output=True,
                text=True,
                check=True,
            )
            output = proc.stdout
            self.assertIn("Total Pages Analyzed: 2", output)
            self.assertIn("Within 2.0s Budget:  1 / 2 (50.0%)", output)
            self.assertIn("PASS", output)
            self.assertIn("FAIL (SLOWER)", output)
            self.assertIn("Total Page Arrivals: 2", output)
            self.assertIn("Pages With User Stutter: 1 / 2", output)
            self.assertIn("Average Arrival Wait:    225.0 ms", output)
        finally:
            if os.path.exists(temp_path):
                os.remove(temp_path)


if __name__ == "__main__":
    unittest.main()
