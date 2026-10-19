package com.andrip.browser

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Package visibility queries have stricter syntax than activity intent filters. */
class ManifestContractTest {
    @Test fun visibilityQueriesHaveOnlyOneSchemePerIntent() {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() }
            ?: File("app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val queries = document.getElementsByTagName("queries").item(0) as Element
        val intents = queries.getElementsByTagName("intent")
        val schemes = mutableSetOf<String>()
        for (i in 0 until intents.length) {
            val data = (intents.item(i) as Element).getElementsByTagName("data")
            val current = (0 until data.length).mapNotNull {
                (data.item(it) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "scheme").takeIf(String::isNotBlank)
            }
            assertTrue("Android rejects query intents containing multiple data schemes", current.size <= 1)
            schemes.addAll(current)
        }
        assertEquals(setOf("http", "https"), schemes)
    }

    @Test fun sessionGuardStaysAliveSoTaskRemovalCanClearBrowsingData() {
        val file = File("src/main/AndroidManifest.xml").takeIf { it.exists() }
            ?: File("app/src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file)
        val services = document.getElementsByTagName("service")
        var guard: Element? = null
        for (i in 0 until services.length) {
            val element = services.item(i) as Element
            if (element.getAttributeNS("http://schemas.android.com/apk/res/android", "name") == ".privacy.SessionGuardService") {
                guard = element
            }
        }
        assertNotNull(guard)
        assertEquals("false", guard!!.getAttributeNS("http://schemas.android.com/apk/res/android", "exported"))
        assertEquals("false", guard.getAttributeNS("http://schemas.android.com/apk/res/android", "stopWithTask"))
    }
}
