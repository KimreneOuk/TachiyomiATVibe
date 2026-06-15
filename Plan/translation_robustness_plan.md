# Translation Robustness Plan

## Problems Found

1. Manual translate button is attached to the page container, not the actual image bounds.
2. Auto translation can cancel/restart itself on repeated page-selection events.
3. Reader streams are removed too early, so retries can fall back to missing download files.
4. Translation store JSON can become corrupted by stale bytes or partial writes.
5. Downloaded chapter loading can throw on missing/stale SAF paths instead of failing cleanly.

## Implementation Plan

1. Reposition the per-page translate button using the decoded image rect instead of the full holder frame.
2. Stop cancelling an active job when `translatePage()` receives a duplicate request for the same page.
3. Keep reader-provided streams available until chapter cleanup instead of removing them on first use.
4. Make translation-store persistence atomic or truncating so writes cannot leave garbage tails.
5. Harden downloaded chapter directory/page discovery to return reader errors instead of crashing.
6. Reproduce the downloaded-chapter crash with live `adb logcat` while opening a downloaded reader session.

## Verification

1. Manual mode: button overlays the image correctly in pager and vertical strip modes.
2. Auto mode: enabling it starts translating immediately and does not oscillate between cancel/restart.
3. Online chapter: manual and auto translation both work without a local download.
4. Downloaded chapter: reader opens without crashing and shows a usable error if storage is invalid.
5. Translation files remain readable after multiple page translations and app restarts.
