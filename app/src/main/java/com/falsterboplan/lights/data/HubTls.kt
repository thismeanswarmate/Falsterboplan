package com.falsterboplan.lights.data

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The hub serves a self-signed certificate that no public CA vouches for, so normal validation
 * can't work. Instead we trust on first use: the hub's public key is remembered when pairing and
 * every later connection must present the same key.
 */
class PinningTrustManager(
    private val expectedPin: () -> String?,
    private val onUnpinned: (String) -> Unit,
) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Hub sent no certificate")
        val pin = pinOf(leaf)
        val expected = expectedPin()
        if (expected == null) {
            onUnpinned(pin)
        } else if (expected != pin) {
            throw CertificateException(
                "The hub's identity has changed since pairing. Forget the hub and pair again if you trust this network.",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun pinOf(cert: X509Certificate): String =
            Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded),
            )
    }
}

fun hubHttpClient(trustManager: PinningTrustManager): OkHttpClient {
    val context = SSLContext.getInstance("TLS")
    context.init(null, arrayOf(trustManager), null)
    return OkHttpClient.Builder()
        .sslSocketFactory(context.socketFactory, trustManager)
        // The certificate is issued for the hub's serial number, not its IP; the key pin is the check.
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
}
