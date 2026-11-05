package io.github.easylsp.app.platform.analysis

import android.util.Xml
import jadx.api.security.JadxSecurityFlag
import jadx.api.security.impl.JadxSecurity
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Android's DOM factory does not support the Xerces features requested by JADX. */
class AndroidJadxSecurity : JadxSecurity(JadxSecurityFlag.all()) {
    override fun parseXml(input: InputStream): Document {
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        var node: Node = document
        while (true) {
            when (parser.nextToken()) {
                XmlPullParser.END_DOCUMENT -> return document
                XmlPullParser.DOCDECL -> throw XmlPullParserException("DOCTYPE declarations are not supported")
                XmlPullParser.START_TAG -> {
                    val element = document.createElementNS(parser.namespace.ifEmpty { null }, qualified(parser.prefix, parser.name))
                    for (index in parser.getNamespaceCount(parser.depth - 1) until parser.getNamespaceCount(parser.depth)) {
                        val prefix = parser.getNamespacePrefix(index)
                        element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, if (prefix.isNullOrEmpty()) "xmlns" else "xmlns:$prefix", parser.getNamespaceUri(index))
                    }
                    for (index in 0 until parser.attributeCount) {
                        element.setAttributeNS(parser.getAttributeNamespace(index).ifEmpty { null }, qualified(parser.getAttributePrefix(index), parser.getAttributeName(index)), parser.getAttributeValue(index))
                    }
                    node.appendChild(element)
                    node = element
                }
                XmlPullParser.END_TAG -> node = node.parentNode
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                    val text = parser.text ?: throw XmlPullParserException("Unresolved XML entity")
                    if (node !== document) node.appendChild(document.createTextNode(text))
                }
            }
        }
    }

    private fun qualified(prefix: String?, name: String) = if (prefix.isNullOrEmpty()) name else "$prefix:$name"
}
