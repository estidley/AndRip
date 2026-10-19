package com.andrip.browser.privacy

import android.content.Context

/**
 * Browsing data is cleared only when a session fully ends, not when the app is backgrounded.
 *
 * A session is marked open from the activity. It is marked clean only after a successful wipe.
 * Wipe points:
 * - [onFullClose]: the task was swiped away (`SessionGuardService.onTaskRemoved`) or the user chose Exit.
 * - [onProcessStart]: the process is starting and the previous session never recorded a clean wipe
 *   (task swipe where `onDestroy` / `onTaskRemoved` did not run, or the process was killed).
 *
 * Switching apps only pauses the activity, so neither path runs and cookies, history and cache stay.
 * If Android later kills that process, the next cold start takes the fallback path and wipes.
 * Saved downloads are not browsing data; [BrowsingDataWiper] does not delete them.
 */
object SessionPrivacy {
    private const val FILE = "session_privacy"
    private const val KEY = "closed_cleanly"

    fun closedCleanly(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    /** @return true when leftover browsing data from an unclean previous session was wiped. */
    fun onProcessStart(context: Context, wipe: (Context) -> Unit = BrowsingDataWiper::wipe): Boolean {
        if (closedCleanly(context)) return false
        wipe(context)
        markClean(context)
        return true
    }

    /** Call when the user-visible browser session starts. Does not delete anything. */
    fun onUiSessionStart(context: Context) {
        prefs(context).edit().putBoolean(KEY, false).commit()
    }

    fun onFullClose(context: Context, wipe: (Context) -> Unit = BrowsingDataWiper::wipe) {
        wipe(context)
        markClean(context)
    }

    private fun markClean(context: Context) {
        prefs(context).edit().putBoolean(KEY, true).commit()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
