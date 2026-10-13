package com.andrip.browser.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

object ExternalBrowser {
    fun open(context: Context, url: String) {
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        val choices = context.packageManager.queryIntentActivities(base, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map { Intent(base).setPackage(it.activityInfo.packageName) }
            .distinctBy { it.`package` }
        if (choices.isEmpty()) {
            Toast.makeText(context, "No external browser available", Toast.LENGTH_SHORT).show()
            return
        }
        context.startActivity(Intent.createChooser(choices.first(), "Open source in browser")
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, choices.drop(1).toTypedArray()))
    }
}
