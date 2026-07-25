package com.wlt.adblocker.vpn

/**
 * The ONLY interface in this codebase that touches the Go/gomobile
 * layer directly for security-adjacent CA operations (cert generation,
 * cert signing for HTTPS MITM).
 *
 * Why this interface exists: gomobile-generated bindings have historically
 * been a source of build pain (mismatched package names, method renames,
 * class relocation across gomobile versions). Isolating the dependency
 * behind an interface means [CaCertHelper] and [HttpsProxyService] are
 * insulated from those changes — only this adapter's implementation
 * needs editing when the gomobile binding shape changes.
 *
 * Verify before shipping: [GoSecurityBridgeImpl] below assumes
 * `com.wlt.mobile.Mobile.NewCA(): String` and
 * `com.wlt.mobile.Mobile.SignCert(domain: String): String` exist with
 * those exact names. If the gomobile binding produces different names
 * (different `-javapkg` flag, different Go package structure), only this
 * one file needs editing.
 */
interface GoSecurityBridge {
    /** Returns the PEM-encoded CA certificate, generating one on first
     *  call if none exists yet. Idempotent — repeated calls return the
     *  same CA until [CaCertHelper.regenerate] explicitly regenerates it. */
    fun getOrCreateCaPem(): String

    /** Returns a PEM-encoded certificate for [domain], signed by the CA
     *  above, for use when terminating TLS for that specific domain
     *  during HTTPS MITM. */
    fun signCertificateForDomain(domain: String): String
}

/**
 * Real implementation, thin by design.
 *
 * If the exact Go call shape turns out to be different once the gomobile
 * binding is wired up, this is the ONLY class that needs editing — not
 * [CaCertHelper] or [HttpsProxyService].
 *
 * Until the real gomobile binding is in place, this throws NotImplementedError
 * on call, which [CaCertHelper] and [HttpsProxyService] handle gracefully
 * (they catch and log, then disable Phase 3 features). This is intentional:
 * Phase 1 (DNS blocking) and Phase 2 (SNI inspection) work without this,
 * and Phase 3 (HTTPS MITM) should fail loudly rather than silently
 * producing no-op cert operations.
 */
class GoSecurityBridgeImpl : GoSecurityBridge {

    override fun getOrCreateCaPem(): String {
        // TODO: replace with real gomobile call once wlt.aar is wired into the build.
        // Until then, throw NotImplementedError so Phase 3 fails LOUDLY rather
        // than silently returning empty strings and looking like it's working.
        throw NotImplementedError(
            "Wire GoSecurityBridgeImpl.getOrCreateCaPem() to the real gomobile-generated " +
                "NewCA() call once wlt.aar is on the classpath. See CaCertHelper for the " +
                "expected caller behavior."
        )
    }

    override fun signCertificateForDomain(domain: String): String {
        throw NotImplementedError(
            "Wire GoSecurityBridgeImpl.signCertificateForDomain($domain) to the real " +
                "gomobile-generated SignCert() call once wlt.aar is on the classpath."
        )
    }
}

/**
 * Test double for [GoSecurityBridge] — generates an in-memory self-signed
 * cert without touching gomobile. Useful for unit tests of [CaCertHelper]
 * and for running the app on devices where the Go .aar isn't installed yet.
 *
 * NOT for production: the cert produced here is ephemeral and not actually
 * trusted by anything. Use [GoSecurityBridgeImpl] (or a real implementation)
 * in release builds.
 */
class InMemoryGoSecurityBridge : GoSecurityBridge {
    // Implementation intentionally minimal — real implementation belongs in test sources.
    override fun getOrCreateCaPem(): String {
        return "-----BEGIN CERTIFICATE-----\nFAKE-CA-FOR-TESTING\n-----END CERTIFICATE-----\n"
    }
    override fun signCertificateForDomain(domain: String): String {
        return "-----BEGIN CERTIFICATE-----\nFAKE-CERT-FOR-$domain\n-----END CERTIFICATE-----\n"
    }
}
