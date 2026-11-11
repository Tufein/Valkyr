package com.tufein.valkyr.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WifiReceiveTest {
    @Test fun pinHasSixDigitsAndIsComparedExactly() {
        val pin = WifiReceive.newPin()
        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
        assertTrue(WifiReceive.pinMatches("123456", " 123456 "))
        assertFalse(WifiReceive.pinMatches("123456", "12345"))
        assertFalse(WifiReceive.pinMatches("123456", null))
        assertFalse(WifiReceive.pinMatches("123456", ""))
    }

    @Test fun onlyLocalClientsAreServed() {
        assertTrue(WifiReceive.isLocalClient(InetAddress.getByName("192.168.1.20")))
        assertTrue(WifiReceive.isLocalClient(InetAddress.getByName("10.0.0.5")))
        assertTrue(WifiReceive.isLocalClient(InetAddress.getByName("127.0.0.1")))
        assertFalse(WifiReceive.isLocalClient(InetAddress.getByName("8.8.8.8")))
        assertFalse(WifiReceive.isLocalClient(InetAddress.getByName("93.184.216.34")))
    }

    @Test fun headIsParsedAndQueryDecoded() {
        val r = WifiReceive.parseHead("PUT /api/upload?console=gba&name=Advance%20Wars%20%28USA%29.gba HTTP/1.1\r\nHost: x\r\nContent-Length: 12\r\nX-Pin: 123456")!!
        assertEquals("PUT", r.method)
        assertEquals("/api/upload", r.path)
        assertEquals("Advance Wars (USA).gba", r.query["name"])
        assertEquals("12", r.headers["content-length"])
        assertEquals("123456", WifiReceive.pinOf(r))
        assertNull(WifiReceive.parseHead("GET / SPDY/3"))
        assertNull(WifiReceive.parseHead("GET http://evil/ HTTP/1.1"))
        assertNull(WifiReceive.parseHead("nonsense"))
    }

    @Test fun fileNamesCannotEscapeTheConsoleFolder() {
        assertEquals("Game (USA).gba", WifiReceive.safeName(" Game (USA).gba "))
        listOf(null, "", "../x.gba", "a/b.gba", "a\\b.gba", ".hidden", "x.", "con:x", "a\u0000b", "x".repeat(201), ".dogmatix-upload-1.part").forEach {
            assertNull(it, WifiReceive.safeName(it))
        }
    }

    @Test fun responsesCarryLengthAndNoSniffing() {
        val text = String(WifiReceive.json(401, "{\"a\":1}"))
        assertTrue(text.startsWith("HTTP/1.1 401 Unauthorized\r\n"))
        assertTrue(text.contains("Content-Length: 7"))
        assertTrue(text.contains("X-Content-Type-Options: nosniff"))
        assertEquals("\"a\\\"b\"", WifiReceive.jsonString("a\"b"))
    }
}
