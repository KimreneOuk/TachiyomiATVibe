# Device reproduction report

## Capture

- APK: `app-standard-arm64-v8a-debug.apk`, package `app.kanade.tachiyomi.at.debug`
- Device: Android 16 / API 36 / arm64
- Chapter: `6474`
- Capture: `batch-download-trace.txt`

## Observed sequence

```text
request_phase ... to=waiting_for_download
queue_result ... result=enqueued
chapter_start ... page_total=24 resumed_ready=0 save_as_cbz=true
page_attempt_failed ... page_index=0..23 ... stage=split cause=invalid_page error_class=IllegalStateException
validation ... expected=24 ready=24 on_disk=0 error_count=0
download_terminal ... state=error cause=storage expected=24 ready=24 on_disk=0
```

Every page reaches `READY`, but every page also emits the same split-stage
`IllegalStateException`. The error is therefore deterministic and occurs
after acquisition, not on the final network page. Validation sees no files in
the temporary chapter directory and rejects the download; the translation
handoff is never reached.

## Code-level diagnosis

`Downloader.getOrDownloadImage` downloads/renames a page and then calls
`splitTallImageIfNeeded`. The current implementation re-lists `tmpDir` and
throws an `IllegalStateException` when no entry starts with the page prefix.
That exception is caught locally, so the page is still marked `READY` even
though the file was not observed by the directory listing. This explains the
contradictory `ready=24` / `on_disk=0` state.

This matches the SAF-specific behavior and regression visible in repository
history. Commit `7c517d5` passed the already-created `UniFile` directly into
the split helper and treated an empty temporary-directory listing as a
successful SAF visibility case. Commit `18e9f24` removed both protections,
restoring a fresh `tmpDir.listFiles()` lookup and an unconditional on-disk
count. The diagnostic capture reproduces the failure mode introduced by that
reversion.

No product behavior was changed during this investigation; only diagnostic
instrumentation was installed and exercised.
