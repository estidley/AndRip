package com.andrip.browser

import com.andrip.browser.download.SegmentProgress
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SegmentProgressTest {
    @Test fun parallelCompletionsStayOrderedAndKeepCountsDuringFinalization() {
        val updates = mutableListOf<Pair<Int, String>>()
        val progress = SegmentProgress(200) { percent, detail -> updates.add(percent to detail) }
        progress.start()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..200).map { pool.submit { progress.segmentDone() } }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(0 to "0 of 200 segments", updates.first())
        assertEquals((1..200).map { "$it of 200 segments" }, updates.drop(1).map { it.second })
        assertEquals(updates.map { it.first }.sorted(), updates.map { it.first })
        progress.finishing("Merging audio and video…")
        assertEquals(100 to "200 of 200 segments · Merging audio and video…", updates.last())
    }
}
