package com.andrip.browser.download

import android.content.Context

object DownloadPreferences {
    fun unmeteredOnly(context: Context) = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
        .getBoolean("unmetered", false)
    fun setUnmeteredOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putBoolean("unmetered", enabled).apply()
    }
}
