package io.github.easylsp.app

import io.github.easylsp.app.platform.analysis.AndroidJadxSecurity
import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParserException

class AndroidXmlTest {
    @Test fun preservesNamespacesAndTextAcrossEncodings() {
        val xml = """<?xml version="1.0" encoding="UTF-16"?><resources xmlns:a="urn:test"><item a:name="标题">A&amp;B<![CDATA[<C>]]></item></resources>"""
        val document = AndroidJadxSecurity().parseXml(xml.toByteArray(Charsets.UTF_16).inputStream())
        val item = document.getElementsByTagName("item").item(0)
        assertEquals("标题", item.attributes.getNamedItemNS("urn:test", "name").nodeValue)
        assertEquals("A&B<C>", item.textContent)
    }

    @Test fun rejectsDocumentTypesBeforeResolvingEntities() {
        val xml = """<!DOCTYPE resources [<!ENTITY external SYSTEM "file:///does-not-exist">]><resources>&external;</resources>"""
        try {
            AndroidJadxSecurity().parseXml(xml.byteInputStream())
            fail("must reject document types")
        } catch (expected: XmlPullParserException) {
            assertTrue(expected.message!!.contains("DOCTYPE"))
        }
    }
}
