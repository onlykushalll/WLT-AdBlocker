package com.wlt.adblocker.data

/**
 * Domain categorization — Phase 9d
 *
 * Tags each blocked domain with a category so the UI can show
 * "Top categories blocked" (AdGuard v2.23 feature).
 *
 * Categories are determined by which blocklist the domain came from.
 */
enum class BlockCategory(val displayName: String, val emoji: String) {
    ADVERTISING("Advertising", "📢"),
    TRACKING("Tracking", "👁"),
    ANALYTICS("Analytics", "📊"),
    SOCIAL("Social", "📱"),
    MALWARE("Malware", "🦠"),
    CRYPTO_MINING("Crypto Mining", "⛏"),
    SMART_TV("Smart TV", "📺"),
    GAME_ADS("Game Ads", "🎮"),
    YOUTUBE_ADS("YouTube Ads", "▶"),
    SPOTIFY_ADS("Spotify Ads", "🎵"),
    CNAME_CLOAK("CNAME Cloaking", "🎭"),
    DOH_BYPASS("DoH Bypass", "🔒"),
    OTHER("Other", "❓");

    companion object {
        /**
         * Maps a blocklist filename to its category.
         * Used by BlocklistManager to tag domains at load time.
         */
        fun fromBlocklist(filename: String): BlockCategory {
            return when {
                filename.contains("game-ads") -> GAME_ADS
                filename.contains("youtube") -> YOUTUBE_ADS
                filename.contains("spotify") -> SPOTIFY_ADS
                filename.contains("social") -> SOCIAL
                filename.contains("crypto") -> CRYPTO_MINING
                filename.contains("smart-tv") -> SMART_TV
                filename.contains("trackers") -> TRACKING
                filename.contains("cname") -> CNAME_CLOAK
                filename.contains("passthrough") -> OTHER // allowlist, no category
                else -> OTHER
            }
        }

        /**
         * Guesses a domain's category from its name (for domains not
         * loaded from a specific blocklist, e.g. custom rules).
         */
        fun fromDomain(domain: String): BlockCategory {
            val d = domain.lowercase()
            return when {
                d.contains("ads") || d.contains("admob") || d.contains("doubleclick") ||
                    d.contains("googlesyndication") || d.contains("adsystem") -> ADVERTISING
                d.contains("track") || d.contains("telemetry") || d.contains("analytics") ||
                    d.contains("metrics") -> ANALYTICS
                d.contains("facebook") || d.contains("instagram") || d.contains("tiktok") ||
                    d.contains("twitter") || d.contains("snapchat") || d.contains("reddit") -> SOCIAL
                d.contains("mine") || d.contains("coinhive") || d.contains("crypt") -> CRYPTO_MINING
                d.contains("samsung") || d.contains("roku") || d.contains("vizio") ||
                    d.contains("lgsmart") -> SMART_TV
                d.contains("unity3d") || d.contains("applovin") || d.contains("ironsrc") ||
                    d.contains("chartboost") || d.contains("vungle") -> GAME_ADS
                d.contains("youtube") || d.contains("ytimg") -> YOUTUBE_ADS
                d.contains("spotify") -> SPOTIFY_ADS
                d.contains("malware") || d.contains("phish") || d.contains("scam") -> MALWARE
                d.contains("dns.google") || d.contains("cloudflare-dns") || d.contains("doh.") -> DOH_BYPASS
                else -> OTHER
            }
        }
    }
}
