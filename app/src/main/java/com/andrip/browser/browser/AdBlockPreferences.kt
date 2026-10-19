package com.andrip.browser.browser

import android.content.Context

object AdBlockPreferences {
    private const val FILE = "browser"
    private const val KEY = "adblock"

    /** On until the user turns it off. The choice is stored outside browsing data, so a close does not reset it. */
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).commit()
    }
}
