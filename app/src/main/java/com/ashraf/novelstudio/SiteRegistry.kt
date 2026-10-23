package com.ashraf.novelstudio

import android.content.Context

internal object SiteRegistry {
    private fun adapters(context: Context): List<SiteAdapter> = listOf(
        WebNovelSystem(context),
        WtrLabSystem(context),
        QidianSystem(context),
        Novel543System(context)
    )

    fun find(context: Context, url: String?): SiteAdapter? =
        adapters(context).firstOrNull { it.matches(url) }
}
