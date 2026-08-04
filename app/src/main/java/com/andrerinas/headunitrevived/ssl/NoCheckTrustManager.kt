package com.andrerinas.headunitrevived.ssl

import com.andrerinas.headunitrevived.utils.AppLog
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class NoCheckTrustManager: X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
    }

    // [OBSERVE-ONLY, does not enforce anything] This app has no way to verify the connected
    // phone's certificate — closing that gap with certificate pinning
    // requires first confirming the phone's cert is actually stable across reconnects, reboots,
    // and Android Auto app updates, otherwise pinning would break every reconnect instead of
    // adding security. Nothing else in this codebase can answer that; it can only be observed
    // from real usage. Log the presented certificate's identity on every handshake so that, after
    // some real driving/usage, the logs can be compared across sessions to establish whether it's
    // stable before any enforcement is built on top of this.
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        logPeerCertificate(chain)
    }

    private fun logPeerCertificate(chain: Array<out X509Certificate>?) {
        try {
            val leaf = chain?.firstOrNull() ?: return
            val spkiSha256 = MessageDigest.getInstance("SHA-256")
                .digest(leaf.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }
            AppLog.i(
                "SSL: peer certificate observed - spki_sha256=$spkiSha256 " +
                    "subject=${leaf.subjectDN} issuer=${leaf.issuerDN} serial=${leaf.serialNumber} " +
                    "validFrom=${leaf.notBefore} validTo=${leaf.notAfter}"
            )
        } catch (e: Exception) {
            AppLog.w("SSL: failed to log peer certificate: ${e.message}")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        // [FIX] X509TrustManager's contract requires a non-null (possibly empty) array; null
        // is a latent NPE for any provider/caller that enumerates accepted issuers.
        return emptyArray()
    }
}