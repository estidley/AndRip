package com.andrip.browser

import com.andrip.browser.data.AppDatabase

/** Robolectric resets SQLite handles between methods; reset our process singleton with them. */
internal fun closeTestDatabase() {
    val field = AppDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
    (field.get(null) as? AppDatabase)?.close()
    field.set(null, null)
}
