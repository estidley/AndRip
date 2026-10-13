package com.andrip.browser.download

import android.media.MediaExtractor
import java.io.File

object MediaProtection {
    /** No license requests or decryption keys are used. Check container metadata before export. */
    fun checkFile(file: File) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            if (extractor.drmInitData != null || !extractor.psshInfo.isNullOrEmpty()) {
                throw UnsupportedStreamException("This media contains DRM protection and cannot be saved.")
            }
            if (extractor.trackCount == 0) throw UnsupportedStreamException("No playable media tracks were found. This container may not be supported by your device.")
            for (index in 0 until extractor.trackCount) {
                extractor.selectTrack(index)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                    throw UnsupportedStreamException("This media contains encrypted samples and cannot be saved.")
                }
                extractor.unselectTrack(index)
            }
        } finally { extractor.release() }
    }
}
