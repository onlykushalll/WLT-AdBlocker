package com.wlt.adblocker.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * ReVanced/NewPipe detection and YouTube-strategy coordination.
 *
 * Why this exists: WLT can NOT block YouTube app ads at the VPN layer.
 * YouTube uses SSAI (Server-Side Ad Injection) — ads are stitched into
 * the same video stream as content, served from the same CDN domain.
 * No DNS/SNI/HTTPS distinction exists to filter on. Pretending otherwise
 * would be dishonest (Task 20's gap analysis called this out explicitly).
 *
 * What we CAN do:
 *   - Detect whether the user has ReVanced / NewPipe / xManager installed
 *     (apps that DO patch YouTube/Spotify at the APK level to remove ads)
 *   - If detected: route YouTube traffic through them automatically (WLT
 *     DNS still blocks YouTube tracker domains, but ad-blocking is left
 *     to the patched app)
 *   - If NOT detected: surface a "Tap to install ReVanced" deep-link
 *     in the UI, with a one-paragraph explanation of WHY VPN-blocking
 *     can't do this
 *
 * This file is detection only — installation flows belong in the UI.
 */
class ReVancedIntegration(private val context: Context) {

    companion object {
        private const val TAG = "ReVanced"

        // Package names of alternative YouTube/Spotify clients we know about.
        val YOUTUBE_ALT_PACKAGES = listOf(
            "app.revanced.android.youtube",        // ReVanced
            "app.revanced.android.youtube.music",  // ReVanced Music
            "org.schabi.newpipe",                  // NewPipe
            "org.polymorphicshade.newpipe",        // NewPipe fork ( Tubular )
            "free.rm.skytube.oss",                 // SkyTube
            "com.github.libretube",                // LibreTube
        )

        val SPOTIFY_ALT_PACKAGES = listOf(
            "xmanager.spotify",           // xManager
            "xmanagermod.spotify",        // xManager fork
            "com.spotify.music",          // stock (for comparison)
        )

        val YOUTUBE_STOCK_PACKAGE = "com.google.android.youtube"
        val YOUTUBE_MUSIC_STOCK = "com.google.android.apps.youtube.music"
    }

    data class AppStatus(
        val packageName: String,
        val displayName: String,
        val installed: Boolean,
        val versionName: String? = null,
    )

    /** Returns the install status of every known YouTube alternative
     *  (ReVanced, NewPipe, SkyTube, LibreTube, etc.). Used by the UI to
     *  show "you have ReVanced installed" or "tap to install ReVanced". */
    fun detectYouTubeAlternatives(): List<AppStatus> {
        return YOUTUBE_ALT_PACKAGES.map { pkg ->
            appStatusFor(pkg, defaultDisplayName(pkg))
        }
    }

    /** Returns install status for the stock YouTube app and its alternatives. */
    fun detectAllYouTubeClients(): List<AppStatus> {
        val result = ArrayList<AppStatus>()
        result.add(appStatusFor(YOUTUBE_STOCK_PACKAGE, "YouTube (stock)"))
        result.add(appStatusFor(YOUTUBE_MUSIC_STOCK, "YouTube Music (stock)"))
        result.addAll(detectYouTubeAlternatives())
        return result
    }

    /** Returns install status for Spotify alternatives (xManager etc.). */
    fun detectSpotifyAlternatives(): List<AppStatus> {
        return SPOTIFY_ALT_PACKAGES.map { pkg ->
            appStatusFor(pkg, defaultDisplayName(pkg))
        }
    }

    /** Returns true if ANY YouTube alternative is installed.
     *  Used by the VPN service to decide whether to surface the
     *  "you should use ReVanced for YouTube ads" notification. */
    fun hasYouTubeAlternative(): Boolean {
        return YOUTUBE_ALT_PACKAGES.any { isPackageInstalled(it) }
    }

    /** Returns true if ANY Spotify alternative is installed. */
    fun hasSpotifyAlternative(): Boolean {
        return SPOTIFY_ALT_PACKAGES.minus("com.spotify.music").any { isPackageInstalled(it) }
    }

    /** Recommended YouTube strategy for the user, based on what's installed. */
    fun youtubeStrategy(): YouTubeStrategy {
        val alts = detectYouTubeAlternatives()
        val installedAlt = alts.firstOrNull { it.installed }
        return when {
            installedAlt != null -> YouTubeStrategy.UseAlternative(
                packageName = installedAlt.packageName,
                displayName = installedAlt.displayName,
            )
            isPackageInstalled(YOUTUBE_STOCK_PACKAGE) -> YouTubeStrategy.SuggestAlternative(
                "YouTube app SSAI ads cannot be blocked at the VPN layer. " +
                    "Install ReVanced or NewPipe for ad-free YouTube.",
            )
            else -> YouTubeStrategy.NoAction
        }
    }

    /** Strategy enum for the UI to render. */
    sealed class YouTubeStrategy {
        /** No YouTube app installed; nothing to do. */
        data object NoAction : YouTubeStrategy()
        /** A YouTube alternative is installed — route through it. */
        data class UseAlternative(val packageName: String, val displayName: String) : YouTubeStrategy()
        /** Stock YouTube is installed, no alternative — suggest installing one. */
        data class SuggestAlternative(val message: String) : YouTubeStrategy()
    }

    // --- Helpers ---

    private fun isPackageInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        Log.w(TAG, "Failed to check if $pkg is installed", e)
        false
    }

    private fun appStatusFor(pkg: String, displayName: String): AppStatus {
        return try {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            AppStatus(
                packageName = pkg,
                displayName = displayName,
                installed = true,
                versionName = info.versionName,
            )
        } catch (e: PackageManager.NameNotFoundException) {
            AppStatus(
                packageName = pkg,
                displayName = displayName,
                installed = false,
            )
        } catch (e: Exception) {
            AppStatus(packageName = pkg, displayName = displayName, installed = false)
        }
    }

    private fun defaultDisplayName(pkg: String): String = when (pkg) {
        "app.revanced.android.youtube" -> "ReVanced"
        "app.revanced.android.youtube.music" -> "ReVanced Music"
        "org.schabi.newpipe" -> "NewPipe"
        "org.polymorphicshade.newpipe" -> "Tubular"
        "free.rm.skytube.oss" -> "SkyTube"
        "com.github.libretube" -> "LibreTube"
        "xmanager.spotify" -> "xManager"
        "xmanagermod.spotify" -> "xManager (mod)"
        "com.spotify.music" -> "Spotify (stock)"
        else -> pkg
    }
}
