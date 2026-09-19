#!/usr/bin/env python3
"""Host-side discrete-event study of a PP-OCRv6 page pipeline.

This is a research model, not Android production code.  It models one decode
worker, one detector, one crop extractor and one recognition session, with
bounded queues between stages.  The timing profile is explicitly marked as
modelled; an optional host ONNX timing JSON is copied into the result for
comparison and is never presented as Android evidence.

The useful property of this small simulator is that policy changes are cheap:
batch size, width buckets, queue bounds, cancellation and priority behaviour
can be compared without moving production code or page binaries.
"""

from __future__ import annotations

import argparse
import heapq
import json
import math
import random
import statistics
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable


WIDTH_BUCKETS = (320, 640, 960, 1280, 1600)
PRIORITY = {"manual": 0, "auto": 1, "batch": 2}


@dataclass(frozen=True)
class TimingProfile:
    source: str = "modelled_android_initial_budget"
    decode_ms: float = 42.0
    det_ms: float = 68.0
    crop_base_ms: float = 3.0
    crop_per_region_ms: float = 0.7
    rec_launch_ms: float = 12.0
    rec_per_item_ms: float = 10.0
    rec_width_exponent: float = 0.50
    rec_batch_efficiency: float = 0.68
    cpu_factor: float = 1.0
    nnapi_factor: float = 0.62
    thermal_factor: float = 1.0


@dataclass(frozen=True)
class Page:
    page: int
    mode: str
    regions: int
    widths: tuple[int, ...]


@dataclass
class Crop:
    page: int
    crop: int
    width: int
    bucket: int
    mode: str
    priority: int
    enqueued_ms: float = 0.0
    status: str = "queued"


@dataclass
class Event:
    at: float
    seq: int
    kind: str
    payload: Any = field(compare=False)

    def __lt__(self, other: "Event") -> bool:
        return (self.at, self.seq) < (other.at, other.seq)


def width_bucket(width: int) -> int:
    for bucket in WIDTH_BUCKETS:
        if width <= bucket:
            return bucket
    return WIDTH_BUCKETS[-1]


def make_pages(count: int, seed: int) -> list[Page]:
    rng = random.Random(seed)
    pages: list[Page] = []
    for page in range(count):
        # Deliberately mixed page density and widths: this prevents a policy
        # from looking good only on a uniform synthetic chapter.
        regions = max(2, min(20, int(rng.gauss(8.0, 3.0))))
        widths = tuple(rng.choice((190, 260, 320, 430, 560, 700, 900, 1120, 1450)) for _ in range(regions))
        if page == 0:
            mode = "manual"
        elif page < max(4, count // 8):
            mode = "auto"
        else:
            mode = "batch"
        pages.append(Page(page, mode, regions, widths))
    return pages


def make_pages_from_manifest(path: Path, count: int) -> list[Page]:
    """Build page geometry from the fixed path-only fixture manifest.

    Only region boxes are read; page binaries remain external to this
    worktree.  The width proxy is the larger side after the same orientation
    normalization used by recognition, then clamped to the model's supported
    range.  This is not a detector run, but it gives the scheduler real page
    density/geometry instead of a uniform synthetic chapter.
    """
    manifest = json.loads(path.read_text(encoding="utf-8"))
    pages: list[Page] = []
    for chapter in manifest.get("chapters", []):
        for raw in chapter.get("pages", []):
            if len(pages) >= count:
                return pages
            widths: list[int] = []
            for region in raw.get("regions", []):
                box = region.get("ocr_box") or region.get("box") or []
                if len(box) != 4:
                    continue
                w = abs(int(box[2]) - int(box[0]))
                h = abs(int(box[3]) - int(box[1]))
                # Rotation for tall CJK crops makes the long side the REC
                # width; cap only at the production recognition maximum.
                widths.append(max(180, min(1600, max(w, h))))
            if not widths:
                widths = [320]
            mode = "manual" if not pages else "auto" if len(pages) < max(4, count // 8) else "batch"
            pages.append(Page(len(pages), mode, len(widths), tuple(widths)))
    return pages


def make_crops(page: Page) -> list[Crop]:
    priority = PRIORITY[page.mode]
    return [
        Crop(page.page, index, width, width_bucket(width), page.mode, priority)
        for index, width in enumerate(page.widths)
    ]


def read_host_measurement(path: Path | None) -> dict[str, Any] | None:
    if not path or not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


class Pipeline:
    def __init__(
        self,
        pages: list[Page],
        profile: TimingProfile,
        *,
        policy: str,
        queue_caps: dict[str, int],
        accelerator_requested: str,
        accelerator_available: bool,
        thermal_limit_c: float | None = None,
        thermal_start_c: float = 31.0,
        thermal_rise_per_s: float = 0.80,
        cancel_at_ms: float | None = None,
        checkpoint: dict[str, Any] | None = None,
    ) -> None:
        self.pages = pages
        self.profile = profile
        self.policy = policy
        self.queue_caps = queue_caps
        self.accelerator_requested = accelerator_requested
        self.accelerator_available = accelerator_available
        self.thermal_limit_c = thermal_limit_c
        self.thermal_c = float((checkpoint or {}).get("thermal_c", thermal_start_c))
        self.thermal_rise_per_s = thermal_rise_per_s
        self.cancel_at_ms = cancel_at_ms
        self.checkpoint = checkpoint or {"pages": {}}
        self.now = 0.0
        self.seq = 0
        self.events: list[Event] = []
        self.decode_q: list[Page] = []
        self.det_q: list[tuple[Page, list[Crop]]] = []
        self.rec_q: list[Crop] = []
        self.busy = {"decode": False, "det": False, "crop": False, "rec": False}
        self.next_page = 0
        self.cancelled = False
        self.counters = {"decode": 0, "det": 0, "crop": 0, "rec_batches": 0, "rec_crops": 0}
        self.batch_log: list[dict[str, Any]] = []
        self.page_status: dict[int, dict[str, Any]] = {}
        self.queue_high_water = {"decode_to_det": 0, "det_to_crop": 0, "crop_to_rec": 0}
        self.backpressure_events = 0
        self._restore_checkpoint()

    @property
    def accelerator_selected(self) -> str:
        if self.accelerator_requested in ("cpu", "none"):
            return "cpu"
        return self.accelerator_requested if self.accelerator_available else "cpu"

    @property
    def fallback_reason(self) -> str | None:
        if self.accelerator_requested in ("cpu", "none"):
            return None
        if not self.accelerator_available:
            return f"{self.accelerator_requested}_unavailable_in_host_simulation"
        return None

    def _restore_checkpoint(self) -> None:
        records = self.checkpoint.get("pages", {})
        for page in self.pages:
            record = records.get(str(page.page), {})
            self.page_status[page.page] = {
                "decode": bool(record.get("decode", False)),
                "det": bool(record.get("det", False)),
                "crop": bool(record.get("crop", False)),
                "rec_done": int(record.get("rec_done", 0)),
                "total": page.regions,
            }
        # A resumed run starts at the first page not fully recognized.  This
        # is intentionally conservative: incomplete pages may be replayed,
        # while fully committed pages are never decoded again.
        self.next_page = next((p.page for p in self.pages if self.page_status[p.page]["rec_done"] < p.regions), len(self.pages))

    def schedule(self, at: float, kind: str, payload: Any = None) -> None:
        self.seq += 1
        heapq.heappush(self.events, Event(at, self.seq, kind, payload))

    def queue_size(self, name: str) -> int:
        return {"decode": len(self.decode_q), "det": len(self.det_q), "rec": len(self.rec_q)}[name]

    def allowed_factor(self) -> float:
        factor = self.profile.cpu_factor if self.accelerator_selected == "cpu" else self.profile.nnapi_factor
        if self.thermal_limit_c is not None and self.thermal_c >= self.thermal_limit_c:
            return factor * self.profile.thermal_factor
        return factor

    def duration(self, stage: str, payload: Any) -> float:
        factor = self.allowed_factor()
        if stage == "decode":
            return self.profile.decode_ms * factor
        if stage == "det":
            return self.profile.det_ms * factor
        if stage == "crop":
            return (self.profile.crop_base_ms + self.profile.crop_per_region_ms * payload.regions) * factor
        crops: list[Crop] = payload
        width = max(c.bucket for c in crops)
        batch = len(crops)
        per_item = self.profile.rec_per_item_ms * (width / WIDTH_BUCKETS[0]) ** self.profile.rec_width_exponent
        # Efficiency is a bounded model of one ONNX session: larger batches
        # amortize launch overhead but never get free linear speed-up.
        return (self.profile.rec_launch_ms + batch * per_item * (self.profile.rec_batch_efficiency + (1.0 - self.profile.rec_batch_efficiency) / batch)) * factor

    def can_start(self, stage: str) -> bool:
        return not self.busy[stage]

    def start_decode(self) -> bool:
        if not self.can_start("decode") or self.next_page >= len(self.pages):
            return False
        page = self.pages[self.next_page]
        record = self.page_status[page.page]
        if record["rec_done"] >= page.regions:
            self.next_page += 1
            return True
        if len(self.decode_q) >= self.queue_caps["decode_to_det"]:
            return False
        self.next_page += 1
        self.busy["decode"] = True
        self.counters["decode"] += 1
        self.schedule(self.now + self.duration("decode", page), "decode_done", page)
        return True

    def start_det(self) -> bool:
        if not self.can_start("det") or not self.decode_q or len(self.det_q) >= self.queue_caps["det_to_crop"]:
            return False
        page = self.decode_q.pop(0)
        self.busy["det"] = True
        self.counters["det"] += 1
        self.schedule(self.now + self.duration("det", page), "det_done", page)
        return True

    def start_crop(self) -> bool:
        if not self.can_start("crop") or not self.det_q:
            return False
        page, crops = self.det_q[0]
        if len(self.rec_q) + len(crops) > self.queue_caps["crop_to_rec"]:
            return False
        self.det_q.pop(0)
        self.busy["crop"] = True
        self.counters["crop"] += 1
        self.schedule(self.now + self.duration("crop", page), "crop_done", (page, crops))
        return True

    def batch_limit(self, bucket: int, mode: str) -> int:
        if self.policy == "serial":
            return 1
        if mode == "manual":
            return 1
        if mode == "auto":
            return 4 if bucket <= 640 else 2
        return 8 if bucket <= 320 else 4 if bucket <= 960 else 2

    def pick_batch(self) -> list[Crop]:
        if self.busy["rec"] or not self.rec_q:
            return []
        ordered = sorted(self.rec_q, key=lambda c: (c.priority, c.enqueued_ms, c.page, c.crop))
        first = ordered[0]
        # Manual/auto work is never held behind a batch crop.  The selected
        # bucket is the first crop's bucket; this is the width-bucket policy.
        same = [c for c in ordered if c.bucket == first.bucket]
        if self.policy == "naive_pad":
            same = [c for c in ordered if c.priority == first.priority]
        limit = self.batch_limit(first.bucket, first.mode)
        selected = same[:limit]
        for crop in selected:
            self.rec_q.remove(crop)
        return selected

    def start_rec(self) -> bool:
        batch = self.pick_batch()
        if not batch:
            return False
        self.busy["rec"] = True
        self.counters["rec_batches"] += 1
        self.counters["rec_crops"] += len(batch)
        for crop in batch:
            crop.status = "running"
        duration = self.duration("rec", batch)
        self.schedule(self.now + duration, "rec_done", batch)
        self.batch_log.append({
            "start_ms": self.now,
            "end_ms": self.now + duration,
            "count": len(batch),
            "bucket": max(c.bucket for c in batch),
            "modes": sorted({c.mode for c in batch}),
            "pages": sorted({c.page for c in batch}),
        })
        return True

    def pump(self) -> None:
        # Repeatedly fill the pipeline until every stage is either busy or
        # blocked by a bounded queue.  This is the backpressure point.
        changed = True
        while changed and not self.cancelled:
            changed = False
            changed |= self.start_decode()
            changed |= self.start_det()
            changed |= self.start_crop()
            changed |= self.start_rec()
        current = {
            "decode_to_det": len(self.decode_q),
            "det_to_crop": len(self.det_q),
            "crop_to_rec": len(self.rec_q),
        }
        for name, size in current.items():
            self.queue_high_water[name] = max(self.queue_high_water[name], size)
        # A full edge with its producer idle is evidence that backpressure
        # actually constrained admission, rather than merely being configured.
        if (not self.busy["decode"] and self.next_page < len(self.pages) and len(self.decode_q) >= self.queue_caps["decode_to_det"]):
            self.backpressure_events += 1
        if (not self.busy["crop"] and self.det_q and len(self.rec_q) + self.det_q[0][0].regions > self.queue_caps["crop_to_rec"]):
            self.backpressure_events += 1

    def checkpoint_snapshot(self) -> dict[str, Any]:
        return {
            "at_ms": round(self.now, 3),
            "thermal_c": round(self.thermal_c, 3),
            "pages": {str(page): dict(status) for page, status in self.page_status.items()},
            "queues": {"decode_to_det": len(self.decode_q), "det_to_crop": len(self.det_q), "crop_to_rec": len(self.rec_q)},
            "in_flight": dict(self.busy),
        }

    def handle(self, event: Event) -> None:
        self.now = event.at
        # ``thermal_rise_per_s`` is expressed in °C/s, while the event clock
        # is milliseconds.  Keeping the unit conversion here avoids the easy
        # mistake of heating the simulated device hundreds of degrees per run.
        self.thermal_c += max(0.0, self.now - getattr(self, "last_time", 0.0)) * self.thermal_rise_per_s / 1000.0
        self.last_time = self.now
        if event.kind == "decode_done":
            page: Page = event.payload
            self.busy["decode"] = False
            self.decode_q.append(page)
            self.page_status[page.page]["decode"] = True
        elif event.kind == "det_done":
            page = event.payload
            self.busy["det"] = False
            self.det_q.append((page, make_crops(page)))
            self.page_status[page.page]["det"] = True
        elif event.kind == "crop_done":
            page, crops = event.payload
            self.busy["crop"] = False
            for crop in crops:
                crop.enqueued_ms = self.now
                self.rec_q.append(crop)
            self.page_status[page.page]["crop"] = True
        elif event.kind == "rec_done":
            batch: list[Crop] = event.payload
            self.busy["rec"] = False
            for crop in batch:
                crop.status = "done"
                self.page_status[crop.page]["rec_done"] += 1
            for page in self.pages:
                if self.page_status[page.page]["rec_done"] >= page.regions:
                    self.page_status[page.page]["status"] = "complete"

    def run(self) -> dict[str, Any]:
        self.last_time = 0.0
        self.pump()
        while self.events and not self.cancelled:
            event = heapq.heappop(self.events)
            if self.cancel_at_ms is not None and event.at >= self.cancel_at_ms:
                self.now = self.cancel_at_ms
                self.cancelled = True
                break
            self.handle(event)
            self.pump()
        if self.cancelled:
            status = "cancelled_checkpointed"
            checkpoint = self.checkpoint_snapshot()
        else:
            status = "complete"
            checkpoint = self.checkpoint_snapshot()
        complete_pages = sum(1 for s in self.page_status.values() if s.get("rec_done", 0) >= s["total"])
        return {
            "status": status,
            "policy": self.policy,
            "pages": len(self.pages),
            "complete_pages": complete_pages,
            "elapsed_ms": round(self.now, 3),
            "thermal_end_c": round(self.thermal_c, 3),
            "accelerator_requested": self.accelerator_requested,
            "accelerator_selected": self.accelerator_selected,
            "fallback_reason": self.fallback_reason,
            "queue_caps": self.queue_caps,
            "queue_high_water": self.queue_high_water,
            "backpressure_events": self.backpressure_events,
            "counters": self.counters,
            "checkpoint": checkpoint,
            "batch_log": self.batch_log,
        }


def serial_reference(pages: list[Page], profile: TimingProfile) -> dict[str, Any]:
    total = 0.0
    for page in pages:
        total += profile.decode_ms + profile.det_ms + profile.crop_base_ms + profile.crop_per_region_ms * page.regions
        for crop in make_crops(page):
            total += profile.rec_launch_ms + profile.rec_per_item_ms * (crop.bucket / 320) ** profile.rec_width_exponent
    return {"status": "complete", "policy": "serial_reference", "pages": len(pages), "elapsed_ms": round(total, 3), "complete_pages": len(pages)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pages", type=int, default=44)
    parser.add_argument("--seed", type=int, default=924)
    parser.add_argument("--out", type=Path, default=Path("research/results/pipeline_simulation.json"))
    parser.add_argument("--host-timing", type=Path, default=Path("research/results/host_onnx_timing.json"))
    parser.add_argument("--manifest", type=Path, default=Path("research/dataset/manifest.json"))
    args = parser.parse_args()

    fixture_source = "synthetic_seeded"
    manifest_coverage_pages = None
    manifest_missing_pages = None
    if args.manifest.exists():
        manifest_data = json.loads(args.manifest.read_text(encoding="utf-8"))
        manifest_coverage_pages = sum(len(chapter.get("pages", [])) for chapter in manifest_data.get("chapters", []))
        # The current external inventory reports 44 readable page binaries;
        # the remaining 32 manifest entries are retained as coverage metadata
        # but are not silently treated as runnable inputs.  ``--pages`` is
        # still useful for sensitivity runs, but the default and fixed-corpus
        # result below are the 44-page accessible subset.
        manifest_missing_pages = 32
        pages = make_pages_from_manifest(args.manifest, args.pages)
        if len(pages) == args.pages:
            fixture_source = (
                "path_only_real_page_manifest_accessible_subset"
                if args.pages <= 44
                else "path_only_manifest_geometry_sensitivity_includes_unreadable_entries"
            )
        else:
            pages = make_pages(args.pages, args.seed)
    else:
        pages = make_pages(args.pages, args.seed)
    profile = TimingProfile()
    caps = {"decode_to_det": 2, "det_to_crop": 2, "crop_to_rec": 32}
    results: list[dict[str, Any]] = [serial_reference(pages, profile)]
    for policy in ("width_bucket", "naive_pad"):
        results.append(Pipeline(pages, profile, policy=policy, queue_caps=caps, accelerator_requested="cpu", accelerator_available=False).run())

    first = Pipeline(
        pages,
        profile,
        policy="width_bucket",
        queue_caps=caps,
        accelerator_requested="nnapi",
        accelerator_available=False,
        thermal_limit_c=39.0,
        thermal_rise_per_s=1.50,
        cancel_at_ms=3_000.0,
    ).run()
    resumed = Pipeline(
        pages,
        profile,
        policy="width_bucket",
        queue_caps=caps,
        accelerator_requested="nnapi",
        accelerator_available=False,
        thermal_limit_c=39.0,
        thermal_rise_per_s=1.50,
        checkpoint=first["checkpoint"],
    ).run()

    host = read_host_measurement(args.host_timing)
    output = {
        "schema": "ppocrv6_pipeline_simulation.v1",
        "generated_by": "research/pipeline_simulation.py",
        "inputs": {
            "pages": args.pages,
            "seed": args.seed,
            "fixture_source": fixture_source,
            "manifest": str(args.manifest) if "manifest" in fixture_source else None,
            "manifest_coverage_pages": manifest_coverage_pages,
            "manifest_missing_pages": manifest_missing_pages,
            "readable_pages_modelled": len(pages),
            "regions": {"mean": round(statistics.mean(p.regions for p in pages), 3), "total": sum(p.regions for p in pages)},
            "modes": {mode: sum(1 for p in pages if p.mode == mode) for mode in PRIORITY},
            "timing_profile": asdict(profile),
            "timing_profile_status": "modelled_android_initial_budget",
            "queue_caps": caps,
            "width_buckets": list(WIDTH_BUCKETS),
        },
        "host_measurement": host,
        "runs": results,
        "cancellation_resume": {"initial": first, "resumed": resumed, "status": "checkpoint_resume_exercised"},
        "interpretation": {
            "measured": ["host_measurement"],
            "modelled": ["runs", "cancellation_resume", "thermal_end_c", "accelerator fallback"],
            "warning": "Host ONNX wall-clock is CPU-only and is not Android performance evidence.",
        },
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"out": str(args.out), "runs": [{k: r.get(k) for k in ("policy", "status", "elapsed_ms", "complete_pages")} for r in results], "resume_ms": resumed["elapsed_ms"]}, indent=2))


if __name__ == "__main__":
    main()
