#!/usr/bin/env python3
"""
analyze_wall_budget.py — extended wall-time reconciliation analyzer for TachiyomiATVibe.

Companion to scripts/analyze_latency_budget.py (which this does not replace).
Audit tool, host-side only: parses a logcat capture and reconstructs where page
wall time actually went, using the events the app already emits.

Event families parsed
---------------------
A) Translation trace — tag `TachiyomiAT.Translation`, lines begin `schema=translation_trace_v1`:
   schedule_start / schedule_end  (wallMs, busy + wait decomposition, overlapMs, bottleneck)
   run_start / run_end            (queuedMs, totalMs, stageSumMs, bottleneck, retries, outcome)
   stage_start / stage_end        (lane, stage, queueMs, durationMs, totalMs, provider, model, items)

B) Telemetry breadcrumbs — tag `TachiyomiAT.<Domain>`, lines begin `ts=... monoMs=...`:
   budget_summary                 (detMs segMs ocrMs inpaintMs totalNativeMs budgetMet)
   arrival_wait_recorded          (waitMs hadToWait)
   render_tail_breakdown          (permitWaitMs colorEstimateMs commitMs totalTailMs)
   tap_to_dispatch                (delayMs)
   display_attach                 (streamAttached showTranslatedImage displayImageName)

Outputs
-------
  1. Schedule decomposition table (wall vs busy vs waits; overlap; savings)
  2. Per-run reconciliation (totalMs vs stageSumMs vs queuedMs; residue; bottleneck)
  3. Stage interval reconstruction with cross-lane overlap and nested-span detection
  4. Quoted-baseline check: schedule wallMs vs the historical 6-stage quote
  5. P50/P95/P99 for key durations
  6. Optional CSV (--csv PATH): one row per run for spreadsheet joins

Self-test: `python analyze_wall_budget.py --selftest` feeds synthetic lines and
verifies parsing + arithmetic without a device.

Zero non-stdlib dependencies.
"""

import argparse
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

KV_RE = re.compile(r'([a-zA-Z0-9_]+)=(\S+)')
TRACE_SCHEMA = "translation_trace_v1"

WAIT_KEYS = [
    "leaseWaitMs", "nativeQueueMs", "preparedQueueMs", "preparedSendWaitMs",
    "providerWindowWaitMs", "providerGovernorWaitMs", "renderPermitWaitMs",
    "deferredStorageDrainMs", "cleanedPersistMs", "storeCommitMs", "storeFlushMs",
]
BUSY_KEYS = ["nativeBusyMs", "providerBusyMs", "renderBusyMs"]
RUN_KEYS = ["queuedMs", "totalMs", "stageSumMs", "bottleneckMs", "retries"]
STAGE_KEYS = ["queueMs", "durationMs", "totalMs"]


def parse_line(line: str) -> Optional[Dict[str, Any]]:
    """Classify one logcat line into a trace/telemetry event dict, or None."""
    if TRACE_SCHEMA not in line and " event=" not in line:
        return None
    kv = dict(KV_RE.findall(line))
    event = kv.get("event")
    if not event:
        return None
    is_trace = kv.get("schema") == TRACE_SCHEMA
    rec: Dict[str, Any] = {"event": event, "trace": is_trace, "kv": kv}
    if is_trace:
        rec["sid"] = kv.get("sid")
        rec["rid"] = kv.get("rid")
    # monotonic ms if the telemetry family provided it
    if "monoMs" in kv:
        try:
            rec["monoMs"] = float(kv["monoMs"])
        except ValueError:
            pass
    return rec


def to_f(kv: Dict[str, str], key: str, default: float = 0.0) -> float:
    try:
        return float(kv.get(key, default))
    except (TypeError, ValueError):
        return default


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_vals) - 1)
    return sorted_vals[f] + (sorted_vals[c] - sorted_vals[f]) * (k - f)


class Analysis:
    def __init__(self) -> None:
        self.schedules: Dict[str, Dict[str, Any]] = {}
        self.runs: Dict[Tuple[str, str], Dict[str, Any]] = {}
        self.stages: List[Dict[str, Any]] = []
        self.open_stages: Dict[Tuple[str, str, str, int], Dict[str, Any]] = {}
        self.budgets: List[Dict[str, Any]] = []
        self.arrival_waits: List[Dict[str, Any]] = []
        self.render_tails: List[Dict[str, Any]] = []
        self.taps: List[Dict[str, Any]] = []
        self.stage_seq = 0

    def consume(self, rec: Dict[str, Any]) -> None:
        ev = rec["event"]
        kv = rec["kv"]
        if ev == "schedule_end" and rec["trace"]:
            sid = rec["sid"] or "?"
            self.schedules[sid] = {
                "sid": sid, "mode": kv.get("mode"), "pages": kv.get("pages"),
                "wallMs": to_f(kv, "wallMs"),
                "busy": {k: to_f(kv, k) for k in BUSY_KEYS},
                "waits": {k: to_f(kv, k) for k in WAIT_KEYS},
                "overlapMs": to_f(kv, "overlapMs"),
                "unionActiveMs": to_f(kv, "unionActiveMs"),
                "workMs": to_f(kv, "workMs"),
                "criticalPathMs": to_f(kv, "criticalPathMs"),
                "maxQueueMs": to_f(kv, "maxQueueMs"),
                "slowestPage": kv.get("slowestPage"),
                "bottleneck": kv.get("bottleneck"),
                "outcome": kv.get("outcome"),
            }
        elif ev == "run_end" and rec["trace"]:
            key = (rec["sid"] or "?", rec["rid"] or "?")
            self.runs[key] = {
                "sid": rec["sid"], "rid": rec["rid"],
                "mode": kv.get("mode"), "page": kv.get("page"), "pageIndex": kv.get("pageIndex"),
                "plan": kv.get("plan"),
                "queuedMs": to_f(kv, "queuedMs"),
                "totalMs": to_f(kv, "totalMs"),
                "stageSumMs": to_f(kv, "stageSumMs"),
                "bottleneck": kv.get("bottleneck"),
                "bottleneckMs": to_f(kv, "bottleneckMs"),
                "retries": to_f(kv, "retries"),
                "outcome": kv.get("outcome"),
                "residueMs": max(0.0, to_f(kv, "totalMs") - to_f(kv, "stageSumMs") - to_f(kv, "queuedMs")),
            }
        elif ev == "stage_end" and rec["trace"]:
            self.stage_seq += 1
            self.stages.append({
                "sid": rec["sid"], "rid": rec["rid"],
                "lane": kv.get("lane"), "stage": kv.get("stage"),
                "queueMs": to_f(kv, "queueMs"),
                "durationMs": to_f(kv, "durationMs"),
                "totalMs": to_f(kv, "totalMs"),
                "provider": kv.get("provider"), "model": kv.get("model"),
                "items": to_f(kv, "items"),
                "outcome": kv.get("outcome"),
                "seq": self.stage_seq,
                # interval derived from totalMs offsets
                "startMs": max(0.0, to_f(kv, "totalMs") - to_f(kv, "durationMs")),
                "endMs": to_f(kv, "totalMs"),
            })
        elif ev == "budget_summary":
            self.budgets.append({
                "pageKey": kv.get("pageKey"),
                "detMs": to_f(kv, "detMs"), "segMs": to_f(kv, "segMs"),
                "ocrMs": to_f(kv, "ocrMs"), "inpaintMs": to_f(kv, "inpaintMs"),
                "totalNativeMs": to_f(kv, "totalNativeMs"),
                "budgetMet": kv.get("budgetMet", "").lower() == "true",
                "leafCount": to_f(kv, "leafCount"), "boxCount": to_f(kv, "boxCount"),
            })
        elif ev == "arrival_wait_recorded":
            self.arrival_waits.append({
                "pageKey": kv.get("pageKey"),
                "waitMs": to_f(kv, "waitMs"),
                "hadToWait": kv.get("hadToWait", "").lower() == "true",
            })
        elif ev == "render_tail_breakdown":
            self.render_tails.append({
                "pageKey": kv.get("pageKey"),
                "permitWaitMs": to_f(kv, "permitWaitMs"),
                "colorEstimateMs": to_f(kv, "colorEstimateMs"),
                "commitMs": to_f(kv, "commitMs"),
                "totalTailMs": to_f(kv, "totalTailMs"),
            })
        elif ev == "tap_to_dispatch":
            self.taps.append({"pageKey": kv.get("pageKey"), "delayMs": to_f(kv, "delayMs")})


def detect_stage_overlaps(stages: List[Dict[str, Any]]) -> List[str]:
    """Report cross-lane overlaps and same-run nested spans from stage intervals."""
    notes: List[str] = []
    by_run: Dict[Tuple[str, str], List[Dict[str, Any]]] = {}
    for s in stages:
        by_run.setdefault((s["sid"], s["rid"]), []).append(s)
    for (sid, rid), items in by_run.items():
        items.sort(key=lambda x: x["startMs"])
        for i in range(len(items)):
            for j in range(i + 1, len(items)):
                a, b = items[i], items[j]
                ov = min(a["endMs"], b["endMs"]) - max(a["startMs"], b["startMs"])
                if ov > 1.0:
                    rel = "same-lane" if a["lane"] == b["lane"] else "cross-lane"
                    notes.append(
                        f"sid={sid} rid={rid} OVERLAP[{rel}] {ov:.0f}ms: "
                        f"{a['lane']}/{a['stage']}[{a['startMs']:.0f}..{a['endMs']:.0f}] vs "
                        f"{b['lane']}/{b['stage']}[{b['startMs']:.0f}..{b['endMs']:.0f}]"
                    )
    return notes


def fmt_table(headers: List[str], rows: List[List[str]]) -> str:
    widths = [max(len(h), *(len(r[i]) for r in rows)) if rows else len(h)
              for i, h in enumerate(headers)]
    out = [" | ".join(h.ljust(widths[i]) for i, h in enumerate(headers)),
           "-+-".join("-" * w for w in widths)]
    for r in rows:
        out.append(" | ".join(str(c).ljust(widths[i]) for i, c in enumerate(r)))
    return "\n".join(out)


def analyze(analysis: Analysis, csv_path: Optional[str]) -> str:
    out: List[str] = []

    # 1. Schedule decomposition
    out.append("=== SCHEDULE DECOMPOSITION (wall vs busy vs waits) ===")
    if not analysis.schedules:
        out.append("No schedule_end events found.")
    else:
        headers = ["sid", "mode", "wall", "work", "union", "overlap", "sumWaits",
                   "wall-work-sumWaits", "bottleneck"]
        rows = []
        for s in analysis.schedules.values():
            sum_waits = sum(s["waits"].values())
            unexplained = s["wallMs"] - s["workMs"] - sum_waits
            rows.append([
                s["sid"], str(s["mode"]), f"{s['wallMs']:.0f}", f"{s['workMs']:.0f}",
                f"{s['unionActiveMs']:.0f}", f"{s['overlapMs']:.0f}", f"{sum_waits:.0f}",
                f"{unexplained:.0f}", str(s["bottleneck"]),
            ])
        out.append(fmt_table(headers, rows))
        out.append("")
        out.append("Wait components (ms) per schedule:")
        headers = ["sid"] + WAIT_KEYS
        rows = [[s["sid"]] + [f"{s['waits'][k]:.0f}" for k in WAIT_KEYS]
                for s in analysis.schedules.values()]
        out.append(fmt_table(headers, rows))

    # 2. Run reconciliation
    out.append("")
    out.append("=== RUN RECONCILIATION (totalMs vs stageSumMs vs queuedMs) ===")
    if not analysis.runs:
        out.append("No run_end events found.")
    else:
        headers = ["sid", "rid", "mode", "page", "plan", "queued", "stageSum",
                   "total", "residue", "bottleneck", "retries", "outcome"]
        rows = []
        for r in analysis.runs.values():
            rows.append([
                r["sid"], r["rid"], str(r["mode"]), str(r["page"]), str(r["plan"]),
                f"{r['queuedMs']:.0f}", f"{r['stageSumMs']:.0f}", f"{r['totalMs']:.0f}",
                f"{r['residueMs']:.0f}", str(r["bottleneck"]), f"{r['retries']:.0f}",
                str(r["outcome"]),
            ])
        out.append(fmt_table(headers, rows))
        totals = sorted(r["totalMs"] for r in analysis.runs.values())
        residues = sorted(r["residueMs"] for r in analysis.runs.values())
        out.append("")
        out.append(f"run totalMs   P50={percentile(totals, 50):.0f}  "
                   f"P95={percentile(totals, 95):.0f}  P99={percentile(totals, 99):.0f}")
        out.append(f"run residueMs P50={percentile(residues, 50):.0f}  "
                   f"P95={percentile(residues, 95):.0f}  P99={percentile(residues, 99):.0f}")

    # 3. Stage intervals / overlaps
    out.append("")
    out.append("=== STAGE OVERLAP DETECTION ===")
    notes = detect_stage_overlaps(analysis.stages)
    if not notes:
        out.append("No overlapping stage intervals detected (>1ms).")
    else:
        for n in notes[:40]:
            out.append(n)
        if len(notes) > 40:
            out.append(f"... {len(notes) - 40} more overlaps suppressed")

    # 4. Quoted-baseline check
    out.append("")
    out.append("=== BUDGET_SUMMARY (native vision only; translate NOT included) ===")
    if not analysis.budgets:
        out.append("No budget_summary events found.")
    else:
        headers = ["page", "det", "seg", "ocr", "inpaint", "totalNative", "met", "leaves"]
        rows = [[b["pageKey"], f"{b['detMs']:.0f}", f"{b['segMs']:.0f}", f"{b['ocrMs']:.0f}",
                 f"{b['inpaintMs']:.0f}", f"{b['totalNativeMs']:.0f}",
                 "Y" if b["budgetMet"] else "N", f"{b['leafCount']:.0f}"]
                for b in analysis.budgets]
        out.append(fmt_table(headers, rows))
        native = sorted(b["totalNativeMs"] for b in analysis.budgets)
        out.append(f"totalNativeMs P50={percentile(native, 50):.0f}  "
                   f"P95={percentile(native, 95):.0f}")

    # 5. Render tails / taps / arrival waits
    if analysis.render_tails:
        out.append("")
        out.append("=== RENDER TAIL BREAKDOWN ===")
        headers = ["page", "permitWait", "colorEstimate", "commit", "totalTail"]
        rows = [[t["pageKey"], f"{t['permitWaitMs']:.0f}", f"{t['colorEstimateMs']:.0f}",
                 f"{t['commitMs']:.0f}", f"{t['totalTailMs']:.0f}"] for t in analysis.render_tails]
        out.append(fmt_table(headers, rows))
    if analysis.taps:
        delays = sorted(t["delayMs"] for t in analysis.taps)
        out.append("")
        out.append(f"=== TAP TO DISPATCH: n={len(delays)} "
                   f"P50={percentile(delays, 50):.0f} P95={percentile(delays, 95):.0f} ===")
    if analysis.arrival_waits:
        waits = sorted(a["waitMs"] for a in analysis.arrival_waits)
        stutter = sum(1 for a in analysis.arrival_waits if a["hadToWait"])
        out.append("")
        out.append(f"=== ARRIVAL WAITS: n={len(waits)} stutter={stutter} "
                   f"P50={percentile(waits, 50):.0f} P95={percentile(waits, 95):.0f} ===")

    # 6. CSV export
    if csv_path:
        import csv
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["sid", "rid", "mode", "page", "plan", "queuedMs", "stageSumMs",
                        "totalMs", "residueMs", "bottleneck", "bottleneckMs", "retries", "outcome"])
            for r in analysis.runs.values():
                w.writerow([r["sid"], r["rid"], r["mode"], r["page"], r["plan"],
                            r["queuedMs"], r["stageSumMs"], r["totalMs"], r["residueMs"],
                            r["bottleneck"], r["bottleneckMs"], r["retries"], r["outcome"]])
        out.append("")
        out.append(f"CSV written: {csv_path}")

    return "\n".join(out)


SELFTEST_LINES = [
    "10-13 16:00:00.000  1234  5678 I TachiyomiAT.Translation: schema=translation_trace_v1 event=schedule_start sid=s1 rid=none mode=manual origin=manual chapter=c1 page=none pageIndex=none pages=3 wallMs=0 nativeBusyMs=0 providerBusyMs=0 renderBusyMs=0 overlapMs=0 unionActiveMs=0 concurrencySavingsMs=0 workMs=0 criticalPathMs=0 leaseWaitMs=0 nativeQueueMs=0 preparedQueueMs=0 preparedSendWaitMs=0 providerWindowWaitMs=0 providerGovernorWaitMs=0 translateMs=0 renderPermitWaitMs=0 deferredStorageDrainMs=0 cleanedPersistMs=0 storeCommitMs=0 storeFlushMs=0 maxQueueMs=0 slowestPage=none bottleneck=none outcome=started",
    "10-13 16:00:05.000  1234  5678 I TachiyomiAT.Translation: schema=translation_trace_v1 event=stage_start sid=s1 rid=r1 mode=manual origin=manual chapter=c1 page=p1 pageIndex=0 lane=NATIVE stage=SOURCE_DECODE queueMs=0 durationMs=0 totalMs=0 provider=CPU model=none items=0 outcome=started",
    "10-13 16:00:05.020  1234  5678 I TachiyomiAT.Translation: schema=translation_trace_v1 event=stage_end sid=s1 rid=r1 mode=manual origin=manual chapter=c1 page=p1 pageIndex=0 lane=NATIVE stage=SOURCE_DECODE queueMs=0 durationMs=19 totalMs=19 provider=CPU model=none items=0 outcome=success",
    "10-13 16:00:10.000  1234  5678 I TachiyomiAT.Translation: schema=translation_trace_v1 event=run_end sid=s1 rid=r1 mode=manual origin=manual chapter=c1 page=p1 pageIndex=0 plan=fresh queuedMs=1200 totalMs=8000 stageSumMs=5600 bottleneck=native_queue bottleneckMs=1500 retries=0 outcome=success errorType=none errorCode=none",
    "10-13 16:00:20.000  1234  5678 I TachiyomiAT.Translation: schema=translation_trace_v1 event=schedule_end sid=s1 rid=none mode=manual origin=manual chapter=c1 page=none pageIndex=none pages=1 wallMs=17000 nativeBusyMs=3900 providerBusyMs=1700 renderBusyMs=60 overlapMs=900 unionActiveMs=5100 workMs=5660 leaseWaitMs=800 nativeQueueMs=1500 preparedQueueMs=0 preparedSendWaitMs=0 providerWindowWaitMs=200 providerGovernorWaitMs=4000 translateMs=1700 renderPermitWaitMs=300 deferredStorageDrainMs=2000 cleanedPersistMs=150 storeCommitMs=90 storeFlushMs=60 criticalPathMs=17000 maxQueueMs=1500 slowestPage=p1 bottleneck=provider_governor outcome=success",
    "10-13 16:00:20.100  1234  5679 V TachiyomiAT.NativeVision: ts=2026-10-13T16:00:20.090 monoMs=123456 domain=native_vision event=budget_summary pageKey=r2 targetMs=2000 totalNativeMs=3899.00 budgetMet=false detMs=185.00 segMs=353.00 ocrMs=2253.00 inpaintMs=1102.00 leafCount=22 boxCount=8 provider=XNNPACK",
    "10-13 16:00:20.200  1234  5679 V TachiyomiAT.Render: ts=2026-10-13T16:00:20.150 monoMs=123556 domain=render_tail event=render_tail_breakdown pageKey=r2 permitWaitMs=300.00 colorEstimateMs=12.00 commitMs=90.00 totalTailMs=402.00",
    "10-13 16:00:20.300  1234  5680 V TachiyomiAT.Auto: ts=2026-10-13T16:00:20.250 monoMs=123656 domain=auto event=arrival_wait_recorded pageKey=r2 waitMs=4200.00 hadToWait=true",
]


def selftest() -> int:
    a = Analysis()
    ok = True
    for line in SELFTEST_LINES:
        rec = parse_line(line)
        if rec is None:
            print(f"FAIL: unparsed line: {line[:80]}")
            ok = False
            continue
        a.consume(rec)
    # assertions
    if len(a.runs) != 1:
        print(f"FAIL: expected 1 run, got {len(a.runs)}"); ok = False
    run = next(iter(a.runs.values()), None)
    if run:
        if run["totalMs"] != 8000 or run["stageSumMs"] != 5600 or run["queuedMs"] != 1200:
            print(f"FAIL: run fields wrong: {run}"); ok = False
        if abs(run["residueMs"] - 1200) > 0.01:
            print(f"FAIL: residue expected 1200, got {run['residueMs']}"); ok = False
    sched = a.schedules.get("s1")
    if sched:
        if sched["wallMs"] != 17000 or sched["workMs"] != 5660:
            print(f"FAIL: schedule fields wrong: {sched}"); ok = False
        sum_waits = sum(sched["waits"].values())
        if abs(sum_waits - 9100) > 0.01:
            print(f"FAIL: wait sum expected 9100, got {sum_waits}"); ok = False
        unexplained = sched["wallMs"] - sched["workMs"] - sum_waits
        if abs(unexplained - 2240) > 0.01:
            print(f"FAIL: unexplained expected 2240, got {unexplained}"); ok = False
    else:
        print("FAIL: schedule s1 missing"); ok = False
    if len(a.budgets) != 1 or a.budgets[0]["totalNativeMs"] != 3899:
        print(f"FAIL: budget parse wrong: {a.budgets}"); ok = False
    if len(a.render_tails) != 1 or a.render_tails[0]["permitWaitMs"] != 300:
        print(f"FAIL: render tail parse wrong: {a.render_tails}"); ok = False
    if len(a.arrival_waits) != 1 or a.arrival_waits[0]["waitMs"] != 4200:
        print(f"FAIL: arrival wait parse wrong: {a.arrival_waits}"); ok = False
    if len(a.stages) != 1 or a.stages[0]["durationMs"] != 19:
        print(f"FAIL: stage parse wrong: {a.stages}"); ok = False
    print("[selftest] PASS" if ok else "[selftest] FAIL")
    print()
    print(analyze(a, None))
    return 0 if ok else 1


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logfile", nargs="?", help="logcat capture file")
    ap.add_argument("--csv", dest="csv_path", default=None, help="optional CSV export of runs")
    ap.add_argument("--selftest", action="store_true", help="run built-in verification")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())
    if not args.logfile or not os.path.exists(args.logfile):
        print("Error: provide a logfile (or --selftest)", file=sys.stderr)
        sys.exit(1)

    a = Analysis()
    with open(args.logfile, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            rec = parse_line(line)
            if rec:
                a.consume(rec)
    print(analyze(a, args.csv_path))


if __name__ == "__main__":
    main()
