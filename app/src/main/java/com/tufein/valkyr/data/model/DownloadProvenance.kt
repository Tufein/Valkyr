package com.tufein.valkyr.data.model

/**
 * Where a download came from, as safe labels (hosts or kinds — never addresses with keys).
 * [original] stays the first source after a switch; [switchedFrom] names the source that failed.
 */
data class DownloadProvenance(
    val source: String,
    val original: String = source,
    val switchedFrom: String = ""
)
