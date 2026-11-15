package com.tufein.valkyr.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * A picked folder is stored as a tree URI (`…/tree/<id>`). A folder found *inside* a picked one is
 * stored as a document of that tree (`…/tree/<id>/document/<child id>`), so the permission given
 * for the picked folder still covers it.
 */
object SafUris {
    /** The document id of the folder [uri] stands for: the child's for a document of a tree, else the tree's own. */
    fun pickedId(uri: Uri): String {
        val segments = uri.pathSegments
        return if (segments.size >= 4 && segments[0] == "tree" && segments[2] == "document") segments[3]
        else DocumentsContract.getTreeDocumentId(uri)
    }

    /** The URI of the folder with document id [childId] inside the picked tree [tree]. */
    fun childOf(tree: Uri, childId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
}
