package com.andrip.browser.download

/** Serializes parallel segment callbacks so the visible count cannot move backwards. */
internal class SegmentProgress(private val total: Int, private val report: (Int, String) -> Unit) {
    private var completed = 0

    init { require(total > 0) }

    @Synchronized fun start() { report(0, "0 of $total segments") }

    @Synchronized fun segmentDone() {
        check(completed < total)
        completed++
        report(completed * 100 / total, "$completed of $total segments")
    }

    @Synchronized fun finishing(detail: String) {
        report(completed * 100 / total, "$completed of $total segments · $detail")
    }
}
