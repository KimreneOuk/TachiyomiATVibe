#!/usr/bin/env python3
"""Fetch and verify the model assets listed in scripts/models.manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
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
    """A manifest, download, conversion, or integrity error."""


def validate_relative_path(value: Any, description: str) -> PurePosixPath:
    if not isinstance(value, str) or not value:
        raise FetchError(f"{description} must be a non-empty relative path")
    path = PurePosixPath(value)
    if (
        "\\" in value
        or path.is_absolute()
        or any(part in ("", ".", "..") for part in path.parts)
    ):
        raise FetchError(f"unsafe {description}: {value!r}")
    return path


def validate_url(value: Any, description: str) -> str:
    parsed = urllib.parse.urlparse(value) if isinstance(value, str) else None
    if not parsed or parsed.scheme != "https" or not parsed.netloc or parsed.username:
        raise FetchError(f"invalid HTTPS {description}")
    return value


def validate_digest(value: Any, description: str) -> str:
    if not isinstance(value, str) or not SHA256_PATTERN.fullmatch(value):
        raise FetchError(f"invalid SHA-256 {description}")
    return value


def load_manifest(path: Path) -> list[dict[str, Any]]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise FetchError(f"cannot read manifest {path}: {error}") from error

    if not isinstance(manifest, dict) or manifest.get("version") != 2:
        raise FetchError(f"unsupported manifest format in {path}")

    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise FetchError(f"manifest {path} must contain a non-empty files list")

    seen_paths: set[str] = set()
    for index, entry in enumerate(files, start=1):
        if not isinstance(entry, dict):
            raise FetchError(f"manifest entry {index} must be an object")

        relative_path = entry.get("path")
        validate_relative_path(relative_path, f"path in manifest entry {index}")
        if relative_path in seen_paths:
            raise FetchError(f"duplicate model path in manifest: {relative_path}")
        seen_paths.add(relative_path)

        size = entry.get("size_bytes")
        if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
            raise FetchError(f"invalid size_bytes for {relative_path}")
        validate_digest(entry.get("sha256"), f"for {relative_path}")
        validate_url(entry.get("source_url"), f"source_url for {relative_path}")

        converter = entry.get("convert")
        if converter is not None:
            if not isinstance(converter, dict):
                raise FetchError(f"convert must be an object for {relative_path}")
            converter_path = validate_relative_path(
                converter.get("script"), f"converter script for {relative_path}"
            )
            if converter_path.suffix != ".py":
                raise FetchError(f"converter script must be Python for {relative_path}")
            source_name = converter.get("source_filename")
            validate_relative_path(source_name, f"source_filename for {relative_path}")
            source_size = converter.get("source_size_bytes")
            if isinstance(source_size, bool) or not isinstance(source_size, int) or source_size <= 0:
                raise FetchError(f"invalid source_size_bytes for {relative_path}")
            validate_digest(converter.get("source_sha256"), f"source for {relative_path}")

    return files


def destination_for(root: Path, relative_path: str) -> Path:
    model_path = validate_relative_path(relative_path, "model path")
    models_root = (root / Path(*ASSET_DIRECTORY.parts)).resolve()
    destination = models_root.joinpath(*model_path.parts).resolve()
    try:
        destination.relative_to(models_root)
    except ValueError as error:
        raise FetchError(f"model path escapes the assets directory: {relative_path}") from error
    return destination


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


def is_verified(path: Path, size: int, digest: str) -> bool:
    if not path.is_file():
        return False
    actual_size, actual_digest = digest_file(path)
    return actual_size == size and actual_digest == digest


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


def download_verified(
    destination: Path,
    url: str,
    expected_size: int,
    expected_digest: str,
    label: str,
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "TachiyomiATVibe-model-fetcher/2.0"},
    )
    temporary_path: Path | None = None
    digest = hashlib.sha256()
    downloaded_size = 0

    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{destination.name}.", suffix=".part", dir=destination.parent, delete=False
        ) as temporary_file:
            temporary_path = Path(temporary_file.name)
            with urllib.request.urlopen(request, timeout=120) as response:
                while True:
                    chunk = response.read(CHUNK_SIZE)
                    if not chunk:
                        break
                    if downloaded_size + len(chunk) > expected_size:
                        raise FetchError(
                            f"download too large for {label}: expected at most {expected_size} bytes"
                        )
                    temporary_file.write(chunk)
                    downloaded_size += len(chunk)
                    digest.update(chunk)

        downloaded_digest = digest.hexdigest()
        if downloaded_size != expected_size:
            raise FetchError(
                f"size mismatch for {label}: expected {expected_size} bytes, received {downloaded_size}"
            )
        if downloaded_digest != expected_digest:
            raise FetchError(
                f"SHA-256 mismatch for {label}: expected {expected_digest}, received {downloaded_digest}"
            )

        os.replace(temporary_path, destination)
        temporary_path = None
    except urllib.error.HTTPError as error:
        raise FetchError(f"HTTP {error.code} fetching {label} from {url}") from error
    except urllib.error.URLError as error:
        raise FetchError(f"network error fetching {label} from {url}: {error.reason}") from error
    except TimeoutError as error:
        raise FetchError(f"timed out fetching {label} from {url}") from error
    except OSError as error:
        raise FetchError(f"I/O error fetching {label}: {error}") from error
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass


def run_converter(
    destination: Path,
    entry: dict[str, Any],
    converter: dict[str, Any],
    root: Path,
) -> None:
    script = (REPOSITORY_ROOT / Path(*PurePosixPath(converter["script"]).parts)).resolve()
    try:
        script.relative_to(REPOSITORY_ROOT)
    except ValueError as error:
        raise FetchError(f"converter script escapes the repository: {converter['script']}") from error
    if not script.is_file():
        raise FetchError(f"converter script not found: {converter['script']}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(prefix="model-convert-", dir=destination.parent) as temporary_dir:
            temporary_root = Path(temporary_dir)
            source = temporary_root / converter["source_filename"]
            generated = temporary_root / "generated.onnx"
            download_verified(
                source,
                entry["source_url"],
                converter["source_size_bytes"],
                converter["source_sha256"],
                f"source for {entry['path']}",
            )
            command = [
                sys.executable,
                str(script),
                "--source",
                str(source),
                "--output",
                str(generated),
            ]
            print(f"[converting] {entry['path']} via {converter['script']}")
            subprocess.run(command, cwd=root, check=True)
            if not is_verified(generated, entry["size_bytes"], entry["sha256"]):
                actual_size, actual_digest = digest_file(generated)
                raise FetchError(
                    f"converted output mismatch for {entry['path']}: expected "
                    f"{entry['size_bytes']} bytes sha256 {entry['sha256']}, received "
                    f"{actual_size} bytes sha256 {actual_digest}"
                )
            os.replace(generated, destination)
            print(f"[fetched] {entry['path']} ({entry['size_bytes']} bytes, sha256 verified)")
    except subprocess.CalledProcessError as error:
        raise FetchError(
            f"converter failed for {entry['path']} with exit code {error.returncode}; "
            "install scripts/converters/requirements.txt and retry"
        ) from error
    except OSError as error:
        raise FetchError(f"I/O error converting {entry['path']}: {error}") from error


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Fetch or convert model assets, then verify size and full SHA-256."
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
            if is_verified(destination, entry["size_bytes"], entry["sha256"]):
                print(f"[verified, skipped] {entry['path']}")
                continue

            converter = entry.get("convert")
            if converter:
                run_converter(destination, entry, converter, root)
            else:
                download_verified(
                    destination,
                    entry["source_url"],
                    entry["size_bytes"],
                    entry["sha256"],
                    entry["path"],
                )
                print(f"[fetched] {entry['path']} ({entry['size_bytes']} bytes, sha256 verified)")

        print(f"Verified {len(files)} model files.")
        return 0
    except FetchError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"ERROR: filesystem operation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
