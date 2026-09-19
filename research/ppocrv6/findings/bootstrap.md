# PP-OCRv6 acceleration bootstrap

Date: 2026-09-18  
Worktree: `C:\Users\User\.traycer\worktrees\kimreneouk__tachiyomiatvibe\research-ppocrv6-optimization`  
Branch: `research/ppocrv6-optimization`  
HEAD: `9c19ad05bd62cc3c222dfa8527fd4407b037ecc6`

## Repository and model baseline

The worktree was clean at bootstrap and is based on the same commit as
`origin/main`. No upstream is configured for this research-only branch. No
production source or asset was modified.

The complete upstream detector repository is cloned into ignored
`research/cache/PP-OCRv6_small_det_onnx` from
`https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx` at revision
`28fe5895c24fd108c19eb3e8479f4ab385fbfc62` (the GitHub URL suggested in the
initial request does not exist). Its verified files are recorded in
`research/results/bootstrap.json`; the detector `inference.onnx` is 9,880,512
bytes with SHA-256
`d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`.

The in-app PP-OCRv6 small assets are:

| Role | Path | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| detector | `app/src/main/assets/models/ocr/paddle-v6-small/det/inference.onnx` | 9,880,512 | `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e` |
| recognizer | `app/src/main/assets/models/ocr/paddle-v6-small/inference.onnx` | 21,159,378 | `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634` |
| recognizer dictionary | `app/src/main/assets/models/ocr/paddle-v6-small/PP-OCRv6_small_rec.txt` | 74,947 | `b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d` |

The in-app detector binary is byte-identical to the pinned upstream detector.
The checked-in detector YAML has the same semantic fields as upstream; its
different byte hash is attributable to file formatting/line endings.

## Existing pipeline entry points

- `PaddleOcrV6DetEngine.kt` performs PP-OCRv6 DB detection: resize the long
  side to 736, pad to 736x736, ImageNet mean/std normalize in RGB CHW, run the
  ONNX model, then threshold the `[1,1,736,736]` map with DB thresholds 0.20 and
  0.45 and back-project boxes. The Kotlin path intentionally emits raw boxes
  without the metadata `unclip_ratio: 1.4`.
- `PaddleOcrV6SmallEngine.kt` performs recognition at height 48 with 640/1600
  width buckets, mid-gray padding, `(value/255 - 0.5)/0.5` normalization, NCHW
  RGB direct-buffer input, and CTC decoding through `PaddleCtcDecoder`.
- The current page/CTD-style detector is
  `OnnxPageTextDetector.kt` with
  `app/src/main/assets/models/detection/detector-v4-s_int8.onnx`: a fixed
  11,120,765-byte asset (SHA-256
  `5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79`), a fixed
  640x640 NCHW pass with `orig_target_sizes`, labels `bubble`, `text_bubble`,
  and `text_free`, confidence filtering at 0.45, and geometric same-label
  deduplication. It uses pooled direct buffers and accelerator fallback.
- `OnnxPanelDetector.kt` is a separate optional YOLO panel detector backed by
  `manga_panel_detector_int8.onnx`; it is not the CTD text detector.

Existing desktop/lab utilities include:

- `tools/translation_studio/paddle_ocr.py`: constant-for-constant desktop port
  of the PP-OCRv6 det/rec and vertical-line path.
- `tools/prototype_paddle_det.py` and `tools/prototype_paddle_fast.py`:
  detector-versus-Paddle prototypes and visual/inpaint experiments.
- `tools/mangaocr_lab/manga_ocr_lab.py`: repeatable MangaOCR batching lab with
  model verification, fixture generation, tracing, and JSON reports.

## Fixed external corpus

The manifest at `research/dataset/manifest.json` preserves absolute source
paths and annotation paths while keeping all page binaries outside this
worktree. It contains three chapters and 76 source pages with 486 annotated
regions:

| Source | Chapter | Pages | `.studio/ocr.json` | Fixture mix |
| --- | --- | ---: | --- | --- |
| supplied | `ore-ni-trauma-wo-ataeta-joshitachi-ga-chirachira-mitekuru-kedo-zannen-desu-ga-teokure-desu_ch16` | 32 | yes | bubble/free text, Japanese |
| downloaded | `tonari-no-seki-no-seijo-sama-wa-ore-ni-kossori-sukaato-no-naka-o-oshiete-kureru_ch1` | 18 | yes | bubble/free text, Japanese |
| downloaded | `watashitachi-ketsukon-shimashita-fuguu-na-ouji-to-tensai-majutsushi-wa-inochi-no-unmei-kyoudoutai_ch5` | 26 | yes | bubble/free text, Japanese |

The literal additional path supplied in the follow-up was not present. The
available equivalent is the same repository's `_gui_probe\dl_out` directory,
which is recorded as the observed source and the requested spelling is retained
in `research/results/bootstrap.json` for provenance.

## Blockers and handoff

- No Android device or accelerator timing was run during bootstrap; this commit
  only establishes assets, source-level contracts, and a fixed corpus.
- Page dimensions and pixels remain external; benchmark agents should read
  `source_path` values directly and must not copy or rewrite `.studio` files.
- The detector metadata declares `unclip_ratio: 1.4`, while the Kotlin
  implementation documents raw-box/no-unclip behavior. Any benchmark comparing
  reference and Android behavior must keep this distinction explicit.

Raw inventory: `research/results/bootstrap.json`  
Manifest generator: `research/scripts/build_dataset_manifest.py`  
Inventory generator: `research/scripts/inventory_ppocrv6.py`
