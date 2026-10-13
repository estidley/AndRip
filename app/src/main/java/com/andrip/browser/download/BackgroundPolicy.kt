package com.andrip.browser.download

import java.io.IOException

/** Each scheduled attempt already includes the transport's bounded request retries. */
object BackgroundPolicy {
    const val MAX_RETRIES = 2
    fun shouldRetry(error: Exception, attempt: Int): Boolean =
        attempt < MAX_RETRIES && error is IOException && HttpPolicy.retryable(error)
}
