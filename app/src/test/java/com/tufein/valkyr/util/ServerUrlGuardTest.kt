package com.tufein.valkyr.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlGuardTest {
    private val base = "https://romm.lan:8080"

    @Test fun acceptsFilesOfTheServer() {
        assertTrue(ServerUrlGuard.isOnServer(base, "https://romm.lan:8080/assets/romm/resources/roms/1/manual/m.pdf"))
        assertTrue(ServerUrlGuard.isOnServer(base, "HTTPS://ROMM.LAN:8080/assets/a%20b.pdf"))
        assertTrue(ServerUrlGuard.isOnServer("https://h.lan/romm/", "https://h.lan/romm/assets/m.pdf"))
        assertTrue(ServerUrlGuard.isOnServer("https://h.lan", "https://h.lan:443/assets/m.pdf"))
    }

    @Test fun refusesOtherHostsPortsAndSchemes() {
        assertFalse(ServerUrlGuard.isOnServer(base, "https://romm.lan:8080.evil.com/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer("https://romm.lan", "https://romm.lan.evil.com/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "https://romm.lan:9090/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "http://romm.lan:8080/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "https://user@romm.lan:8080/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "https://other.example/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "ftp://romm.lan:8080/x.pdf"))
    }

    @Test fun refusesTraversalAndOutsideBasePath() {
        assertFalse(ServerUrlGuard.isOnServer(base, "https://romm.lan:8080/assets/../api/x"))
        assertFalse(ServerUrlGuard.isOnServer(base, "https://romm.lan:8080/assets/%2e%2e/api/x"))
        assertFalse(ServerUrlGuard.isOnServer(base, "https://romm.lan:8080/assets/..%2fapi"))
        assertFalse(ServerUrlGuard.isOnServer("https://h.lan/romm", "https://h.lan/other/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer("https://h.lan/romm", "https://h.lan/romm-evil/x.pdf"))
        assertFalse(ServerUrlGuard.isOnServer(base, "not a url"))
    }
}
