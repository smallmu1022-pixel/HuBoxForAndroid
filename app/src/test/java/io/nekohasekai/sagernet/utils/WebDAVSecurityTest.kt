package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDAVSecurityTest {
    @Test fun acceptsHttpsWithoutEmbeddedCredentials() {
        assertEquals("https://example.com/dav", WebDAVSecurity.parseHttpsUrl("https://example.com/dav").toString())
    }

    @Test fun rejectsHttpAndEmbeddedCredentials() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDAVSecurity.parseHttpsUrl("http://example.com/dav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDAVSecurity.parseHttpsUrl("https://user:pass@example.com/dav")
        }
    }
}
