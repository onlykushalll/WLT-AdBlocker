package com.wlt.adblocker.vpn

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Manages the local MITM CA certificate's lifecycle: generating/loading it
 * (via [GoSecurityBridge], not directly -- see that file for why),
 * persisting the PEM, exporting it for the user to install, a best-effort
 * check for whether it's currently trusted, and regeneration.
 *
 * Context worth restating plainly: this CA, once installed, lets THIS
 * DEVICE decrypt and re-encrypt its OWN HTTPS traffic locally, entirely
 * on-device, for ad/tracker filtering -- the same mechanism AdGuard's
 * Android app and Blokada's paid tier use. The private key generated
 * alongside this CA never leaves the device and is never transmitted
 * anywhere by this code. That said, treat this file and its private key
 * storage with real care: anyone who extracted that private key could
 * forge certificates trusted by this specific device, for as long as the
 * CA stays installed.
 */
class CaCertHelper(
    private val context: Context,
    private val bridge: GoSecurityBridge = GoSecurityBridgeImpl(),
) {
    companion object {
        private const val TAG = "CaCertHelper"
        private const val CA_FILENAME = "wlt-adblocker-ca.pem"
        private const val CA_DISPLAY_NAME = "WLT-Adblocker Local CA"
    }

    private val caFile: File get() = File(context.filesDir, CA_FILENAME)

    /**
     * Returns the current CA's PEM text, generating and persisting one on
     * first call. Safe to call repeatedly -- returns the same CA every
     * time until [regenerate] is called explicitly.
     */
    fun getOrCreateCaPem(): String {
        if (caFile.exists()) {
            return try {
                caFile.readText()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read cached CA, regenerating", e)
                generateAndPersist()
            }
        }
        return generateAndPersist()
    }

    private fun generateAndPersist(): String {
        val pem = bridge.getOrCreateCaPem()
        caFile.writeText(pem)
        return pem
    }

    /**
     * Regenerates the CA from scratch. This invalidates the OLD CA's
     * trust (if installed) and every certificate previously signed by
     * it -- the caller is responsible for warning the user clearly before
     * calling this, since it means re-installing the new CA and briefly
     * losing HTTPS filtering until they do. Not something to call
     * casually or automatically.
     */
    fun regenerate(): String {
        caFile.delete()
        return generateAndPersist()
    }

    /** Parses the stored PEM into a real X509Certificate object, for
     * fingerprint display or the trust-check below. Null if the PEM is
     * somehow malformed -- should not happen in practice, but this is
     * security-adjacent code, so fail closed rather than throw into a UI
     * layer that might not expect it. */
    fun getCaCertificate(): X509Certificate? {
        return try {
            val pem = getOrCreateCaPem()
            val der = pemToDer(pem)
            CertificateFactory.getInstance("X.509")
                .generateCertificate(der.inputStream()) as X509Certificate
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse stored CA certificate", e)
            null
        }
    }

    private fun pemToDer(pem: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        return android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
    }

    /**
     * A human-readable fingerprint (SHA-256 of the DER encoding, hex,
     * colon-separated) for the user to visually cross-check against
     * whatever Android's cert installer UI shows during installation --
     * a real, if manual, way to confirm they're installing the CA this
     * app actually generated, not a different one.
     */
    fun getSha256Fingerprint(): String? {
        val cert = getCaCertificate() ?: return null
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compute CA fingerprint", e)
            null
        }
    }

    /**
     * Builds the system Intent that prompts the user to install this CA
     * certificate, via the long-standing KeyChain API rather than asking
     * the user to navigate Settings manually. Returns null if the CA
     * can't currently be read.
     *
     * Worth flagging plainly rather than glossing over: I have not been
     * able to verify on a real Android 14+ device in this session whether
     * this flow's behavior has changed recently -- KeyChain.createInstallIntent
     * has been stable for a very long time historically, but "historically
     * stable" isn't the same as "verified against the current OS build,"
     * and cert-installation flows are exactly the kind of thing OEMs
     * sometimes customize. Test this on the actual target device before
     * relying on it.
     */
    fun buildCaInstallIntent(): Intent? {
        val der = try {
            getCaCertificate()?.encoded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode CA for install intent", e)
            null
        } ?: return null

        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, der)
            putExtra(KeyChain.EXTRA_NAME, CA_DISPLAY_NAME)
        }
    }

    /**
     * Best-effort check for whether this CA is currently among the
     * system's trusted issuers. Implemented by asking the platform's
     * default X509TrustManager for its accepted-issuers list and looking
     * for a matching public key -- this is a real, workable technique,
     * but exact behavior has some history of varying across OEM Android
     * builds and versions, so treat a `false` result here as "probably
     * not installed, but confirm with the user" rather than an absolute.
     * Never treat a `true` result as a substitute for the user's own
     * confirmation before you start actually intercepting traffic.
     */
    fun isCaTrustedBySystem(): Boolean {
        val ourCert = getCaCertificate() ?: return false
        return try {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            val x509tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
                ?: return false
            x509tm.acceptedIssuers.any { it.publicKey == ourCert.publicKey }
        } catch (e: Exception) {
            Log.w(TAG, "Trust check failed, assuming not trusted", e)
            false
        }
    }

    /**
     * Exports the CA PEM to a location the user can access outside this
     * app (e.g. to install manually, or share to another device), via
     * FileProvider rather than writing directly to public storage --
     * avoids needing broad storage permissions on modern Android.
     * Requires a `<provider>` entry for FileProvider in the manifest with
     * an authority of "${applicationId}.fileprovider" and a matching
     * file-paths XML resource -- add both if they aren't already present
     * (they're a few lines of manifest/XML boilerplate, not shown here
     * since I don't have your current manifest in front of me to avoid
     * duplicating or conflicting with an existing provider entry).
     */
    fun buildShareIntent(): Intent? {
        val pem = try {
            getOrCreateCaPem()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CA PEM for sharing", e)
            return null
        }

        val shareDir = File(context.cacheDir, "shared_ca")
        shareDir.mkdirs()
        val shareFile = File(shareDir, CA_FILENAME)
        return try {
            shareFile.writeText(pem)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
            Intent(Intent.ACTION_SEND).apply {
                type = "application/x-x509-ca-cert"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build share intent for CA", e)
            null
        }
    }

    /** Plain-language instructions for the settings screen. Kept as data
     * here rather than hardcoded into a Composable, so the wording only
     * needs updating in one place if Android's menu path changes again --
     * it has moved before across OS versions and OEM skins, and likely
     * will again. */
    fun installInstructions(): List<String> = listOf(
        "Tap \"Install CA certificate\" below and confirm through Android's own installer.",
        "If that doesn't open anything, go to Settings > Security (or Security & privacy) " +
            "> More security settings > Encryption & credentials > Install a certificate > " +
            "CA certificate, and select the exported file instead.",
        "Android will show a fingerprint during install -- it should match: " +
            "${getSha256Fingerprint() ?: "(unavailable)"}",
        "This CA only decrypts traffic for HTTPS filtering ON THIS DEVICE. Nothing is sent " +
            "anywhere. You can remove it later from the same Settings screen, or use " +
            "\"Regenerate CA\" here if you ever suspect the private key was exposed.",
    )
}
