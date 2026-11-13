package io.nekohasekai.sagernet.utils

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** WebDAV Basic Auth credentials must only be sent over TLS. */
object WebDAVSecurity {
    fun parseHttpsUrl(server: String): HttpUrl {
        val url = server.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid WebDAV server URL")
        require(url.isHttps) { "WebDAV requires an HTTPS server to protect credentials" }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Do not embed WebDAV credentials in the server URL"
        }
        return url
    }
}
