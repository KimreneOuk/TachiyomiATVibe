# Inpainting QA — fast vs quality (dynamic vs static-512)

18 real pages, 512x512 centered crops, CPU onnxruntime.

**Avg latency:** fast push-pull = 165ms | quality dynamic = 681ms | quality static-512 = 732ms

Static-512 vs dynamic speedup: 0.93x (if <1, static is slower).


| page | category | fast ms | dyn ms | stat ms | fast verdict | dyn verdict | stat verdict |
|---|---|---|---|---|---|---|---|
| real_001 | free_text_aot512 | 227 | 683 | 774 | accept | accept | accept |
| real_002 | free_text_aot512 | 199 | 690 | 744 | accept | accept | accept |
| real_010 | free_text_aot512 | 155 | 699 | 714 | accept | accept | accept |
| real_011 | free_text_aot512 | 142 | 675 | 736 | accept | accept | accept |
| real_012 | free_text_aot512 | 164 | 712 | 715 | accept | accept | accept |
| real_017 | free_text_aot512 | 138 | 708 | 744 | accept | accept | accept |
| real_021 | free_text_aot512 | 144 | 689 | 752 | accept | accept | accept |
| real_023 | free_text_aot512 | 187 | 676 | 796 | REJECT(near-white) | REJECT(near-white) | REJECT(near-white) |
| real_024 | free_text_aot512 | 164 | 678 | 714 | accept | accept | accept |
| real_025 | free_text_aot512 | 156 | 669 | 682 | accept | accept | accept |
| real_026 | free_text_aot512 | 151 | 638 | 725 | accept | accept | accept |
| real_030 | free_text_aot512 | 154 | 650 | 693 | accept | accept | accept |
