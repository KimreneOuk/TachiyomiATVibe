#!/usr/bin/env python3
"""
TachiyomiATVibe Environment & Onboarding Diagnostic Checker.

Performs non-destructive environment checks to verify whether this machine
is ready to fetch models, compile, and run the application.

Usage:
    python scripts/setup_check.py
"""

from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "scripts" / "models.manifest"
ASSETS_MODELS_DIR = ROOT / "app" / "src" / "main" / "assets" / "models"


class Colors:
    GREEN = "\033[92m"
    YELLOW = "\033[93m"
    RED = "\033[91m"
    BLUE = "\033[94m"
    BOLD = "\033[1m"
    RESET = "\033[0m"


# On Windows cmd without VT100 enabled, fallback to plain text if colorama/ANSI not supported
if sys.platform == "win32" and not os.environ.get("WT_SESSION"):
    # Enable ANSI escape sequences on Windows 10/11 if possible
    try:
        import ctypes
        kernel32 = ctypes.windll.kernel32
        kernel32.SetConsoleMode(kernel32.GetStdHandle(-11), 7)
    except Exception:
        Colors.GREEN = ""
        Colors.YELLOW = ""
        Colors.RED = ""
        Colors.BLUE = ""
        Colors.BOLD = ""
        Colors.RESET = ""


def ok(text: str) -> None:
    print(f"  [{Colors.GREEN}OK{Colors.RESET}] {text}")


def warn(text: str) -> None:
    print(f"  [{Colors.YELLOW}WARN{Colors.RESET}] {text}")


def fail(text: str) -> None:
    print(f"  [{Colors.RED}FAIL{Colors.RESET}] {text}")


def info(text: str) -> None:
    print(f"  [{Colors.BLUE}INFO{Colors.RESET}] {text}")


def check_python() -> bool:
    print(f"\n{Colors.BOLD}1. Python Environment{Colors.RESET}")
    v = sys.version_info
    py_str = f"Python {v.major}.{v.minor}.{v.micro}"
    in_venv = sys.prefix != sys.base_prefix

    if v.major == 3 and 10 <= v.minor <= 12:
        ok(f"{py_str} (supported version: 3.10 - 3.12)")
    elif v.major == 3 and v.minor >= 13:
        warn(f"{py_str} (Python 3.13+ may not have prebuilt PyTorch/Ultralytics wheels)")
    else:
        fail(f"{py_str} (Python 3.10-3.12 is required for pinned PyTorch/NumPy dependencies)")
        return False

    if in_venv:
        ok(f"Virtual environment active: {sys.prefix}")
    else:
        warn("Running directly in system Python. A virtual environment (.venv) is recommended.")

    # Check converter dependencies
    required_modules = [
        ("torch", "torch"),
        ("torchvision", "torchvision"),
        ("ultralytics", "ultralytics"),
        ("onnx", "onnx"),
        ("onnxruntime", "onnxruntime"),
        ("onnxslim", "onnxslim"),
        ("numpy", "numpy"),
    ]
    missing_modules = []
    installed_versions = {}

    for mod, pkg in required_modules:
        try:
            m = __import__(mod)
            ver = getattr(m, "__version__", "installed")
            installed_versions[mod] = ver
        except ImportError:
            missing_modules.append(pkg)

    if not missing_modules:
        ok("Model converter dependencies installed:")
        for mod, ver in installed_versions.items():
            print(f"       - {mod} {ver}")
    else:
        warn(f"Missing converter dependencies: {', '.join(missing_modules)}")
        info("Install via: python -m pip install -r scripts/converters/requirements.txt")

    return True


def check_java() -> tuple[bool, str | None]:
    print(f"\n{Colors.BOLD}2. Java / JDK Environment{Colors.RESET}")
    java_home = os.environ.get("JAVA_HOME")
    java_exe = None

    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if sys.platform == "win32" else "java")
        if candidate.is_file():
            java_exe = str(candidate)
            ok(f"JAVA_HOME is set: {java_home}")
        else:
            warn(f"JAVA_HOME is set to '{java_home}', but bin/java executable was not found.")

    if not java_exe:
        found_in_path = shutil.which("java")
        if found_in_path:
            java_exe = found_in_path
            info(f"'java' found in PATH: {java_exe}")
        else:
            # Check default Android Studio JBR paths
            default_jbr_paths = []
            if sys.platform == "win32":
                default_jbr_paths = [
                    Path("C:/Program Files/Android/Android Studio/jbr"),
                    Path("C:/Program Files/Android/Android Studio/jre"),
                ]
            elif sys.platform == "darwin":
                default_jbr_paths = [
                    Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home"),
                    Path("/Applications/Android Studio.app/Contents/jre/Contents/Home"),
                ]
            else:
                default_jbr_paths = [
                    Path("/opt/android-studio/jbr"),
                    Path.home() / ".local/share/JetBrains/Toolbox/apps/android-studio/jbr",
                ]

            for path in default_jbr_paths:
                bin_java = path / "bin" / ("java.exe" if sys.platform == "win32" else "java")
                if bin_java.is_file():
                    java_exe = str(bin_java)
                    info(f"Detected Android Studio JBR at: {path}")
                    info(f"Recommended: set JAVA_HOME=\"{path}\"")
                    break

    if not java_exe:
        fail("Java executable not found. Gradle requires JDK 17 (or Android Studio's bundled JBR).")
        info("Set JAVA_HOME to your JDK 17 or Android Studio JBR path.")
        return False, None

    # Check Java version
    try:
        proc = subprocess.run([java_exe, "-version"], capture_output=True, text=True, check=False)
        output = proc.stderr or proc.stdout
        first_line = output.splitlines()[0] if output else "Unknown version"
        if "version" in first_line:
            ok(f"Runtime: {first_line.strip()}")
            return True, java_home
        else:
            ok(f"Java executable verified: {java_exe}")
            return True, java_home
    except Exception as e:
        warn(f"Could not query java version: {e}")
        return True, java_home


def check_android_sdk() -> tuple[bool, Path | None]:
    print(f"\n{Colors.BOLD}3. Android SDK Environment{Colors.RESET}")
    sdk_dir: Path | None = None

    # 1. Check local.properties
    local_props = ROOT / "local.properties"
    if local_props.is_file():
        for line in local_props.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("sdk.dir="):
                val = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "/").replace("\\", "/")
                candidate = Path(val)
                if candidate.is_dir():
                    sdk_dir = candidate
                    ok(f"Found sdk.dir in local.properties: {sdk_dir}")
                    break

    # 2. Check ANDROID_HOME or ANDROID_SDK_ROOT
    if not sdk_dir:
        env_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if env_home and Path(env_home).is_dir():
            sdk_dir = Path(env_home)
            ok(f"Found ANDROID_HOME environment variable: {sdk_dir}")

    # 3. Check OS standard locations
    if not sdk_dir:
        standard_paths = []
        if sys.platform == "win32":
            local_appdata = os.environ.get("LOCALAPPDATA")
            if local_appdata:
                standard_paths.append(Path(local_appdata) / "Android" / "Sdk")
        elif sys.platform == "darwin":
            standard_paths.append(Path.home() / "Library" / "Android" / "sdk")
        else:
            standard_paths.append(Path.home() / "Android" / "Sdk")

        for candidate in standard_paths:
            if candidate.is_dir():
                sdk_dir = candidate
                ok(f"Detected default Android SDK location: {sdk_dir}")
                info("Recommended: set ANDROID_HOME environment variable or define sdk.dir in local.properties")
                break

    if not sdk_dir:
        fail("Android SDK not found. Install via Android Studio or command-line tools.")
        info("Define sdk.dir in local.properties or set the ANDROID_HOME environment variable.")
        return False, None

    # Check compileSdk 35 platform
    platform_35 = sdk_dir / "platforms" / "android-35"
    if platform_35.is_dir():
        ok("Android SDK Platform 35 (compileSdk 35) is installed.")
    else:
        fail("Android SDK Platform 35 is missing! (Required by compileSdk = 35)")
        info("Install via Android Studio SDK Manager -> SDK Platforms -> Android 15.0 ('VanillaIceCream') / API 35")

    # Check NDK 27.1.12297006 (optional for standard prebuilt APK build, pinned in AndroidConfig)
    ndk_dir = sdk_dir / "ndk" / "27.1.12297006"
    if ndk_dir.is_dir():
        ok("NDK 27.1.12297006 is installed.")
    else:
        info("NDK 27.1.12297006 not detected (not required for standard debug APK builds using prebuilt dependencies).")

    # Check adb
    adb_exe = sdk_dir / "platform-tools" / ("adb.exe" if sys.platform == "win32" else "adb")
    if adb_exe.is_file():
        ok(f"Android platform-tools (adb) available at: {adb_exe}")
    else:
        warn("adb not found under platform-tools. Install Android SDK Platform-Tools.")

    return True, sdk_dir


def check_models() -> bool:
    print(f"\n{Colors.BOLD}4. Translation Model Assets{Colors.RESET}")
    if not MANIFEST_PATH.is_file():
        fail(f"Manifest file missing: {MANIFEST_PATH}")
        return False

    try:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        files = manifest.get("files", [])
    except Exception as e:
        fail(f"Cannot parse manifest: {e}")
        return False

    total_files = len(files)
    present_files = 0
    missing = []

    for entry in files:
        rel_path = entry.get("path")
        expected_size = entry.get("size_bytes")
        target = ASSETS_MODELS_DIR / Path(rel_path)
        if target.is_file() and target.stat().st_size == expected_size:
            present_files += 1
        else:
            missing.append(rel_path)

    if present_files == total_files:
        ok(f"All {total_files}/{total_files} required model assets are present and size-verified.")
        return True
    elif present_files > 0:
        warn(f"Partial model assets: {present_files}/{total_files} present. Missing {len(missing)} files.")
        info("Run: python scripts/fetch_models.py to download and convert the rest.")
        return False
    else:
        fail(f"Model assets not found (0/{total_files} present).")
        info("APK builds will fail checkModelAssets without these files.")
        info("Run: python scripts/fetch_models.py to obtain and verify all models.")
        return False


def check_gradle() -> bool:
    print(f"\n{Colors.BOLD}5. Gradle Wrapper{Colors.RESET}")
    wrapper_bat = ROOT / "gradlew.bat"
    wrapper_sh = ROOT / "gradlew"
    props = ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"

    if wrapper_bat.is_file() and wrapper_sh.is_file():
        ok("gradlew and gradlew.bat are present.")
    else:
        fail("Gradle wrapper scripts missing in repository root.")
        return False

    if props.is_file():
        text = props.read_text(encoding="utf-8")
        for line in text.splitlines():
            if line.startswith("distributionUrl="):
                ok(f"Configured wrapper: {line.split('/')[-1]}")
                break
    return True


def check_devices(sdk_dir: Path | None) -> None:
    print(f"\n{Colors.BOLD}6. Connected Devices (adb){Colors.RESET}")
    adb = None
    if sdk_dir:
        candidate = sdk_dir / "platform-tools" / ("adb.exe" if sys.platform == "win32" else "adb")
        if candidate.is_file():
            adb = str(candidate)
    if not adb:
        adb = shutil.which("adb")

    if not adb:
        info("adb not found; skipping device discovery.")
        return

    try:
        proc = subprocess.run([adb, "devices"], capture_output=True, text=True, check=False)
        lines = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
        devices = [line for line in lines[1:] if "\tdevice" in line or " device" in line]
        if devices:
            ok(f"Detected {len(devices)} active device/emulator:")
            for d in devices:
                print(f"       - {d.split()[0]}")
        else:
            info("No active device or emulator attached. (Connect via USB/Wi-Fi or start an emulator)")
    except Exception as e:
        warn(f"Failed to query adb devices: {e}")


def main() -> int:
    print("=" * 70)
    print(f" {Colors.BOLD}TachiyomiATVibe - Newcomer Environment Diagnostic Check{Colors.RESET}")
    print("=" * 70)

    py_ok = check_python()
    java_ok, java_home = check_java()
    sdk_ok, sdk_dir = check_android_sdk()
    models_ok = check_models()
    gradle_ok = check_gradle()
    check_devices(sdk_dir)

    print("\n" + "=" * 70)
    print(f" {Colors.BOLD}Summary & Next Steps{Colors.RESET}")
    print("=" * 70)

    if not java_ok:
        print(f"\n{Colors.RED}[!] ACTION: Configure JAVA_HOME{Colors.RESET}")
        if sys.platform == "win32":
            print("    cmd:        set \"JAVA_HOME=C:\\Program Files\\Android\\Android Studio\\jbr\"")
            print("    PowerShell: $env:JAVA_HOME = \"C:\\Program Files\\Android\\Android Studio\\jbr\"")
        else:
            print("    export JAVA_HOME=\"/path/to/jdk-17-or-android-studio/jbr\"")

    if not sdk_ok:
        print(f"\n{Colors.RED}[!] ACTION: Configure Android SDK{Colors.RESET}")
        print("    Create 'local.properties' in repository root with:")
        if sys.platform == "win32":
            print("    sdk.dir=C:\\\\Users\\\\<YourUser>\\\\AppData\\\\Local\\\\Android\\\\Sdk")
        else:
            print("    sdk.dir=/Users/<YourUser>/Library/Android/sdk")

    if not models_ok:
        print(f"\n{Colors.YELLOW}[!] ACTION: Fetch & Convert Models{Colors.RESET}")
        print("    python scripts/fetch_models.py")

    ready = java_ok and sdk_ok and models_ok and gradle_ok
    if ready:
        print(f"\n{Colors.GREEN}{Colors.BOLD}[SUCCESS] Your environment is fully configured!{Colors.RESET}")
        print("To build the debug APK:")
        if sys.platform == "win32":
            print("    .\\gradlew.bat :app:assembleStandardDebug")
        else:
            print("    ./gradlew :app:assembleStandardDebug")
        print("\nTo run JVM unit tests (no models required):")
        if sys.platform == "win32":
            print("    .\\gradlew.bat test")
        else:
            print("    ./gradlew test")
        return 0
    else:
        print(f"\n{Colors.YELLOW}Complete the missing actions above before running assembleStandardDebug.{Colors.RESET}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
