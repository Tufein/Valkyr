package com.tufein.valkyr.util

import java.net.InetAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 6.6: the pure parts of *Receive games over Wi-Fi*: the PIN, the checks on what a browser sends and
 * the pages it gets back. The socket and the storage are in `WifiReceiveService`. Plain HTTP on the
 * local network, behind a PIN, only while the user has switched it on.
 */
object WifiReceive {

    const val PIN_DIGITS = 6
    /** Wrong PINs before the receiver stops itself. */
    const val MAX_WRONG_PINS = 5
    /** Stops by itself after this long without a request, and after this long in all. */
    const val IDLE_MINUTES = 10
    const val TOTAL_MINUTES = 60
    /** One file larger than this is refused (before it is read). */
    const val MAX_FILE_BYTES = 32L * 1024 * 1024 * 1024
    const val MAX_HEAD_BYTES = 16 * 1024

    fun newPin(random: SecureRandom = SecureRandom()): String =
        (0 until PIN_DIGITS).joinToString("") { random.nextInt(10).toString() }

    /** Compares in constant time; a missing or differently long PIN never matches. */
    fun pinMatches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.trim().toByteArray(Charsets.UTF_8))

    /** Only addresses of the local network (and the device itself) may talk to the receiver. */
    fun isLocalClient(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress

    /** A request as far as the receiver needs it. */
    data class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    /** The request line and headers of [head] (everything before the empty line); null when they are not HTTP/1.x. */
    fun parseHead(head: String): Request? {
        val lines = head.split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) return null
        val target = first[1]
        if (!target.startsWith("/")) return null
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
        }.toMap()
        val headers = lines.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
            val at = line.indexOf(':')
            if (at <= 0) null else line.substring(0, at).trim().lowercase() to line.substring(at + 1).trim()
        }.toMap()
        return Request(first[0].uppercase(), path, query, headers)
    }

    /** The PIN a request carries: the `x-pin` header or the `pin` query parameter. */
    fun pinOf(request: Request): String? = request.headers["x-pin"] ?: request.query["pin"]

    /**
     * A file name that is safe to create in a console folder: no path, no control characters, not hidden,
     * not a leftover of the app, at most 200 characters. Null otherwise.
     */
    fun safeName(raw: String?): String? {
        val name = raw?.trim() ?: return null
        if (name.isEmpty() || name.length > 200) return null
        if (name.startsWith(".") || name.endsWith(".") || name.endsWith(" ")) return null
        if (name.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' || it.isISOControl() }) return null
        return name
    }

    /** Status line text for the few codes the receiver sends. */
    fun reason(code: Int): String = when (code) {
        200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        500 -> "Internal Server Error"; 507 -> "Insufficient Storage"; else -> "Error"
    }

    fun response(code: Int, contentType: String, body: ByteArray): ByteArray {
        val head = "HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
        return head.toByteArray(Charsets.UTF_8) + body
    }

    fun json(code: Int, body: String): ByteArray = response(code, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n")
            c.code < 0x20 -> append(String.format("\\u%04x", c.code)); else -> append(c)
        }
        append('"')
    }

    /** The one page a browser gets: PIN, console, files, progress. Texts come from the app's own strings. */
    fun page(title: String, pinLabel: String, consoleLabel: String, chooseLabel: String, sendLabel: String, doneLabel: String, wrongPin: String): ByteArray {
        fun h(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val js = """
const $=id=>document.getElementById(id);let pin='';
async function api(p,o){o=o||{};o.headers=Object.assign({'X-Pin':pin},o.headers||{});return fetch(p,o)}
async function unlock(){pin=$('pin').value.trim();const r=await api('/api/consoles');if(!r.ok){$('msg').textContent='${wrongPin.replace("'", "\\'")}';return}
const list=await r.json();$('console').innerHTML=list.map(c=>'<option value="'+c.id+'">'+c.label.replace(/</g,'&lt;')+'</option>').join('');$('step1').hidden=true;$('step2').hidden=false}
function send(){const files=[...$('files').files];const c=$('console').value;$('log').innerHTML='';
files.reduce((p,f)=>p.then(()=>new Promise(res=>{const row=document.createElement('div');row.textContent=f.name+' 0%';$('log').appendChild(row);
const x=new XMLHttpRequest();x.open('PUT','/api/upload?console='+encodeURIComponent(c)+'&name='+encodeURIComponent(f.name));x.setRequestHeader('X-Pin',pin);
x.upload.onprogress=e=>{row.textContent=f.name+' '+Math.round(100*e.loaded/Math.max(1,e.total))+'%'};
x.onloadend=()=>{row.textContent=f.name+' - '+(x.status==201?'${doneLabel.replace("'", "\\'")}':x.responseText||x.status);res()};x.send(f)})),Promise.resolve())}
"""
        val html = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${h(title)}</title>" +
            "<style>body{font-family:system-ui,sans-serif;background:#000;color:#eee;max-width:34em;margin:2em auto;padding:0 1em}input,select,button{font-size:1.1em;padding:.5em;margin:.4em 0;width:100%;box-sizing:border-box;background:#111;color:#eee;border:1px solid #555;border-radius:8px}button{background:#2a6df4;border:0}#msg{color:#f66}</style></head><body>" +
            "<h1>${h(title)}</h1><div id=\"step1\"><label>${h(pinLabel)}<input id=\"pin\" inputmode=\"numeric\" autocomplete=\"off\"></label><button onclick=\"unlock()\">OK</button><div id=\"msg\"></div></div>" +
            "<div id=\"step2\" hidden><label>${h(consoleLabel)}<select id=\"console\"></select></label><label>${h(chooseLabel)}<input id=\"files\" type=\"file\" multiple></label><button onclick=\"send()\">${h(sendLabel)}</button><div id=\"log\"></div></div>" +
            "<script>$js</script></body></html>"
        return response(200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }
}
