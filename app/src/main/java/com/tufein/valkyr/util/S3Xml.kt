package com.tufein.valkyr.util

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/** 8.2: the two S3 answers the app reads: a `ListBucketResult` and an `Error`. Pure JVM. */
object S3Xml {
    data class Item(val key: String, val size: Long, val lastModified: Long?, val etag: String?)
    data class Listing(val items: List<Item>, val prefixes: List<String>, val nextToken: String?)
    data class Error(val code: String, val message: String, val region: String? = null)

    fun parseListing(xml: String): Listing {
        val root = document(xml)
        val items = children(root, "Contents").map { c ->
            Item(
                text(c, "Key").orEmpty(),
                text(c, "Size")?.toLongOrNull() ?: 0L,
                text(c, "LastModified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                text(c, "ETag")?.trim('"')
            )
        }.filter { it.key.isNotEmpty() }
        val prefixes = children(root, "CommonPrefixes").mapNotNull { text(it, "Prefix") }
        val next = if (text(root, "IsTruncated") == "true") text(root, "NextContinuationToken") else null
        return Listing(items, prefixes, next)
    }

    /** The code and message of an S3 error body; null when [xml] is not one. */
    fun parseError(xml: String): Error? = runCatching {
        val root = document(xml)
        if ((root.localName ?: root.tagName) != "Error") return@runCatching null
        Error(text(root, "Code").orEmpty(), text(root, "Message").orEmpty(), text(root, "Region"))
    }.getOrNull()

    private fun document(xml: String): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        return builder.parse(InputSource(StringReader(xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')))).documentElement
    }

    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && ((n as Element).localName ?: n.tagName.substringAfter(':')) == name) out += n
        }
        return out
    }

    private fun text(parent: Element, name: String): String? = children(parent, name).firstOrNull()?.textContent
}
