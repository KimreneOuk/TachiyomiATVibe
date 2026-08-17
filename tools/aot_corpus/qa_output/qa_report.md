# Inpainting QA — fast vs quality (dynamic vs static-512)

42 faithful free-text pages (AOT-512 path inputs), 512x512 centered crops, CPU onnxruntime.

**Avg latency:** fast push-pull = 146ms | quality dynamic = 642ms | quality static-512 = 687ms

Static-512 vs dynamic speedup: 0.93x (if <1, static is slower).


| page | category | fast ms | dyn ms | stat ms | fast verdict | dyn verdict | stat verdict |
|---|---|---|---|---|---|---|---|
| real_001__ft_001 | free_text_aot512 | 174 | 676 | 715 | accept | accept | accept |
| real_001__ft_002 | free_text_aot512 | 147 | 615 | 663 | accept | accept | accept |
| real_002__ft_001 | free_text_aot512 | 149 | 660 | 672 | accept | accept | accept |
| real_002__ft_002 | free_text_aot512 | 148 | 693 | 659 | accept | accept | accept |
| real_008__ft_001 | free_text_aot512 | 142 | 623 | 685 | accept | accept | accept |
| real_008__ft_002 | free_text_aot512 | 145 | 645 | 706 | accept | accept | accept |
| real_008__ft_003 | free_text_aot512 | 160 | 626 | 681 | accept | accept | accept |
| real_008__ft_004 | free_text_aot512 | 143 | 647 | 695 | accept | accept | accept |
| real_010__ft_001 | free_text_aot512 | 140 | 646 | 668 | accept | accept | accept |
| real_010__ft_002 | free_text_aot512 | 145 | 649 | 696 | accept | accept | accept |
| real_011__ft_001 | free_text_aot512 | 143 | 655 | 705 | accept | accept | accept |
| real_012__ft_001 | free_text_aot512 | 165 | 624 | 673 | accept | accept | accept |
| real_017__ft_001 | free_text_aot512 | 140 | 637 | 694 | accept | accept | accept |
| real_017__ft_002 | free_text_aot512 | 143 | 672 | 670 | accept | accept | accept |
| real_017__ft_003 | free_text_aot512 | 136 | 607 | 674 | accept | accept | accept |
| real_020__ft_001 | free_text_aot512 | 135 | 626 | 723 | accept | accept | accept |
| real_020__ft_002 | free_text_aot512 | 147 | 638 | 666 | accept | accept | accept |
| real_020__ft_003 | free_text_aot512 | 129 | 617 | 697 | accept | accept | accept |
| real_021__ft_001 | free_text_aot512 | 148 | 650 | 680 | accept | accept | accept |
| real_021__ft_002 | free_text_aot512 | 131 | 615 | 705 | accept | accept | accept |
| real_021__ft_003 | free_text_aot512 | 146 | 674 | 695 | accept | accept | accept |
| real_021__ft_004 | free_text_aot512 | 135 | 637 | 667 | accept | accept | accept |
| real_023__ft_001 | free_text_aot512 | 146 | 632 | 705 | REJECT(near-white) | REJECT(near-white) | REJECT(near-white) |
| real_024__ft_001 | free_text_aot512 | 150 | 641 | 712 | accept | accept | accept |
| real_024__ft_002 | free_text_aot512 | 152 | 632 | 672 | accept | accept | accept |
| real_024__ft_003 | free_text_aot512 | 146 | 662 | 681 | accept | accept | accept |
| real_024__ft_004 | free_text_aot512 | 146 | 645 | 674 | accept | accept | accept |
| real_024__ft_005 | free_text_aot512 | 136 | 641 | 686 | accept | accept | accept |
| real_024__ft_006 | free_text_aot512 | 155 | 653 | 715 | accept | accept | accept |
| real_024__ft_007 | free_text_aot512 | 139 | 626 | 665 | accept | accept | accept |
| real_024__ft_008 | free_text_aot512 | 134 | 652 | 696 | accept | accept | accept |
| real_025__ft_001 | free_text_aot512 | 149 | 656 | 693 | accept | accept | accept |
| real_025__ft_002 | free_text_aot512 | 139 | 622 | 677 | accept | accept | accept |
| real_025__ft_003 | free_text_aot512 | 156 | 656 | 699 | accept | accept | accept |
| real_025__ft_004 | free_text_aot512 | 148 | 647 | 666 | accept | accept | accept |
| real_025__ft_005 | free_text_aot512 | 141 | 660 | 696 | accept | accept | accept |
| real_026__ft_001 | free_text_aot512 | 150 | 633 | 669 | accept | accept | accept |
| real_026__ft_002 | free_text_aot512 | 149 | 608 | 670 | accept | accept | accept |
| real_026__ft_003 | free_text_aot512 | 142 | 659 | 693 | accept | accept | accept |
| real_026__ft_004 | free_text_aot512 | 159 | 625 | 672 | accept | accept | accept |
| real_026__ft_005 | free_text_aot512 | 139 | 635 | 680 | accept | accept | accept |
| real_030__ft_001 | free_text_aot512 | 146 | 653 | 742 | accept | accept | accept |
