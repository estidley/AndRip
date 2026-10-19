package com.andrip.browser.download

/** Serializes parallel segment callbacks so the visible count cannot move backwards. */
internal class SegmentProgress(
    private val total: Int,
    private val note: String = "",
    private val report: (Int, String) -> Unit,
) {
    private var completed = 0
    private val startedNs = System.nanoTime()

    init { require(total > 0) }

    @Synchronized fun start() { report(0, line()) }

    @Synchronized fun segmentDone() {
        check(completed < total)
        completed++
        report(completed * 100 / total, line())
    }

    @Synchronized fun finishing(detail: String) {
        report(completed * 100 / total, line(detail))
    }

    private fun line(extra: String = ""): String = buildString {
        append(completed)
        append(" of ")
        append(total)
        append(" segments")
        append(etaSuffix())
        if (note.isNotEmpty()) append(" · ").append(note)
        if (extra.isNotEmpty()) append(" · ").append(extra)
    }

    /** Rough remaining time once a transfer has been moving long enough to estimate. */
    private fun etaSuffix(): String {
        if (completed <= 0 || completed >= total) return ""
        val elapsed = System.nanoTime() - startedNs
        if (elapsed < 500_000_000L) return ""
        val seconds = (elapsed.toDouble() * (total - completed) / completed / 1_000_000_000.0).toLong().coerceAtLeast(1)
        val text = when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
        return " · $text left"
    }
}
