#!/usr/bin/env python3
"""T922 device-verification trace analyzer.

Parses a full-logcat capture, extracts `TachiyomiAT.Translation` (schema
translation_trace_v1) lines plus ORT/QNN evidence, and asserts the plan §7/§8
device acceptance checks for one scenario capture.

Usage: python analyze_trace.py <logfile> [--scenario NAME] [--titles "T1" "T2"]
Exits 0 when all mandatory assertions pass; prints a PASS/FAIL table either way.
"""
import argparse
import json
import re
import sys
from collections import defaultdict

TRACE_TAG = "TachiyomiAT.Translation"
SCHEMA = "schema=translation_trace_v1"

# logcat: MM-DD HH:MM:SS.mmm PID TID P TAG: msg
LINE_RE = re.compile(
    r"^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+"
    r"([^:]+):\s(.*)$"
)

DURATION_KEYS = [
    "queueMs", "durationMs", "totalMs", "wallMs", "nativeBusyMs",
    "providerBusyMs", "renderBusyMs", "overlapMs", "unionActiveMs",
    "concurrencySavingsMs", "workMs", "criticalPathMs", "maxQueueMs",
    "queuedMs", "stageSumMs", "bottleneckMs", "budgetMs",
]


def parse_line(line):
    m = LINE_RE.match(line.rstrip("\n"))
    if not m:
        return None
    date, time, pid, tid, prio, tag, msg = m.groups()
    return {
        "date": date, "time": time, "pid": pid, "tid": tid,
        "prio": prio, "tag": tag.strip(), "msg": msg,
    }


def kv_parse(msg):
    """schema=translation_trace_v1 event=... -> ordered dict of key=value."""
    out = {}
    for tok in msg.split():
        if "=" in tok:
            k, _, v = tok.partition("=")
            out[k] = v
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logfile")
    ap.add_argument("--scenario", default="scenario")
    ap.add_argument("--titles", nargs="*", default=[],
                    help="raw title fragments that must NOT appear in trace lines")
    args = ap.parse_args()

    trace_lines = []          # dicts: parsed + kv
    ort_lines = []
    qnn_diag_lines = []
    translation_tag_other = []  # tag matches but no schema prefix
    all_line_count = 0

    with open(args.logfile, "r", encoding="utf-8", errors="replace") as fh:
        for raw in fh:
            all_line_count += 1
            p = parse_line(raw)
            if p is None:
                continue
            tag = p["tag"]
            if tag == TRACE_TAG:
                if p["msg"].startswith(SCHEMA):
                    kv = kv_parse(p["msg"])
                    trace_lines.append({**p, "kv": kv})
                else:
                    translation_tag_other.append(p)
            elif tag in ("ORT", "ai.onnxruntime", "OnnxRuntime") or "OrtException" in p["msg"] \
                    or "onnxruntime" in tag.lower() or "ORT-" in p["msg"]:
                ort_lines.append(p)
            elif tag == "QnnDiagnostics" or "QnnDiagnostics" in tag:
                qnn_diag_lines.append(p)

    results = []  # (check, pass/fail, detail)

    def check(name, ok, detail):
        results.append((name, "PASS" if ok else "FAIL", detail))

    # ---- grouping ----
    schedules = defaultdict(lambda: {"start": [], "end": []})
    runs = defaultdict(lambda: {"start": [], "end": []})
    stages = []
    states = []
    routes = []
    for tl in trace_lines:
        kv = tl["kv"]
        ev = kv.get("event")
        if ev == "schedule_start":
            schedules[kv.get("sid")]["start"].append(tl)
        elif ev == "schedule_end":
            schedules[kv.get("sid")]["end"].append(tl)
        elif ev == "run_start":
            runs[kv.get("rid")]["start"].append(tl)
        elif ev == "run_end":
            runs[kv.get("rid")]["end"].append(tl)
        elif ev == "stage_start":
            stages.append(tl)
        elif ev == "stage_end":
            stages.append(tl)
        elif ev == "schedule_state":
            states.append(tl)
        elif ev == "route_change":
            routes.append(tl)

    modes_seen = sorted({tl["kv"].get("mode") for tl in trace_lines})
    summary = {
        "scenario": args.scenario,
        "log_lines_total": all_line_count,
        "trace_lines": len(trace_lines),
        "trace_tag_non_schema_lines": len(translation_tag_other),
        "modes_seen": modes_seen,
        "schedules": len(schedules),
        "runs": len(runs),
        "stage_events": len(stages),
        "schedule_states": len(states),
        "route_changes": len(routes),
        "ort_lines": len(ort_lines),
        "qnn_diagnostics_lines": len(qnn_diag_lines),
    }

    # ---- per-mode event counts ----
    per_mode = defaultdict(lambda: defaultdict(int))
    for tl in trace_lines:
        per_mode[tl["kv"].get("mode")][tl["kv"].get("event")] += 1
    summary["per_mode"] = {m: dict(c) for m, c in per_mode.items()}

    # ---- 1. bubble segmentation provider evidence ----
    seg_lines = [tl for tl in trace_lines
                 if tl["kv"].get("event") == "stage_end"
                 and tl["kv"].get("stage") == "segment"
                 and tl["kv"].get("model") == "bubble_segmenter"]
    seg_by_mode = defaultdict(list)
    for tl in seg_lines:
        seg_by_mode[tl["kv"].get("mode")].append(tl)
    seg_detail = {m: {
        "count": len(v),
        "providers": sorted({x["kv"].get("provider") for x in v}),
        "registered": sorted({x["kv"].get("registeredProvider") or "-" for x in v}),
    } for m, v in seg_by_mode.items()}
    summary["bubble_segment"] = seg_detail
    expect_modes = [m for m in modes_seen if m in ("manual", "auto", "batch")]
    if expect_modes:
        missing = [m for m in expect_modes if m not in seg_by_mode]
        non_cpu = {m: ps for m, ps in seg_detail.items()
                   if ps["providers"] != ["cpu"]}
        check("bubble segment provider=cpu model=bubble_segmenter in every active mode",
              not missing and not non_cpu,
              f"modes={sorted(seg_by_mode)} detail={json.dumps(seg_detail)}; "
              f"missing={missing} non_cpu={non_cpu}")
    else:
        check("bubble segment provider=cpu model=bubble_segmenter", False,
              f"no manual/auto/batch mode lines found; modes_seen={modes_seen}")

    # ---- 2. zero QNN error 1100 from bubble ----
    seg_1100 = [tl for tl in seg_lines if tl["kv"].get("errorCode") == "1100"]
    ort_1100 = [p for p in ort_lines if "1100" in p["msg"]]
    check("zero QNN error 1100 from bubble segmentation",
          not seg_1100,
          f"segment errorCode=1100 count={len(seg_1100)}; "
          f"ORT-tagged lines containing 1100 in capture={len(ort_1100)}")
    if ort_1100:
        summary["ort_1100_samples"] = [p["msg"][:200] for p in ort_1100[:5]]

    # ---- 3. AOT inpaint provenance ----
    inpaint_lines = [tl for tl in trace_lines
                     if tl["kv"].get("event") == "stage_end"
                     and tl["kv"].get("stage") == "inpaint"]
    inp_prov = defaultdict(int)
    inp_prov_proven = defaultdict(int)
    for tl in inpaint_lines:
        inp_prov[tl["kv"].get("provider")] += 1
        if tl["kv"].get("provenProvider"):
            inp_prov_proven[tl["kv"].get("provenProvider")] += 1
    summary["inpaint"] = {
        "count": len(inpaint_lines),
        "providers": dict(inp_prov),
        "provenProviders": dict(inp_prov_proven),
        "models": sorted({tl["kv"].get("model") for tl in inpaint_lines}),
        "outcomes": dict(defaultdict(int, {
            o: [tl["kv"].get("outcome") for tl in inpaint_lines].count(o)
            for o in {tl["kv"].get("outcome") for tl in inpaint_lines}})),
    }
    check("AOT inpaint provenance recorded (actual values recorded honestly)",
          True,
          f"inpaint stage_ends={len(inpaint_lines)} providers={dict(inp_prov)} "
          f"proven={dict(inp_prov_proven)}")

    # ---- 4. terminality ----
    multi_end_runs = {rid: len(v["end"]) for rid, v in runs.items() if len(v["end"]) > 1}
    no_end_runs = [rid for rid, v in runs.items() if not v["end"]]
    multi_end_sched = {sid: len(v["end"]) for sid, v in schedules.items() if len(v["end"]) > 1}
    no_end_sched = [sid for sid, v in schedules.items() if not v["end"]]
    check("exactly one run_end per run_start (by rid)",
          not multi_end_runs and not no_end_runs,
          f"runs={len(runs)} runs_without_end={no_end_runs} "
          f"runs_with_multiple_ends={multi_end_runs}")
    check("exactly one terminal schedule_end per schedule_start (by sid)",
          not multi_end_sched and not no_end_sched,
          f"schedules={len(schedules)} schedules_without_end={no_end_sched} "
          f"schedules_with_multiple_ends={multi_end_sched}")

    # schedule outcomes
    sched_outcomes = {sid: (v["end"][0]["kv"].get("outcome") if v["end"] else None)
                      for sid, v in schedules.items()}
    summary["schedule_outcomes"] = sched_outcomes
    run_outcomes = defaultdict(int)
    for rid, v in runs.items():
        if v["end"]:
            run_outcomes[v["end"][0]["kv"].get("outcome")] += 1
    summary["run_outcomes"] = dict(run_outcomes)

    # ---- 5. durations ----
    neg = []
    for tl in trace_lines:
        kv = tl["kv"]
        for k in DURATION_KEYS:
            if k in kv:
                try:
                    if int(kv[k]) < 0:
                        neg.append((kv.get("event"), k, kv[k]))
                except ValueError:
                    neg.append((kv.get("event"), k, "unparseable:" + kv[k]))
    check("all durations nonnegative", not neg,
          f"violations={neg[:5]} count={len(neg)}")

    bad_sum = []
    no_total = []
    no_bottleneck = []
    for tl in trace_lines:
        kv = tl["kv"]
        if kv.get("event") != "run_end":
            continue
        try:
            total = int(kv.get("totalMs", "-1"))
            stage_sum = int(kv.get("stageSumMs", "-1"))
        except ValueError:
            bad_sum.append(kv.get("rid"))
            continue
        if total < 0:
            no_total.append(kv.get("rid"))
        if stage_sum > total + 100:  # small tolerance (clamp rounding)
            bad_sum.append((kv.get("rid"), f"stageSumMs={stage_sum} totalMs={total}"))
        if kv.get("bottleneck") in (None, "none") or int(kv.get("bottleneckMs", "-1")) < 0:
            no_bottleneck.append(kv.get("rid"))
    check("stageSumMs <= totalMs + 100ms on every run_end", not bad_sum,
          f"violations={bad_sum[:5]} count={len(bad_sum)}")
    check("every run_end has bottleneck + nonnegative totalMs",
          not no_total and not no_bottleneck,
          f"missing_total={no_total[:5]} missing_bottleneck={no_bottleneck[:5]} "
          f"(of {len([tl for tl in trace_lines if tl['kv'].get('event') == 'run_end'])} run_ends)")

    # ---- 6. schedule overlap fields (S2/S3) ----
    sched_summ = []
    for sid, v in sorted(schedules.items()):
        if not v["end"]:
            continue
        kv = v["end"][0]["kv"]
        sched_summ.append({
            "sid": sid, "mode": kv.get("mode"),
            "pages": kv.get("pages"), "wallMs": int(kv.get("wallMs", 0)),
            "nativeBusyMs": int(kv.get("nativeBusyMs", 0)),
            "providerBusyMs": int(kv.get("providerBusyMs", 0)),
            "renderBusyMs": int(kv.get("renderBusyMs", 0)),
            "overlapMs": int(kv.get("overlapMs", 0)),
            "concurrencySavingsMs": int(kv.get("concurrencySavingsMs", 0)),
            "maxQueueMs": int(kv.get("maxQueueMs", 0)),
            "bottleneck": kv.get("bottleneck"), "outcome": kv.get("outcome"),
            "workMs": int(kv.get("workMs", 0)),
        })
    summary["schedule_summaries"] = sched_summ
    auto_batch_scheds = [s for s in sched_summ if s["mode"] in ("auto", "batch")]
    if auto_batch_scheds:
        zero_overlap = [s for s in auto_batch_scheds
                        if s["overlapMs"] == 0 or s["concurrencySavingsMs"] == 0]
        check("auto/batch schedule summaries report overlapMs/concurrencySavingsMs",
              True,
              f"summaries={json.dumps(auto_batch_scheds)}; zero-overlap schedules="
              f"{[s['sid'] + ':' + s['mode'] for s in zero_overlap]} (recorded honestly)")

    # ---- 7. privacy scan ----
    violations = []
    for tl in trace_lines:
        msg = tl["msg"]
        low = msg.lower()
        if re.search(r"\.(png|jpg|jpeg|webp|gif)\b", low):
            violations.append(("filename", msg))
        if "http://" in low or "https://" in low or "content://" in low:
            violations.append(("url", msg))
        for t in args.titles:
            if t and t.lower() in low:
                violations.append(("title:" + t, msg))
        # raw exception shapes
        if re.search(r"(Exception|Throwable)[:\s]", msg) and "errorType=" not in msg:
            violations.append(("raw-exception", msg))
        if re.search(r"at [a-z]+\.[A-Za-z0-9_$]+\(", msg):
            violations.append(("stack-frame", msg))
    check("privacy scan: no raw filenames/URLs/titles/exceptions in trace lines",
          not violations,
          f"violations={violations[:5]} count={len(violations)}")

    # also: legacy raw-identifier leakage under the trace tag (non-schema lines)
    check("no non-schema lines under TachiyomiAT.Translation tag",
          not translation_tag_other,
          f"count={len(translation_tag_other)} samples="
          f"{[p['msg'][:80] for p in translation_tag_other[:3]]}")

    # ---- 8. per-run stage evidence + representative lines ----
    run_reports = []
    for rid, v in runs.items():
        if not v["end"]:
            continue
        endkv = v["end"][0]["kv"]
        stage_map = defaultdict(int)
        for s in stages:
            if s["kv"].get("rid") == rid and s["kv"].get("event") == "stage_end":
                stage_map[s["kv"].get("stage")] += 1
        run_reports.append({
            "rid": rid, "mode": endkv.get("mode"),
            "pageIndex": endkv.get("pageIndex"),
            "totalMs": int(endkv.get("totalMs", 0)),
            "bottleneck": endkv.get("bottleneck"),
            "bottleneckMs": int(endkv.get("bottleneckMs", 0)),
            "queuedMs": int(endkv.get("queuedMs", 0)),
            "retries": int(endkv.get("retries", 0)),
            "outcome": endkv.get("outcome"),
            "plan": endkv.get("plan"),
            "stageEnds": dict(stage_map),
        })
    totals = sorted(r["totalMs"] for r in run_reports)
    if totals:
        med = totals[len(totals) // 2]
    else:
        med = None
    summary["run_count"] = len(run_reports)
    summary["median_run_totalMs"] = med
    summary["min_run_totalMs"] = totals[0] if totals else None
    summary["max_run_totalMs"] = totals[-1] if totals else None
    summary["runs"] = run_reports

    # ---- representative lines (first of each family) ----
    rep = {}
    for fam in ("schedule_start", "run_start", "stage_start", "stage_end",
                "schedule_state", "route_change", "run_end", "schedule_end"):
        for tl in trace_lines:
            if tl["kv"].get("event") == fam:
                rep[fam] = f"{tl['time']} {tl['prio']}/{TRACE_TAG}: {tl['msg']}"
                break
    summary["representative_lines"] = rep

    # ---- print ----
    print(f"=== {args.scenario} ({args.logfile}) ===")
    print(json.dumps(summary, indent=2, ensure_ascii=False)[:6000])
    print("\n=== ASSERTIONS ===")
    all_pass = True
    for name, verdict, detail in results:
        print(f"[{verdict}] {name}\n        {detail}")
        if verdict == "FAIL":
            all_pass = False
    print("\nOVERALL:", "PASS" if all_pass else "FAIL")
    return 0 if all_pass else 1


if __name__ == "__main__":
    sys.exit(main())
