package com.falsterboplan.lights.data

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.net.ssl.SSLException

class HubTlsTest {
    // Self-signed and issued for a name that is not the address we connect to, like the real hub.
    private val hubCert = HeldCertificate.Builder().commonName("hub-serial").addSubjectAlternativeName("hub-serial").build()
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(hubCert).build().sslSocketFactory(), false)
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(trust: PinningTrustManager): String =
        hubHttpClient(trust).newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }

    @Test
    fun firstConnectionRecordsPinAndLaterConnectionsRequireIt() {
        var seen: String? = null
        server.enqueue(MockResponse().setBody("first"))
        assertEquals("first", get(PinningTrustManager({ null }, { seen = it })))
        assertNotNull(seen)
        assertEquals(PinningTrustManager.pinOf(hubCert.certificate), seen)

        server.enqueue(MockResponse().setBody("second"))
        assertEquals("second", get(PinningTrustManager({ seen }, { error("already pinned") })))
    }

    @Test
    fun differentKeyIsRejected() {
        val other = PinningTrustManager.pinOf(HeldCertificate.Builder().commonName("impostor").build().certificate)
        server.enqueue(MockResponse().setBody("secret"))
        val failure = runCatching { get(PinningTrustManager({ other }, {})) }.exceptionOrNull()
        assertTrue("expected TLS failure, got $failure", failure is SSLException)
        assertEquals(0, server.requestCount)
    }
}
