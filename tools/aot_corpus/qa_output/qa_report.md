# Inpainting QA — fast vs quality (dynamic vs static-512)

18 real pages, 512x512 centered crops, CPU onnxruntime.

**Avg latency:** fast push-pull = 168ms | quality dynamic = 651ms | quality static-512 = 666ms

Static-512 vs dynamic speedup: 0.98x (if <1, static is slower).


| page | category | fast ms | dyn ms | stat ms | fast verdict | dyn verdict | stat verdict |
|---|---|---|---|---|---|---|---|
| real_002 | dense_text | 177 | 663 | 675 | accept | accept | accept |
| real_003 | screentone_heavy | 167 | 640 | 661 | accept | accept | accept |
| real_004 | screentone_heavy | 153 | 667 | 664 | accept | accept | accept |
| real_005 | small_pages_lt512 | 162 | 652 | 658 | accept | accept | accept |
| real_006 | tall_wide_asymmetric | 184 | 659 | 656 | accept | accept | accept |
| real_008 | large_bubble | 173 | 664 | 674 | accept | accept | accept |
| real_010 | tall_wide_asymmetric | 189 | 664 | 662 | accept | accept | accept |
| real_011 | large_bubble | 171 | 634 | 681 | accept | accept | accept |
| real_012 | vertical_jp_text | 165 | 650 | 663 | accept | accept | accept |
| real_013 | tall_wide_asymmetric | 162 | 637 | 665 | accept | accept | accept |
| real_014 | small_pages_lt512 | 155 | 634 | 676 | accept | accept | accept |
| real_017 | dense_text | 178 | 632 | 653 | accept | accept | accept |
| real_019 | dense_text | 162 | 668 | 681 | accept | accept | accept |
| real_020 | vertical_jp_text | 166 | 647 | 653 | accept | accept | accept |
| real_022 | small_pages_lt512 | 143 | 649 | 672 | accept | accept | accept |
| real_025 | dense_text | 188 | 639 | 656 | accept | accept | accept |
| real_028 | large_bubble | 184 | 649 | 662 | accept | accept | accept |
| real_030 | screentone_heavy | 144 | 664 | 685 | accept | accept | accept |

## Objective quality metrics

Two per-page metrics (computed on the 512x512 crops):

- **seam** = mean gradient magnitude at the mask boundary (lower = less visible seam).
  Fast push-pull scores low here partly because it fills flat, so there is no
  texture mismatch at the edge — a smooth seam on a flat fill is not better
  reconstruction, just less detail to mismatch.
- **hole-var** = std-dev of inpainted pixels inside the hole (higher = preserves
  surrounding detail; the guard rejects at var < 9, i.e. a flat/uniform fill).

| page | category | seam fast | seam dyn | seam stat | holevar fast | holevar dyn | holevar stat |
|---|---|---|---|---|---|---|---|
| real_002 | dense_text | 29.9 | 32.4 | 32.4 | 9.6 | 33.0 | 33.0 |
| real_003 | screentone_heavy | 31.2 | 14.4 | 14.4 | 51.9 | 40.4 | 40.4 |
| real_004 | screentone_heavy | 31.6 | 31.2 | 31.2 | 11.6 | 41.9 | 41.9 |
| real_005 | small_pages_lt512 | 5.1 | 5.4 | 5.4 | 4.1 | 13.1 | 13.1 |
| real_006 | tall_wide_asymmetric | 10.7 | 9.5 | 9.5 | 14.2 | 25.5 | 25.5 |
| real_008 | large_bubble | 64.3 | 61.0 | 61.0 | 12.5 | 63.1 | 63.1 |
| real_010 | tall_wide_asymmetric | 24.5 | 23.3 | 23.3 | 10.4 | 36.7 | 36.7 |
| real_011 | large_bubble | 40.6 | 41.5 | 41.5 | 8.4 | 38.3 | 38.3 |
| real_012 | vertical_jp_text | 9.0 | 7.6 | 7.6 | 12.2 | 25.8 | 25.8 |
| real_013 | tall_wide_asymmetric | 5.9 | 5.9 | 5.9 | 4.9 | 11.6 | 11.6 |
| real_014 | small_pages_lt512 | 16.1 | 16.7 | 16.7 | 8.0 | 25.0 | 25.0 |
| real_017 | dense_text | 19.2 | 17.7 | 17.7 | 11.3 | 30.3 | 30.3 |
| real_019 | dense_text | 22.7 | 20.2 | 20.2 | 10.8 | 45.9 | 45.9 |
| real_020 | vertical_jp_text | 13.4 | 11.8 | 11.8 | 7.7 | 34.9 | 34.9 |
| real_022 | small_pages_lt512 | 18.6 | 19.2 | 19.2 | 10.9 | 20.7 | 20.7 |
| real_025 | dense_text | 26.9 | 24.2 | 24.2 | 15.3 | 97.7 | 97.7 |
| real_028 | large_bubble | 40.7 | 41.6 | 41.6 | 19.2 | 61.4 | 61.4 |
| real_030 | screentone_heavy | 19.7 | 23.7 | 23.7 | 9.4 | 48.8 | 48.8 |
| **MEAN** | | **23.9** | **22.6** | **22.6** | — | — | — |

## Findings

**1. Dynamic and static-512 produce visually identical output.** On every page
the seam metric and hole-variance match to one decimal place. This confirms the
CP3 numerics gate (max-abs-diff 8.31e-5) at the visual level: the 1940→400 node
reduction via onnxslim lost no perceptible quality. The Tier 3 guard-rejection
gate (0 new rejections) and this QA agree.

**2. Fast push-pull flattens the hole; quality reconstructs texture.** Mean
in-hole std-dev: fast ≈ 10, quality ≈ 35. Quality preserves surrounding detail
(screentone, gradients, shading) inside the erased region; fast fills with a
smooth gradient. This is the expected algorithmic tradeoff and the reason
quality mode exists.

**3. Static-512 is NOT faster than dynamic on CPU.** Median timing over 10 runs
(after 3 warmup) on 5 representative pages:

| page | dyn median ms | stat median ms | stat/dyn |
|---|---|---|---|
| real_008 | 674 | 668 | 0.99 |
| real_025 | 671 | 677 | 1.01 |
| real_003 | 676 | 665 | 0.98 |
| real_005 | 694 | 686 | 0.99 |
| real_012 | 701 | 702 | 1.00 |

Node counts: dynamic = 1940, static = 400 (static/dyn = 0.21). The 79% node
reduction does **not** produce a CPU speedup. Constant folding + shape inference
removed graph structure, not runtime compute — the dynamic model already
specializes its shapes at session-run time.

**Implication for Wave 5.2:** switching the prod path from dynamic to static-512
on CPU yields equal quality, equal speed, equal memory. There is no standalone
CPU benefit. The static-512 payoff is an NNAPI/NPU story (Wave 5.3): fixed
shapes enable better operator partitioning and delegate selection, which is
where the speedup the design anticipated should materialize. Wiring static-512
into the CPU path now is safe (gate passes, quality identical) but is a no-op
for the user until NNAPI lands.

## How to view the images

Open any `tools/aot_corpus/qa_output/<page>.png` — each is a 4-panel
side-by-side: input+mask-overlay | fast push-pull | quality dynamic | quality
static-512, with per-panel timing and guard verdict in the header.
