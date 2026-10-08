#!/usr/bin/env python3
"""
scripts/analyze_latency_budget.py

Parses TachiyomiAT structured telemetry traces (adb logcat or translation-trace.log)
and evaluates:
  1. Native Vision budget compliance (goal: <= 2000 ms total across Det + Seg + OCR + Inpaint).
  2. Auto-reader smoothness arrival wait and user stutter statistics.
"""

import os
import re
import sys
from typing import Any, Dict, List, Optional


def extract_kv_pairs(line: str) -> Dict[str, str]:
    """Extracts key=value tokens from a structured telemetry line into a dict."""
    return dict(re.findall(r'(\b[a-zA-Z0-9_]+)=(\S+)', line))


def parse_budget_summary(line: str) -> Optional[Dict[str, Any]]:
    """
    Parses a budget_summary log line.
    Returns a dict with page, total, det, ocr, inpaint, budgetMet or None if not matching.
    """
    if "event=budget_summary" not in line and "budget_summary" not in line:
        return None

    kv = extract_kv_pairs(line)
    total_str = kv.get("totalNativeMs") or kv.get("total")
    if total_str is None:
        # Fallback regex search if key=value tokenization missed non-standard spacing
        m_tot = re.search(r'totalNativeMs=([\d.]+)', line)
        if m_tot:
            total_str = m_tot.group(1)
        else:
            return None

    try:
        total = float(total_str)
        det = float(kv.get("detMs") or 0.0)
        ocr = float(kv.get("ocrMs") or 0.0)
        inpaint = float(kv.get("inpaintMs") or 0.0)
        page = kv.get("pageKey") or kv.get("page") or "unknown"

        if "budgetMet" in kv:
            budget_met = kv["budgetMet"].lower() == "true"
        else:
            budget_met = total <= 2000.0

        return {
            "page": page,
            "total": total,
            "det": det,
            "ocr": ocr,
            "inpaint": inpaint,
            "budgetMet": budget_met,
        }
    except (ValueError, TypeError):
        return None


def parse_arrival_wait(line: str) -> Optional[Dict[str, Any]]:
    """
    Parses an arrival_wait_recorded log line.
    Returns a dict with page, waitMs, hadToWait or None if not matching.
    """
    if "event=arrival_wait_recorded" not in line and "arrival_wait_recorded" not in line:
        return None

    kv = extract_kv_pairs(line)
    wait_str = kv.get("waitMs") or kv.get("wait")
    if wait_str is None:
        m_wait = re.search(r'waitMs=([\d.]+)', line)
        if m_wait:
            wait_str = m_wait.group(1)
        else:
            return None

    try:
        wait_ms = float(wait_str)
        page = kv.get("pageKey") or kv.get("page") or "unknown"
        if "hadToWait" in kv:
            had_to_wait = kv["hadToWait"].lower() == "true"
        else:
            had_to_wait = wait_ms > 0.0

        return {
            "page": page,
            "waitMs": wait_ms,
            "hadToWait": had_to_wait,
        }
    except (ValueError, TypeError):
        return None


def analyze_lines(lines: List[str]) -> Dict[str, Any]:
    """Analyzes a list of log lines and prints the report."""
    native_events: List[Dict[str, Any]] = []
    arrival_waits: List[Dict[str, Any]] = []

    for line in lines:
        budget_ev = parse_budget_summary(line)
        if budget_ev:
            native_events.append(budget_ev)
            continue

        wait_ev = parse_arrival_wait(line)
        if wait_ev:
            arrival_waits.append(wait_ev)

    print("=== NATIVE VISION PIPELINE ANALYSIS (Goal: <= 2000 ms) ===")
    if not native_events:
        print("No budget_summary events found.")
    else:
        met_count = sum(1 for e in native_events if e["budgetMet"])
        pct = (met_count / len(native_events)) * 100.0
        print(f"Total Pages Analyzed: {len(native_events)}")
        print(f"Within 2.0s Budget:  {met_count} / {len(native_events)} ({pct:.1f}%)")
        print("\nPage Breakdown:")
        print(f"{'Page':<12} | {'Total (ms)':<10} | {'Det (ms)':<9} | {'OCR (ms)':<9} | {'Inpaint':<9} | {'Status'}")
        print("-" * 65)
        for e in native_events:
            status = "PASS" if e["budgetMet"] else "FAIL (SLOWER)"
            print(f"{e['page']:<12} | {e['total']:<10.1f} | {e['det']:<9.1f} | {e['ocr']:<9.1f} | {e['inpaint']:<9.1f} | {status}")

    print("\n=== AUTO-READER SMOOTHNESS ARRIVAL WAITS ===")
    if not arrival_waits:
        print("No arrival_wait_recorded events found.")
    else:
        waited_count = sum(1 for a in arrival_waits if a["hadToWait"])
        avg_wait = sum(a["waitMs"] for a in arrival_waits) / len(arrival_waits)
        print(f"Total Page Arrivals: {len(arrival_waits)}")
        print(f"Pages With User Stutter: {waited_count} / {len(arrival_waits)}")
        print(f"Average Arrival Wait:    {avg_wait:.1f} ms")
        for a in arrival_waits:
            print(f"  Page {a['page']}: {a['waitMs']:.1f} ms wait (stutter={a['hadToWait']})")

    met_count = sum(1 for e in native_events if e["budgetMet"])
    budget_pct = (met_count / len(native_events) * 100.0) if native_events else 0.0
    stutter_count = sum(1 for a in arrival_waits if a["hadToWait"])
    avg_arrival_wait = (sum(a["waitMs"] for a in arrival_waits) / len(arrival_waits)) if arrival_waits else 0.0

    return {
        "native_events": native_events,
        "arrival_waits": arrival_waits,
        "total_pages": len(native_events),
        "budget_met_count": met_count,
        "budget_met_pct": budget_pct,
        "total_arrivals": len(arrival_waits),
        "stutter_count": stutter_count,
        "avg_arrival_wait": avg_arrival_wait,
    }


def analyze_log(filename: str) -> Dict[str, Any]:
    """Reads a log file and performs budget and smoothness analysis."""
    if not os.path.exists(filename):
        print(f"Error: File not found: {filename}", file=sys.stderr)
        sys.exit(1)

    with open(filename, "r", encoding="utf-8", errors="ignore") as f:
        lines = f.readlines()

    return analyze_lines(lines)


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help"):
        print("Usage: python analyze_latency_budget.py <logfile>")
        sys.exit(0 if (len(sys.argv) >= 2 and sys.argv[1] in ("-h", "--help")) else 1)

    analyze_log(sys.argv[1])


if __name__ == "__main__":
    main()
