package com.wlt.adblocker.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * ReVanced Integration — detects if ReVanced YouTube is installed
 * and provides integration guidance.
 *
 * ReVanced patches the YouTube APK to strip ad code at the bytecode level.
 * It's the ONLY way to block YouTube app SSAI ads (same-domain stream stitching).
 *
 * WLT's role:
 * 1. Detect if ReVanced is installed
 * 2. If yes: WLT DNS blocks YouTube trackers, ReVanced handles in-app ads
 * 3. If no: WLT blocks CSAI ads at DNS level, guides user to ReVanced for SSAI
 * 4. For YouTube in browser: WLT scriptlets + m3u-prune handle everything
 */
object ReVancedIntegration {

    private const val TAG = "ReVancedIntegration"

    // Known ReVanced package names
    private val REVANCED_PACKAGES = listOf(
        "app.revanced.android.youtube",
        "app.rvx.android.youtube",
        "com.vanced.android.youtube",
        "com.mgoogle.android.youtube"
    )

    // NewPipe / alternative clients
    private val ALT_CLIENTS = listOf(
        "org.schabi.newpipe",
        "org.polymorphicshade.newpipe",
        "com.github.libretube"
    )

    /**
     * Check if ReVanced YouTube is installed.
     */
    fun isReVancedInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            REVANCED_PACKAGES.any { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if any alternative YouTube client is installed.
     */
    fun isAlternativeClientInstalled(context: Context): Boolean {
        return try {
            val pm = context.packageManager
            ALT_CLIENTS.any { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the installed ReVanced/alternative client package name, or null.
     */
    fun getInstalledClient(context: Context): String? {
        val pm = context.packageManager
        for (pkg in REVANCED_PACKAGES + ALT_CLIENTS) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (e: PackageManager.NameNotFoundException) {
                // continue
            }
        }
        return null
    }

    /**
     * Get YouTube ad-blocking strategy based on what's installed.
     */
    fun getStrategy(context: Context): YouTubeStrategy {
        return when {
            isReVancedInstalled(context) -> YouTubeStrategy.REVANCED
            isAlternativeClientInstalled(context) -> YouTubeStrategy.ALTERNATIVE_CLIENT
            else -> YouTubeStrategy.DNS_AND_SCRIPTLETS
        }
    }

    /**
     * Get user-facing instructions for YouTube ad blocking.
     */
    fun getInstructions(context: Context): List<String> {
        val strategy = getStrategy(context)
        return when (strategy) {
            YouTubeStrategy.REVANCED -> listOf(
                "ReVanced YouTube detected!",
                "WLT is blocking YouTube trackers at DNS level.",
                "ReVanced handles in-app video ads at the bytecode level.",
                "You have full YouTube ad blocking coverage."
            )
            YouTubeStrategy.ALTERNATIVE_CLIENT -> listOf(
                "Alternative YouTube client detected (NewPipe/LibreTube).",
                "This client doesn't load ads at all — no blocking needed.",
                "WLT is still blocking YouTube trackers at DNS level."
            )
            YouTubeStrategy.DNS_AND_SCRIPTLETS -> listOf(
                "WLT blocks YouTube ad domains at DNS level (doubleclick.net, googlesyndication.com).",
                "For YouTube in browser: WLT scriptlets strip ad placements + m3u-prune removes HLS ad segments.",
                "For YouTube app SSAI ads (stitched into video stream):",
                "  Option 1: Install ReVanced (revanced.app) — patches the YouTube APK to strip ad code.",
                "  Option 2: Install NewPipe (f-droid.org/packages/org.schabi.newpipe/) — ad-free alternative client.",
                "  Option 3: Use YouTube in browser with WLT HTTPS filtering enabled (Phase 3).",
                "WLT + ReVanced together = maximum YouTube ad blocking."
            )
        }
    }
}

enum class YouTubeStrategy {
    REVANCED,               // ReVanced installed — full coverage
    ALTERNATIVE_CLIENT,     // NewPipe/LibreTube installed — no ads
    DNS_AND_SCRIPTLETS      // WLT only — DNS + scriptlets, guide to ReVanced
}
