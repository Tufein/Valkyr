package com.tufein.valkyr.util

/**
 * 8.2: an S3-compatible bucket, written in the same "server" field the WebDAV address uses:
 * `s3://host[:port]/bucket` (https) or `s3+http://host:port/bucket` (plain http, for a MinIO on the
 * home network), with an optional `?region=eu-west-1`. The access key goes in the user name field,
 * the secret key in the password field and the folder is the prefix inside the bucket. Pure JVM.
 */
data class S3Target(val scheme: String, val authority: String, val bucket: String, val region: String) {

    /** `https://host/bucket/`: the path-style address every call is made against (works on every S3-compatible server). */
    val baseUrl: String get() = "$scheme://$authority/${S3Signer.encode(bucket)}/"

    val host: String get() = authority

    companion object {
        const val DEFAULT_REGION = "us-east-1"

        fun isS3(server: String): Boolean {
            val s = server.trim().lowercase()
            return s.startsWith("s3://") || s.startsWith("s3+http://")
        }

        /** The target [server] names, or null when it is not an S3 address or has no host or bucket. */
        fun parse(server: String): S3Target? {
            val text = server.trim()
            if (!isS3(text) || text.any { it.isWhitespace() }) return null
            val plain = text.substringBefore("://").lowercase() == "s3+http"
            var rest = text.substringAfter("://").substringBefore('#')
            val query = rest.substringAfter('?', "")
            rest = rest.substringBefore('?')
            val authority = rest.substringBefore('/').lowercase()
            if (authority.isEmpty() || authority.contains('@') || authority.startsWith(':')) return null
            val bucket = rest.substringAfter('/', "").split('/').firstOrNull { it.isNotEmpty() } ?: return null
            val explicit = query.split('&').firstOrNull { it.startsWith("region=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
            return S3Target(if (plain) "http" else "https", authority, bucket, explicit ?: regionOfHost(authority.substringBefore(':')))
        }

        /** `s3.eu-central-1.amazonaws.com` → eu-central-1, `s3.us-west-004.backblazeb2.com` → us-west-004; otherwise us-east-1. */
        fun regionOfHost(host: String): String {
            val labels = host.split('.')
            if (labels.size >= 3 && (labels[0] == "s3" || labels[0].startsWith("s3-"))) {
                val region = if (labels[0] == "s3") labels[1] else labels[0].removePrefix("s3-")
                if (region.isNotEmpty() && region != "amazonaws" && region != "dualstack") return region
            }
            return DEFAULT_REGION
        }
    }
}
