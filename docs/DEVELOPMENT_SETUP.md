# Development Setup & Contributor Onboarding Guide

Welcome to **TachiyomiATVibe**! This guide is the authoritative, step-by-step walkthrough to take you from a fresh machine to a running build and your first successful translation.

---

## 1. System & Tooling Prerequisites

Before building, verify that your machine has the required tools installed.

| Component | Pinned / Required Version | Purpose / Source |
| :--- | :--- | :--- |
| **Operating System** | Windows 10/11, macOS 12+, or modern Linux | Supported on all three platforms. |
| **Git** | 2.30+ | Version control. (No submodules or Git LFS needed). |
| **Python** | **3.10 – 3.12** | Model conversion toolchain. Pinned in `scripts/converters/requirements.txt`. |
| **Java (JDK)** | **JDK 17** (or Android Studio JBR 17/21) | Pinned in `AndroidConfig.kt` (`JavaVersion.VERSION_17`, `JvmTarget.JVM_17`). |
| **Android Studio** | Ladybug (2024.2+) or newer | Recommended IDE with bundled JBR and SDK Manager. |
| **Android SDK Platform** | **API 35** (`android-35`) | Pinned in `AndroidConfig.kt` (`COMPILE_SDK = 35`, `TARGET_SDK = 34`, `MIN_SDK = 26`). |
| **Android Build Tools** | **35.0.0** | AGP 8.8.1 default build tools. |
| **Android NDK** | `27.1.12297006` *(Optional for standard builds)* | Pinned in `AndroidConfig.kt`. Optional for standard APK builds using prebuilt dependencies; required only if compiling native C++ components. |
| **Gradle** | **8.12** (via included wrapper) | Managed by `./gradlew` / `gradlew.bat`. Pinned in `gradle-wrapper.properties`. |
| **Target Device / Emulator** | Android 8.0+ (API 26+) | 64-bit architecture (`arm64-v8a` for phones, `x86_64` for emulators). Min 4–6 GB RAM allocated. |
| **Disk Space** | ~5 GB free | For SDK components, Gradle cache, Python virtualenv, and models (~159 MB). |

> [!TIP]
> You do **not** need a separate manual Gradle installation. The repository provides `gradlew` (Linux/macOS) and `gradlew.bat` (Windows).

---

## 2. Step 1: Clone the Repository

Clone the repository to your local machine:

```sh
git clone https://github.com/KimreneOuk/TachiyomiATVibe.git
cd TachiyomiATVibe
```

- **Git Submodules:** Not used.
- **Git LFS:** Not used.
- **Branch:** Default branch is `main`.

---

## 3. Step 2: Configure Environment Variables

The build toolchain requires knowledge of your **JDK** and **Android SDK** paths.

### A. Set `JAVA_HOME`

Gradle requires `JAVA_HOME` to point to a valid JDK 17 (or Android Studio's bundled JetBrains Runtime).

- **Windows (PowerShell):**
  ```powershell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```
- **Windows (Command Prompt):**
  ```cmd
  set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
  ```
- **macOS:**
  ```sh
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  ```
- **Linux:**
  ```sh
  export JAVA_HOME="/opt/android-studio/jbr" # or your system JDK 17 path (e.g., /usr/lib/jvm/java-17-openjdk)
  ```

*(To make this permanent, set `JAVA_HOME` in your system's Environment Variables or your shell profile: `~/.bashrc` / `~/.zshrc`).*

### B. Configure Android SDK (`local.properties` or `ANDROID_HOME`)

If building from the command line on a fresh checkout, Gradle needs to locate the Android SDK.

Either set the `ANDROID_HOME` environment variable, or create a file named `local.properties` in the repository root:

- **Windows (`local.properties`):**
  ```properties
  sdk.dir=C\:\\Users\\<YourUsername>\\AppData\\Local\\Android\\Sdk
  ```
- **macOS (`local.properties`):**
  ```properties
  sdk.dir=/Users/<YourUsername>/Library/Android/sdk
  ```
- **Linux (`local.properties`):**
  ```properties
  sdk.dir=/home/<YourUsername>/Android/Sdk
  ```

*(Note: If you open the project in Android Studio first, Android Studio automatically creates `local.properties` for you).*

---

## 4. Step 3: Set Up Python Virtual Environment

The model setup toolchain uses PyTorch and Ultralytics to export and quantize ONNX models. Installing these dependencies directly into system Python can cause library conflicts or fail on modern Linux distributions (PEP 668). **Always use a project-local virtual environment.**

### Linux & macOS

```sh
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r scripts/converters/requirements.txt
```

### Windows (PowerShell)

```powershell
py -3 -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r scripts/converters/requirements.txt
```

*(If script execution is disabled in PowerShell, run `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned` or use `.venv\Scripts\python.exe` directly).*

---

## 5. Step 4: Fetch and Convert Models

The application's on-device translation pipeline relies on 13 ONNX and configuration assets:
- **10 direct downloads:** Manga-OCR encoder/decoders, comic bubble detector, PaddleOCR PP-OCRv6 recognition/detection models, and AOT inpainting model.
- **3 locally converted models:** Manga panel detector (YOLO26 int8), bubble segmenter (Manga109 int8), and fixed-dimension AOT-512 inpainter.

Run the model setup script:

```sh
python scripts/fetch_models.py
```

### What this command does:
1. Downloads upstream model weights and configurations (~159 MB total).
2. Executes converter scripts (`scripts/converters/*.py`) using the pinned dependencies.
3. Verifies full SHA-256 cryptographic digests and exact file sizes against [`scripts/models.manifest`](../scripts/models.manifest).
4. Places verified models into `app/src/main/assets/models/`.

### Idempotency & Clean Recovery:
- The script is idempotent: rerunning it skips already-verified assets.
- If a download or conversion is interrupted, temporary `.part` files are cleaned up automatically.
- To remove all fetched models and start clean: `python scripts/fetch_models.py --clean`.

---

## 6. Step 5: Run the Setup Doctor

To verify that your environment is 100% ready before compiling, run the built-in diagnostic tool:

```sh
python scripts/setup_check.py
```

This non-destructive tool checks:
- Python version and virtualenv activation
- Model converter library availability
- Java / JDK version and `JAVA_HOME`
- Android SDK directory and Platform 35 presence
- Model asset completeness (13/13 verified)
- Gradle wrapper integrity
- Connected devices or emulators via `adb`

If any item is missing, the doctor displays exact commands to resolve it.

---

## 7. Step 6: Build the Application

You can build either from the command line or from Android Studio.

### Option A: Command Line Build (CLI)

The standard debug variant is `standardDebug`.

- **Linux / macOS:**
  ```sh
  ./gradlew :app:assembleStandardDebug
  ```
- **Windows:**
  ```powershell
  .\gradlew.bat :app:assembleStandardDebug
  ```

> [!NOTE]
> If model assets are missing, a Gradle verification task (`checkModelAssets`) will fail immediately and explain how to run `python scripts/fetch_models.py`. (To bypass this check for testing, pass `-PskipModelCheck=true`).

### Option B: Android Studio Workflow

1. Launch Android Studio.
2. Choose **File → Open...** and select the repository root folder (`TachiyomiATVibe`).
3. Configure the Gradle JDK:
   - Navigate to **Settings / Preferences → Build, Execution, Deployment → Build Tools → Gradle**.
   - Under **Gradle JDK**, select **Embedded JDK / JBR** or your installed **JDK 17**.
4. Allow Gradle Sync to finish.
5. In the bottom-left corner, open the **Build Variants** panel.
6. Ensure **Active Build Variant** for `:app` is set to `standardDebug` (default).
7. Select an emulator or connected device in the top toolbar, then click **Run** (`Shift+F10`).

---

## 8. Step 7: Locate and Install the APK

### APK Variant Matrix

Because per-ABI splitting is enabled (`splits.abi.isEnable = true`), building `assembleStandardDebug` produces multiple APKs under:

```text
app/build/outputs/apk/standard/debug/
```

| APK File | Architecture | Where to Install |
| :--- | :--- | :--- |
| `app-standard-arm64-v8a-debug.apk` | 64-bit ARM | Most modern physical Android phones. |
| `app-standard-x86_64-debug.apk` | 64-bit x86 | Modern Android Studio emulators on PC/Mac. |
| `app-standard-universal-debug.apk` | All ABIs | Fat APK compatible with any supported device. |
| `app-standard-armeabi-v7a-debug.apk`| 32-bit ARM | Older 32-bit physical devices. |
| `app-standard-x86-debug.apk` | 32-bit x86 | Older 32-bit emulators. |

> [!IMPORTANT]
> **Local Debug vs. CI Release Artifacts:**
> - **Local debug builds (`*debug.apk`):** Automatically signed with your local Android debug keystore. They can be installed immediately.
> - **CI GitHub Actions artifacts (`*release-unsigned.apk`):** Built from the `standardRelease` variant without signing keys. They are **UNSIGNED** verification artifacts and will fail device installation unless manually signed first.

### Installing via `adb`

1. Ensure your device is recognized:
   ```sh
   adb devices
   ```
2. Install the APK matching your device ABI (or the universal APK):
   ```sh
   # Modern phone:
   adb install -r app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk

   # Emulator:
   adb install -r app/build/outputs/apk/standard/debug/app-standard-x86_64-debug.apk
   ```
3. Launch the app directly from your terminal:
   ```sh
   adb shell monkey -p app.kanade.tachiyomi.vibe.debug -c android.intent.category.LAUNCHER 1
   ```

*(Note: The applicationId for debug builds is `app.kanade.tachiyomi.vibe.debug`).*

---

## 9. Step 8: First Run & First Translation Walkthrough

Once installed, follow these steps to verify that the translation subsystem functions correctly:

1. **Open Translation Settings:**
   - Tap **More** in the bottom navigation bar.
   - Go to **Settings → Translations**.
2. **Select Languages:**
   - Set **Translate From** (e.g., Japanese, Korean, or Chinese).
   - Set **Translate To** (e.g., English).
3. **Choose an Initial Translation Engine:**
   - **Google Translate:** Recommended for immediate first-time verification. It operates over HTTP and does **not** require any API credentials.
   - **ML Kit (On-Device):** Works offline without API keys. (ML Kit will download its language pack on the very first translation attempt).
   - **Cloud AI (Gemini, DeepSeek, OpenRouter, DeepL):** Requires your personal API key entered in the corresponding provider field.
4. **Perform a Test Translation:**
   - Add a manga or manhwa source, or add local chapters to your library.
   - Open a chapter in the reader.
   - Tap the center of the screen to reveal the reader overlay.
   - Tap the **Translate** button on the bottom bar for the current page, or enable auto-translation in the translation reader dialog.
   - The app will run detection, OCR, translation, inpainting, and overlay rendering on-device.

---

## 10. Step 9: Running Unit Tests

Unit tests are pure-JVM JUnit 5 tests. **They do not require model assets or an Android device.**

- Run all unit tests:
  ```sh
  ./gradlew test
  # On Windows:
  .\gradlew.bat test
  ```
- Run translation-specific tests:
  ```sh
  ./gradlew :app:testStandardDebugUnitTest --tests "eu.kanade.translation.*"
  ```
- Quarantined tests (`quarantined-flaky`) are excluded from default runs. To opt into running them:
  ```sh
  ./gradlew :app:testDevReleaseUnitTest -PincludeQuarantinedTests
  ```

---

## 11. Emulator vs. Physical Device Expectations

- **Emulators (x86_64):**
  - Fully supported for development, UI changes, reader testing, and translation validation.
  - ONNX models execute via ONNX Runtime's **CPU execution provider**. Inpainting and OCR on CPU are slower than on hardware accelerators but completely functional.
  - Recommended emulator config: Pixel 7 or 8 image, Android 14 (API 34) or 15 (API 35), x86_64, with at least 4096 MB RAM configured in AVD advanced settings.
- **Physical Devices (Qualcomm / ARM64):**
  - Qualcomm Snapdragon devices (e.g., SM8550, SM8650) can leverage specialized NPU/HTP hardware acceleration paths (QNN).
  - General ARM64 devices utilize optimized CPU and NNAPI backends.

---

## 12. Troubleshooting & Common Failure Modes

### 1. `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`
- **Cause:** Neither `JAVA_HOME` nor `java` is configured in the current shell.
- **Fix:** Set `JAVA_HOME` to Android Studio's JBR (`C:\Program Files\Android\Android Studio\jbr` on Windows, `/Applications/Android Studio.app/Contents/jbr/Contents/Home` on macOS).

### 2. `SDK location not found`
- **Cause:** Android SDK directory is not detected.
- **Fix:** Set `ANDROID_HOME` or create a `local.properties` file in the repo root with `sdk.dir=<path_to_sdk>`.

### 3. `BUILD FAILED: Missing 13 required translation model asset(s)`
- **Cause:** Attempting to build an APK before fetching model weights.
- **Fix:** Run `python scripts/fetch_models.py` inside your activated virtual environment.

### 4. `AttributeError: Can't get attribute 'E2ELoss' on module 'ultralytics.utils.loss'`
- **Cause:** An older version of `ultralytics` (< 8.4) installed in system Python is being used.
- **Fix:** Activate your `.venv` and install `scripts/converters/requirements.txt` (`ultralytics==8.4.163`).

### 5. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` on `adb install`
- **Cause:** An APK with a different signing key or package version is already installed on the device.
- **Fix:** Uninstall the existing debug app:
  ```sh
  adb uninstall app.kanade.tachiyomi.vibe.debug
  ```
  Then retry `adb install`.

### 6. Dependency resolution timeouts or JitPack issues
- **Cause:** JitPack or network transient delays.
- **Fix:** The project includes a local Maven mirror in `repo/` for known flaky artifacts. Rerun Gradle, or run the warmup task:
  ```sh
  ./gradlew :app:mergeStandardReleaseNativeLibs
  ```

---

## 13. Documentation Hierarchy

For deeper technical topics, refer to:
- [`README.md`](../README.md): High-level project summary and quick overview.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md): Code conventions, branch workflows, and architectural expectations.
- [`docs/MODEL_SOURCES.md`](MODEL_SOURCES.md): Upstream provenance, individual converter scripts, AGPL/Apache/MIT model licensing details.
- [`docs/translation-architecture.md`](translation-architecture.md): Internal subsystem architecture, lifecycle contracts, and concurrency policies.
