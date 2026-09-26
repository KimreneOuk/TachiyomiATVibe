#!/usr/bin/env python3
"""Fetch and verify model files listed in scripts/models.manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Any


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
ASSET_DIRECTORY = PurePosixPath("app/src/main/assets/models")
MANIFEST_PATH = Path(__file__).with_name("models.manifest")
CHUNK_SIZE = 1024 * 1024
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class FetchError(Exception):
    """An invalid manifest, failed download, or failed integrity check."""


def load_manifest(path: Path) -> list[dict[str, Any]]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FetchError(f"cannot read manifest {path}: {error}") from error

    if not isinstance(manifest, dict) or manifest.get("version") != 1:
        raise FetchError(f"unsupported manifest format in {path}")

    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise FetchError(f"manifest {path} must contain a non-empty files list")

    seen_paths: set[str] = set()
    for index, entry in enumerate(files, start=1):
        if not isinstance(entry, dict):
            raise FetchError(f"manifest entry {index} must be an object")

        relative_path = entry.get("path")
        if not isinstance(relative_path, str) or not relative_path:
            raise FetchError(f"manifest entry {index} has no path")
        model_path = PurePosixPath(relative_path)
        if (
            "\\" in relative_path
            or model_path.is_absolute()
            or any(part in ("", ".", "..") for part in model_path.parts)
        ):
            raise FetchError(f"unsafe model path in manifest: {relative_path!r}")
        if relative_path in seen_paths:
            raise FetchError(f"duplicate model path in manifest: {relative_path}")
        seen_paths.add(relative_path)

        size = entry.get("size_bytes")
        if isinstance(size, bool) or not isinstance(size, int) or size < 0:
            raise FetchError(f"invalid size_bytes for {relative_path}")

        digest = entry.get("sha256")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise FetchError(f"invalid sha256 for {relative_path}")

        source_url = entry.get("source_url")
        parsed_url = urllib.parse.urlparse(source_url) if isinstance(source_url, str) else None
        if not parsed_url or parsed_url.scheme != "https" or not parsed_url.netloc:
            raise FetchError(f"invalid HTTPS source_url for {relative_path}")

    return files


def destination_for(root: Path, relative_path: str) -> Path:
    model_path = PurePosixPath(relative_path)
    models_root = (root / Path(*ASSET_DIRECTORY.parts)).resolve()
    destination = models_root.joinpath(*model_path.parts)
    resolved_destination = destination.resolve()
    try:
        resolved_destination.relative_to(models_root)
    except ValueError as error:
        raise FetchError(f"model path escapes the assets directory: {relative_path}") from error
    return resolved_destination


def digest_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as model_file:
        while True:
            chunk = model_file.read(CHUNK_SIZE)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
    return size, digest.hexdigest()


def is_verified(path: Path, entry: dict[str, Any]) -> bool:
    if not path.is_file():
        return False
    size, digest = digest_file(path)
    return size == entry["size_bytes"] and digest == entry["sha256"]


def remove_models(root: Path, files: list[dict[str, Any]]) -> None:
    models_root = (root / Path(*ASSET_DIRECTORY.parts)).resolve()
    for entry in files:
        destination = destination_for(root, entry["path"])
        if destination.is_dir():
            raise FetchError(f"refusing to remove a directory as a model file: {destination}")
        if destination.exists():
            destination.unlink()
            print(f"[removed] {entry['path']}")
        else:
            print(f"[absent] {entry['path']}")

        parent = destination.parent
        while parent != models_root:
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent


def download_model(destination: Path, entry: dict[str, Any]) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        entry["source_url"],
        headers={"User-Agent": "TachiyomiATVibe-model-fetcher/1.0"},
    )
    temporary_path: Path | None = None
    digest = hashlib.sha256()
    downloaded_size = 0

    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.name}.",
            suffix=".part",
            dir=destination.parent,
            delete=False,
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=60) as response:
                while True:
                    chunk = response.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    temporary_file.write(chunk)
                    downloaded_size += len(chunk)
                    digest.update(chunk)

        downloaded_digest = digest.hexdigest()
        if downloaded_size != entry["size_bytes"]:
            raise FetchError(
                f"size mismatch for {entry['path']}: expected {entry['size_bytes']} bytes, "
                f"received {downloaded_size}"
            )
        if downloaded_digest != entry["sha256"]:
            raise FetchError(
                f"SHA-256 mismatch for {entry['path']}: expected {entry['sha256']}, "
                f"received {downloaded_digest}"
            )

        os.replace(temporary_path, destination)
        temporary_path = None
        print(f"[fetched] {entry['path']} ({downloaded_size} bytes, sha256 verified)")
    except urllib.error.HTTPError as error:
        raise FetchError(
            f"HTTP {error.code} fetching {entry['path']} from {entry['source_url']}"
        ) from error
    except urllib.error.URLError as error:
        raise FetchError(
            f"network error fetching {entry['path']} from {entry['source_url']}: {error.reason}"
        ) from error
    except TimeoutError as error:
        raise FetchError(f"timed out fetching {entry['path']} from {entry['source_url']}") from error
    except OSError as error:
        raise FetchError(f"I/O error fetching {entry['path']}: {error}") from error
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch model assets from the manifest and verify each file's size and SHA-256."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPOSITORY_ROOT,
        help="project root containing app/src/main/assets/models (default: repository root)",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=MANIFEST_PATH,
        help="manifest file to read (default: scripts/models.manifest)",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="remove only files listed in the manifest from the selected project root",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        files = load_manifest(arguments.manifest)
        root = arguments.root.resolve()

        if arguments.clean:
            remove_models(root, files)
            return 0

        for entry in files:
            destination = destination_for(root, entry["path"])
            if destination.is_dir():
                raise FetchError(f"model destination is a directory: {destination}")
            if is_verified(destination, entry):
                print(f"[verified, skipped] {entry['path']}")
                continue
            download_model(destination, entry)

        print(f"Verified {len(files)} model files.")
        return 0
    except FetchError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
