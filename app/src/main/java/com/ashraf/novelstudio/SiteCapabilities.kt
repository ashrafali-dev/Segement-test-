package com.ashraf.novelstudio

internal data class SiteCapabilities(
    val extraction: Boolean = false,
    val nextChapter: Boolean = false,
    val previousChapter: Boolean = false,
    val translationInsert: Boolean = false,
    val htmlPreserve: Boolean = false,
    val autoNext: Boolean = false,
    val chapterNumberDetection: Boolean = false
)
