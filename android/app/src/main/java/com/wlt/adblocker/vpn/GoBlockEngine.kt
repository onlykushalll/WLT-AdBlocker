package com.wlt.adblocker.vpn

import android.content.Context
import android.util.Log

/**
 * Kotlin wrapper around the Go `adblocker.Engine` (via the gomobile binding).
 *
 * CRITICAL (Task 55 fix): the previous code used `Class.forName("adblocker.Mobile")`
 * which NEVER matched. gomobile bind generates the main class from the Go package
 * name — our package is `adblocker` (see mobile.go line 9), so the generated main
 * class is `adblocker.Adblocker` (capitalized package name), NOT `adblocker.Mobile`.
 *
 * Verified by inspecting wlt.aar's classes.jar:
 *   adblocker.Adblocker  ← main class (static factory methods: newEngine, newCA, newWGTunnel)
 *   adblocker.Engine     ← the Engine type (instance methods: shouldBlock, statsJSON, etc.)
 *   adblocker.CheckResult, adblocker.CA, adblocker.ParsedRule, adblocker.WGTunnel
 *
 * This means the Go engine has NEVER loaded until this fix. Every "block" decision
 * was from the KotlinBlockEngine fallback. The Go engine's Smart Cascade, regex,
 * domain age, JA4+ — ALL unused. This is the #1 reason "nothing works".
 *
 * This wrapper provides:
 *   1. Reflection-based load (no hard compile-time dependency on the .aar)
 *   2. Graceful fallback to KotlinBlockEngine if Go engine fails
 *   3. Diagnostic status reporting (so the DiagnosticScreen can show engine state)
 */
class GoBlockEngine(
    private val context: Context,
    private val fallback: KotlinBlockEngine,
) {

    companion object {
        private const val TAG = "GoBlockEngine"

        /** The gomobile-generated main class name (verified via .aar inspection).
         *  gomobile capitalizes the Go package name: `adblocker` → `adblocker.Adblocker`. */
        private const val GO_MAIN_CLASS = "adblocker.Adblocker"

        /** The gomobile-generated Engine class name. */
        private const val GO_ENGINE_CLASS = "adblocker.Engine"
    }

    /** The real gomobile Engine instance, or null if loading failed.
     *  Typed as Any? to avoid a hard compile-time dependency on the .aar. */
    @Volatile
    private var goEngine: Any? = null

    /** True if the Go engine loaded successfully. */
    @Volatile
    private var goEngineLoaded: Boolean = false

    /** The error that occurred during loading, or null if none.
     *  Exposed for the DiagnosticScreen. */
    @Volatile
    private var loadError: String? = null

    /** The class name that was found, or null.
     *  Exposed for the DiagnosticScreen. */
    @Volatile
    private var loadedClassName: String? = null

    init {
        loadGoEngine()
    }

    /** Attempts to load the gomobile Engine via reflection.
     *  Sets [goEngine] and [goEngineLoaded] on success; records [loadError] on failure. */
    private fun loadGoEngine() {
        try {
            // Step 1: Load the gomobile main class (adblocker.Adblocker).
            val mainClass = Class.forName(GO_MAIN_CLASS)
            Log.i(TAG, "Found gomobile main class: $GO_MAIN_CLASS")

            // Step 2: Call the static factory method newEngine() to get an Engine instance.
            // gomobile generates: public static Engine newEngine() throws Exception
            val newEngineMethod = mainClass.getMethod("newEngine")
            goEngine = newEngineMethod.invoke(null)
            loadedClassName = goEngine?.javaClass?.name

            if (goEngine != null) {
                goEngineLoaded = true
                Log.i(TAG, "Go Engine loaded successfully (class=$loadedClassName)")

                // Step 3: Log engine stats for diagnostic visibility.
                try {
                    val statsMethod = goEngine!!.javaClass.getMethod("statsJSON")
                    val stats = statsMethod.invoke(goEngine) as? String
                    Log.i(TAG, "Go Engine stats: $stats")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get Go engine stats (non-fatal)", e)
                }
            } else {
                loadError = "newEngine() returned null"
                Log.w(TAG, "Go newEngine() returned null — using KotlinBlockEngine fallback")
            }
        } catch (e: ClassNotFoundException) {
            loadError = "Class not found: $GO_MAIN_CLASS (is wlt.aar on the classpath?)"
            Log.w(TAG, loadError ?: "unknown error")
            Log.w(TAG, "→ Using KotlinBlockEngine fallback. Go engine features (Smart Cascade, " +
                "regex, domain age, JA4+) are NOT available.")
            goEngine = null
            goEngineLoaded = false
        } catch (e: NoSuchMethodException) {
            loadError = "Method not found on $GO_MAIN_CLASS: ${e.message}"
            Log.w(TAG, loadError, e)
            goEngine = null
            goEngineLoaded = false
        } catch (e: Exception) {
            // gomobile's newEngine() can throw if the native lib (libgojni.so)
            // fails to load — wrong ABI, missing .so, or Go init panic.
            loadError = "${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, "Failed to load Go Engine — using KotlinBlockEngine fallback", e)
            goEngine = null
            goEngineLoaded = false
        }

        // CRITICAL: report engine status to DiagnosticState so the DiagnosticScreen
        // can show whether the Go engine loaded. This was the #1 gap — without it,
        // the user had no way to know the Go engine was silently failing.
        DiagnosticState.setEngineStatus(getDiagnosticStatus())
    }

    /** Returns true if the Go engine is loaded and active. */
    fun isGoEngineActive(): Boolean = goEngineLoaded && goEngine != null

    /** Returns a diagnostic status object for the DiagnosticScreen.
     *  Returns a DiagnosticState.EngineStatus so it can be passed directly
     *  to DiagnosticState.setEngineStatus(). */
    fun getDiagnosticStatus(): DiagnosticState.EngineStatus {
        return DiagnosticState.EngineStatus(
            loaded = goEngineLoaded,
            className = loadedClassName,
            error = loadError,
            ruleCount = fallback.getRuleCount(),  // BlocklistManager count (884 rules)
            engineVersion = if (goEngineLoaded) "Go (gomobile)" else "Kotlin (fallback)",
        )
    }

    /** Returns the number of rules loaded. Uses the Kotlin BlocklistManager count
     *  (884 rules) since the Go engine doesn't expose a direct rule count method. */
    private fun getRuleCount(): Int = fallback.getRuleCount()

    /** Returns the full stats JSON from the Go engine, or null if unavailable.
     *  Used by the DiagnosticScreen. */
    fun getStatsJSON(): String? {
        if (!goEngineLoaded || goEngine == null) return null
        return try {
            val statsMethod = goEngine!!.javaClass.getMethod("statsJSON")
            statsMethod.invoke(goEngine) as? String
        } catch (e: Exception) {
            Log.w(TAG, "getStatsJSON failed", e)
            null
        }
    }

    /** Returns the Go engine's forensics report, or null if unavailable. */
    fun getForensicsRecent(n: Int = 10): String? {
        if (!goEngineLoaded || goEngine == null) return null
        return try {
            val method = goEngine!!.javaClass.getMethod("forensicsRecent", Int::class.javaPrimitiveType)
            method.invoke(goEngine, n) as? String
        } catch (e: Exception) {
            null
        }
    }

    /** Returns the Go engine's recommended fixes, or null if unavailable. */
    fun getRecommendFixes(): String? {
        if (!goEngineLoaded || goEngine == null) return null
        return try {
            val method = goEngine!!.javaClass.getMethod("recommendFixes")
            method.invoke(goEngine) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns true if [domain] should be blocked. Delegates to the Go
     * engine if loaded; falls back to [KotlinBlockEngine.shouldBlock] otherwise.
     */
    fun shouldBlock(domain: String): Boolean {
        if (!goEngineLoaded || goEngine == null) {
            return fallback.shouldBlock(domain)
        }
        return try {
            // gomobile maps Go's `func (e *Engine) ShouldBlock(domain string) bool`
            // to Java `boolean shouldBlock(String domain)`.
            val shouldBlockMethod = goEngine!!.javaClass.getMethod("shouldBlock", String::class.java)
            val result = shouldBlockMethod.invoke(goEngine, domain) as? Boolean
            result ?: fallback.shouldBlock(domain)
        } catch (e: Exception) {
            Log.w(TAG, "Go engine shouldBlock() threw — falling back to Kotlin engine", e)
            fallback.shouldBlock(domain)
        }
    }

    /** Detects which ad SDK a domain belongs to. Delegates to Go engine if loaded
     *  (it has the full 12-SDK database), otherwise falls back to Kotlin. */
    fun detectSdk(domain: String): String? {
        if (!goEngineLoaded || goEngine == null) {
            return fallback.detectSdk(domain)
        }
        return try {
            val method = goEngine!!.javaClass.getMethod("gameSDKName", String::class.java)
            method.invoke(goEngine, domain) as? String
        } catch (e: Exception) {
            fallback.detectSdk(domain)
        }
    }

    /** Returns the last block reason. */
    fun getLastBlockReason(): String {
        return if (goEngineLoaded) "Go engine block" else fallback.getLastBlockReason()
    }

    fun getLastBlockSdk(): String? {
        return if (goEngineLoaded) null else fallback.getLastBlockSdk()
    }

    /** Loads bundled blocklists into both engines.
     *  The Go engine loads its own blocklists via NewEngine() → LoadDefaultBlocklists().
     *  The fallback loads from assets. We call both to keep them in sync. */
    fun loadBundledBlocklists() {
        // Always load into the fallback (in case Go engine fails at runtime).
        fallback.loadBundledBlocklists()

        // If the Go engine is loaded, also load blocklists into it.
        // gomobile maps Go's `func (e *Engine) LoadDefaultBlocklists(assetsDir string) error`
        // to Java `void loadDefaultBlocklists(String assetsDir) throws Exception`.
        if (goEngineLoaded && goEngine != null) {
            try {
                val method = goEngine!!.javaClass.getMethod("loadDefaultBlocklists", String::class.java)
                // The Go engine expects a filesystem path to the blocklists directory.
                // We copy assets to filesDir/blocklists/ and pass that path.
                val blocklistDir = copyBlocklistsToInternalStorage()
                method.invoke(goEngine, blocklistDir)
                Log.i(TAG, "Go engine blocklists loaded from $blocklistDir")
                // Update loadError to null to indicate successful load
                loadError = null
            } catch (e: Exception) {
                // CRITICAL (Task 60 fix): the Go engine's loadDefaultBlocklists now
                // returns an error (previously silently swallowed). If it throws,
                // the Go engine has 0 rules — record this so the DiagnosticScreen
                // can show it.
                loadError = "Go blocklist load failed: ${e.message}"
                Log.e(TAG, "Go engine loadDefaultBlocklists() FAILED — Go engine has 0 rules! " +
                    "Kotlin fallback still has ${fallback.getRuleCount()} rules.", e)
            }
        }
    }

    /** Copies the bundled blocklists from assets/blocklists/ to internal storage
     *  so the Go engine can read them via a filesystem path. */
    private fun copyBlocklistsToInternalStorage(): String {
        val targetDir = java.io.File(context.filesDir, "blocklists")
        if (!targetDir.exists()) targetDir.mkdirs()
        try {
            val assetFiles = context.assets.list("blocklists") ?: return targetDir.absolutePath
            for (filename in assetFiles) {
                val targetFile = java.io.File(targetDir, filename)
                if (!targetFile.exists()) {
                    context.assets.open("blocklists/$filename").use { input ->
                        targetFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy blocklists to internal storage", e)
        }
        return targetDir.absolutePath
    }

    /** Self-test: calls shouldBlock() on known blocked and allowed domains.
     *  Verifies the Go engine's blocking logic actually works (not just loads).
     *  Called after blocklists are loaded. Results logged to logcat.
     *
     *  IMPORTANT: the trie does SUFFIX matching. If "stats.g.doubleclick.net"
     *  is in the blocklist, then "foo.stats.g.doubleclick.net" matches (subdomain)
     *  but "doubleclick.net" does NOT (it's a parent, not a subdomain).
     *  The test domains below are chosen to match exactly what's in the lists. */
    fun runSelfTest() {
        if (!goEngineLoaded || goEngine == null) {
            Log.w(TAG, "Self-test skipped — Go engine not loaded")
            return
        }
        val testCases = listOf(
            // Exact domains from blocklists (verified via grep)
            "stats.g.doubleclick.net" to true,   // in wlt-trackers.txt
            "pubads.g.doubleclick.net" to true,   // in wlt-youtube-ads.txt
            "googleads.g.doubleclick.net" to true, // in wlt-youtube-ads.txt
            "doubleclick.com" to true,             // in wlt-trackers.txt
            // Subdomains of blocked domains (should match via suffix)
            "foo.stats.g.doubleclick.net" to true, // subdomain of stats.g.doubleclick.net
            // Allowed domains (not in any blocklist)
            "example.com" to false,
            "google.com" to false,
            "wikipedia.org" to false,
        )
        var passed = 0
        var failed = 0
        for ((domain, expectedBlock) in testCases) {
            val actualBlock = shouldBlock(domain)
            val result = if (actualBlock == expectedBlock) "PASS" else "FAIL"
            if (actualBlock == expectedBlock) passed++ else failed++
            Log.i(TAG, "Self-test [$result]: shouldBlock(\"$domain\") = $actualBlock (expected $expectedBlock)")
        }
        // CRITICAL FIX (B-KOTLIN-VPN-DEBUG): "$testCases.size" does NOT interpolate
        // the size — Kotlin parses it as "${testCases}" followed by the literal
        // text ".size", so the log showed the list's toString() + ".size".
        // Use ${...} to force expression evaluation.
        Log.i(TAG, "Self-test complete: $passed/${testCases.size} passed ($failed failed)")
    }
}
