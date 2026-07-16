with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace(
    '"TachiyomiAT revision patch applied (kept=\): chapter=\ " +\n                            "pageKey=\ blockIndex=\ anchorId="',
    '"TachiyomiAT revision patch applied (kept=$isKept): chapter=$chapterName " +\n                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId"'
)
content = content.replace(
    '"TachiyomiAT revision patch rejected: chapter=\ " +\n                            "pageKey=\ blockIndex=\ anchorId=\ " +\n                            "reason="',
    '"TachiyomiAT revision patch rejected: chapter=$chapterName " +\n                            "pageKey=$pageKey blockIndex=$blockIndex anchorId=$anchorId " +\n                            "reason=${patchResult.reason}"'
)
content = content.replace(
    'description = "Pass-2 revision correction chapter=\",',
    'description = "Pass-2 revision correction chapter=$chapterName",'
)

with open('app/src/main/java/eu/kanade/translation/translator/RevisionCommitter.kt', 'w', encoding='utf-8') as f:
    f.write(content)
