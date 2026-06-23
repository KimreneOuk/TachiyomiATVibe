## Project Overview

This project is a **mobile manga / manhwa / manhua reader** built in **Kotlin**, featuring **forked translation capabilities**.  
It leverages **ONNX models** and supporting libraries as part of its processing pipeline.

---

## Performance & Resource Constraints

When designing any **pipeline or feature**, always prioritize:

- **Memory efficiency (OOM prevention)**
- **Runtime performance**
- **Low-end device compatibility**

### Target Device Constraints

- Devices with **~6 GB physical RAM**
- Only **20–30% heap availability**

All implementations **must operate reliably within these limits**.

---

## Documentation-First Approach

Before starting any investigation or development task:

- **Always read the `@docs` folder first**
  - Contains:
    - Architecture overview
    - Design decisions
    - System constraints
    - Key implementation details

This ensures all work is aligned with the **existing system design and intent**.

---

## Development & Debugging Guidelines

For any **debugging, implementation, or system design task**:

1. **Understand the Codebase Thoroughly**
   - Read relevant files end-to-end
   - Trace:
     - Data flow
     - Dependencies
     - Execution paths

2. **Work With Context**
   - Avoid assumptions
   - Base all decisions on:
     - Existing architecture
     - Established patterns
     - Project constraints

3. **Produce Grounded Solutions**
   - Ensure changes are consistent with system design
   - Avoid introducing unnecessary complexity

---

## Hallucination Prevention

To avoid generating code that is detached from reality:

- **Never guess** function signatures, package names, file paths, or API surfaces.
- Before using any class, method, or resource, **find its real definition** in the codebase (search, read the file, or follow imports).
- When you need to call an existing function, **read its implementation and its callers** to understand preconditions and side-effects.
- If you cannot find evidence of a capability or pattern, **ask for clarification** instead of inventing one.
- After writing changes, **re-verify** that all referenced symbols exist and all paths are correct (e.g., with a project-wide search).

---

## Code Change Requirements

When modifying or adding code:

- ✅ **Update documentation in `@docs` immediately**
- ✅ Ensure all changes are **well-documented and traceable**
- ✅ Maintain consistency with existing architecture

---

## Code Commenting Standards

- **Follow the existing comment style.**  
  Before adding comments, scan surrounding code and match the project’s conventions:
  - KDoc/JavaDoc for public APIs?
  - Inline `//` for non-obvious logic?
  - Comment blocks for complex algorithms?
  If the style isn’t obvious, prefer **minimal, concise comments that explain *why*, not *what***.

- **Don’t fight existing comments.**
  - Never delete or alter an existing comment unless you’re **certain** it’s wrong or misleading.
  - If a comment seems contradictory, **investigate the code’s actual behavior first**. Then update the comment to match reality, and note the change in the commit message.

- **Write only comments that add value.**
  - Avoid restating the code (`val x = 1 // set x to 1`).
  - Focus on intent, edge cases, non-obvious constraints, or links to documentation/issue tracker.
  - If a block of code is clear on its own, don’t add a comment just to “fill space”.

- **Keep comments in sync with code.**
  - Whenever you change a function’s behavior, update its comment/KDoc immediately.  
  - Stale comments are worse than no comments—review your diff for comment drift before committing.

- **Temporary / TODO comments:**  
  - If you absolutely need a temporary marker, use `// TODO:` with a clear description and a date or issue reference.  
  - Never leave unexplained `// fixme` or `// this is bad` comments; they just add noise.

---

## Version Control & Repo Hygiene

- **Never leave the repository in a broken state.** Before committing, ensure:
  - The project compiles without errors.
  - All affected tests pass (and you’ve run them).
  - No temporary debug files, logs, or unused resources are left in the tree.
- **Commit purposefully:**
  - Commit small, logically coherent changes.
  - Use descriptive commit messages in imperative mood (e.g., “Fix OOM in tile decoder”).
  - Reference any related issue or ticket.
- **Before committing, review your own diff** (`git diff --staged`). Check for:
  - Accidental whitespace / formatting noise.
  - Leftover debug prints or commented-out code.
  - Stray imports or dependency changes you didn’t intend.
- **Branch hygiene:**
  - Create a feature branch for any non-trivial work.
  - Never force-push to shared branches unless explicitly agreed.

---

## Testing Policy

- Always create or update **test cases** alongside code changes
- Verify:
  - Functional correctness
  - Performance impact
  - Memory usage

---

## Error Handling Policy

- ❌ **Never suppress errors or warnings**
- ✅ Always:
  - Investigate root causes
  - Resolve issues properly
  - Maintain visibility into failures

---

## Tooling & Debugging Utilities

Use all available tools when necessary, including:

- `logcat`
- **ADB (wireless debugging)**
- Any relevant profiling or debugging tools

Leverage these tools to:

- Diagnose issues efficiently
- Validate runtime behavior
- Monitor performance and memory usage

### ADB / Android SDK location

`adb` is **NOT on the system PATH** and neither `ANDROID_HOME` nor
`ANDROID_SDK_ROOT` is set in this environment. The Android SDK platform-tools
binary lives at:
C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe

text

Always invoke it by that absolute path (a `where adb` finds only a
third-party `C:\Program Files (x86)\WOMic\adb.exe`, which must NOT be used —
it is an unrelated virtual-audio app's bundled copy).

> **Note:** This path is specific to the current Windows development environment. If the environment changes, update this file accordingly.

Debug APK output dir (per ABI split):
app/build/outputs/apk/standard/debug/app-standard-<abi>-debug.apk

text

Typical device (OnePlus PKG110) install:
C:\Users\ADMIN\AppData\Local\Android\Sdk\platform-tools\adb.exe -s <host:port> install -r app\build\outputs\apk\standard\debug\app-standard-arm64-v8a-debug.apk

text

Installed debug package id: `app.kanade.tachiyomi.at.debug`

### Android Debugging Quick Reference

When investigating a crash, memory leak, or performance issue, use this checklist:

1. **Logcat crash stacktrace:**
<adb-path> logcat -d *:E

text
Capture the full Java/Kotlin stacktrace; identify the root exception and the calling code in your project.

2. **Memory info:**
<adb-path> shell dumpsys meminfo <package-id>

text
Check PSS, heap size, and any abnormal memory spikes.

3. **StrictMode for main-thread violations:**
If you suspect disk or network on the UI thread, enable `StrictMode` temporarily:
```kotlin
StrictMode.setThreadPolicy(
    StrictMode.ThreadPolicy.Builder()
        .detectAll()
        .penaltyLog()
        .build()
)
Then watch logcat for StrictMode violations.

ANR / traces:

text
<adb-path> shell ls /data/anr/
<adb-path> pull /data/anr/anr_<timestamp> .
Analyse the main thread stack in the trace.

Profile GPU / rendering:
In Developer Options on the device, enable Profile HWUI rendering and use adb shell dumpsys gfxinfo <package-id>.

Always start diagnosis with the facts from the device, not assumptions.

Communication Style
Keep commentary minimal and purposeful.

Avoid talking at every turn during routine work such as reading files, writing code, or running tools.

Only provide commentary when it is genuinely needed, such as:

Giving a final report

Explaining a decision or finding

Sharing a plan

Asking a clarifying question

Prefer silent progress during implementation and reserve speech for meaningful handoffs or decisions.

If you encounter an ambiguity that affects design, speak up before proceeding; otherwise, work silently.

Key Principles
Performance-first mindset

Memory-conscious design

Documentation-driven development

Thorough understanding before action

No shortcuts in error handling or testing