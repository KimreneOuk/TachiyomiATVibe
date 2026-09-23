"""Compare production and standalone artifact asset-digest cache behavior."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[6]
sys.path.insert(0, str(ROOT / "tools" / "translation_studio"))

import detection_artifacts  # noqa: E402
import pipeline  # noqa: E402

helper_scan = subprocess.run(
    ["git", "grep", "-n", "-E",
     "(^|[^[:alnum:]_])asset_sha12\\(", "--", "tools/translation_studio"],
    cwd=ROOT, text=True, capture_output=True, check=False,
)
helper_matches = helper_scan.stdout.splitlines()
helper_call_sites = [line for line in helper_matches
                     if ":def asset_sha12(" not in line]


with tempfile.TemporaryDirectory(prefix="t937-asset-digest-") as temporary:
    asset = Path(temporary) / "model.onnx"
    asset.write_bytes(b"a" * 16384)
    original = asset.stat()
    artifact_before = detection_artifacts.asset_sha12(asset)
    pipeline_before = pipeline._asset_identity(asset)["sha256"]

    asset.write_bytes(b"b" * 16384)
    os.utime(asset, ns=(original.st_atime_ns, original.st_mtime_ns))
    replaced = asset.stat()
    artifact_after = detection_artifacts.asset_sha12(asset)
    pipeline_after = pipeline._asset_identity(asset)["sha256"]

    result = {
        "same_size": replaced.st_size == original.st_size,
        "same_mtime_ns": replaced.st_mtime_ns == original.st_mtime_ns,
        "artifact_helper_digest_before": artifact_before,
        "artifact_helper_digest_after": artifact_after,
        "artifact_helper_stale": artifact_before == artifact_after,
        "pipeline_digest_before": pipeline_before,
        "pipeline_digest_after": pipeline_after,
        "pipeline_digest_changed": pipeline_before != pipeline_after,
        "standalone_helper_definition_matches": helper_matches,
        "standalone_helper_call_sites": helper_call_sites,
    }
    if not result["same_size"] or not result["same_mtime_ns"]:
        raise AssertionError(result)
    if not result["artifact_helper_stale"] or not result["pipeline_digest_changed"]:
        raise AssertionError(result)
    if helper_scan.returncode not in (0, 1) or helper_call_sites:
        raise AssertionError(result)

    output = Path(__file__).with_name("asset_digest_helper_probe.json")
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
