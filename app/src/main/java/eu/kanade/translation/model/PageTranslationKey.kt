package eu.kanade.translation.model

/** Derives the URL-based page key used while a page is loaded online. */
fun onlinePageTranslationKey(imageUrl: String?, pageUrl: String): String =
    imageUrl?.substringAfterLast('/')?.substringBefore('?')
        ?: pageUrl.substringAfterLast('/').substringBefore('?')
