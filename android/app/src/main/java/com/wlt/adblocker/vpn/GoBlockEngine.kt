package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log

/**
 * Kotlin wrapper around the Go mobile.Engine (via the gomobile binding).
 *
 * Why a wrapper: the gomobile binding exposes Go's `Engine` type as a
 * Java/Kotlin class with method names that don't always feel idiomatic
 * in Kotlin (e.g., `engine.shouldBlock(domain)` works, but the binding
 * also exposes lower-level methods we don't want to leak into the rest
 * of the codebase). Wrapping it here gives us:
 *   1. A clean Kotlin API surface
 *   2. A single place to add try/catch around gomobile calls (the JNI
 *      bridge throws unchecked exceptions on Go panics, which we want
 *      to convert to fallback behavior, not crashes)
 *   3. A clean swap point: if the Go binding fails to load (missing
 *      .aar, architecture mismatch, etc.), we fall back to
 *      [KotlinBlockEngine] transparently
 *
 * Failure mode: if the gomobile binding is unavailable, [init] fails
 * silently and [shouldBlock] defers to [fallback]. The caller (VPN
 * service) is unaware of which engine is actually running — both
 * implement the same contract.
 */
class GoBlockEngine(
    private val context: Context,
    private val fallback: KotlinBlockEngine,
) {

    companion object {
        private const val TAG = "GoBlockEngine"
    }

    // The real gomobile Engine instance, or null if loading failed.
    // Typed as Any? because we don't want a hard compile-time dependency
    // on the gomobile binding classes — they may not be on the classpath
    // in builds that don't include wlt.aar.
    @Volatile
    private var goEngine: Any? = null
    @Volatile
    private var goEngineLoaded: Boolean = false

    init {
        loadGoEngine()
    }

    /** Attempts to load the gomobile Engine. Sets [goEngine] and
     *  [goEngineLoaded] on success, leaves them null/false on failure. */
    private fun loadGoEngine() {
        try {
            // Reflection-based load: avoids a hard compile-time dependency on
            // com.wlt.mobile.Mobile. If the .aar isn't on the classpath, the
            // CRITICAL FIX: gomobile generates Java classes based on the Go
            // package name. Our Go package is "adblocker" (in mobile.go), so
            // gomobile generates class "adblocker.Mobile". Previously this was
            // "com.wlt.mobile.Mobile" which never matched — the Go engine was
            // never loaded, even when the .aar was present.
            val mobileClass = Class.forName("adblocker.Mobile")
            val newEngineMethod = mobileClass.getMethod("newEngine")
            goEngine = newEngineMethod.invoke(null)
            goEngineLoaded = (goEngine != null)
            Log.i(TAG, "Go mobile.Engine loaded successfully (class=${goEngine?.javaClass?.name})")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "gomobile binding not on classpath — using KotlinBlockEngine fallback")
            goEngine = null
            goEngineLoaded = false
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Mobile.newEngine() not found — using KotlinBlockEngine fallback", e)
            goEngine = null
            goEngineLoaded = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Go mobile.Engine — using KotlinBlockEngine fallback", e)
            goEngine = null
            goEngineLoaded = false
        }
    }

    /** Returns true if the Go engine is loaded and active. */
    fun isGoEngineActive(): Boolean = goEngineLoaded && goEngine != null

    /**
     * Returns true if [domain] should be blocked. Delegates to the Go
     * engine if loaded; falls back to [KotlinBlockEngine.shouldBlock]
     * otherwise.
     *
     * Per Task 29's simplification: this method takes a domain string
     * directly, NOT a raw DNS packet. The caller ([WltVpnService]) is
     * responsible for parsing the DNS packet first via [DnsPacketParser.extractQueryName].
     */
    fun shouldBlock(domain: String): Boolean {
        if (!goEngineLoaded || goEngine == null) {
            return fallback.shouldBlock(domain)
        }
        return try {
            // Reflection call: invoke goEngine.shouldBlock(domain) on the
            // gomobile-generated Engine class. The gomobile binding maps
            // Go's `func (e *Engine) ShouldBlock(domain string) bool` to
            // a Java method `boolean shouldBlock(String domain)`.
            val shouldBlockMethod = goEngine!!.javaClass.getMethod("shouldBlock", String::class.java)
            val result = shouldBlockMethod.invoke(goEngine, domain) as? Boolean
            result ?: fallback.shouldBlock(domain)
        } catch (e: Exception) {
            Log.w(TAG, "Go engine shouldBlock() threw — falling back to Kotlin engine", e)
            // Defensive: if the Go engine panics (rare but possible via JNI),
            // we don't want to take down the VPN loop. Fall back gracefully.
            fallback.shouldBlock(domain)
        }
    }

    /** Delegates SDK detection to the fallback engine. The Go engine
     *  doesn't expose a separate SDK-detection API (it returns true/false
     *  for the whole block decision); the Kotlin fallback's [detectSdk]
     *  is the canonical implementation. */
    fun detectSdk(domain: String): String? = fallback.detectSdk(domain)

    /** Returns the last block reason from whichever engine produced the
     *  most recent block decision. For the Go engine, the reason is
     *  simpler (we don't get per-layer trace info back across the JNI
     *  boundary cheaply); for the fallback, it's the full reason string. */
    fun getLastBlockReason(): String {
        return if (goEngineLoaded) "Go engine block" else fallback.getLastBlockReason()
    }

    fun getLastBlockSdk(): String? {
        return if (goEngineLoaded) null else fallback.getLastBlockSdk()
    }

    /** Loads bundled blocklists. For the Go engine, this is a no-op (the
     *  Go engine loads its own blocklists at init time); for the fallback,
     *  it calls [KotlinBlockEngine.loadBundledBlocklists]. Safe to call
     *  in both cases — the Go engine's loaded blocklists are independent. */
    fun loadBundledBlocklists() {
        // Always also load into the fallback, in case the Go engine fails
        // at runtime and we need to fall back mid-session.
        fallback.loadBundledBlocklists()
    }
}
