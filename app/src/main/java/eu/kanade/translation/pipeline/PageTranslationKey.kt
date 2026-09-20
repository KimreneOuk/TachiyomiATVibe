package eu.kanade.translation.pipeline

import eu.kanade.translation.*
import eu.kanade.translation.orchestration.*
import eu.kanade.translation.storage.*

/** Derives the URL-based page key used while a page is loaded online. */
fun onlinePageTranslationKey(imageUrl: String?, pageUrl: String): String =
    imageUrl?.substringAfterLast('/')?.substringBefore('?')
        ?: pageUrl.substringAfterLast('/').substringBefore('?')
