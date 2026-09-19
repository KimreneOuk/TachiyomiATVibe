# PP-OCRv6 INT8/QDQ research

`test_int8.py` and `test_qdq.py` evaluate the committed PP-OCRv6 small
recognizer and detector independently and in mixed detector/recognizer pairs.
The harness uses the Android-matching desktop port in
`tools/translation_studio/paddle_ocr.py` for preprocessing, CTC decode, and DB
box postprocess. `fixed_manifest.json` is the only default corpus source.

Run from the repository root:

```powershell
python research/test_int8.py --max-fixtures 12 --calibration-fixtures 12
python research/test_qdq.py --max-fixtures 12 --calibration-fixtures 12
```

The default run attempts dynamic per-channel weights, static QOperator
per-tensor and per-channel, and static QDQ per-tensor and per-channel. Every
conversion/load/runtime failure is retained in `research/results/*.json`; the
generated model files remain ignored under `research/cache/`.

Evidence labels in `findings/int8_qdq.md` distinguish observed local CPU
measurements from inferences and blocked Android/device checks. Host timings
are not Android performance claims.

