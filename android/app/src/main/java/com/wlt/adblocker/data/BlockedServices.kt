package com.wlt.adblocker.data

/**
 * Blocked Services — Phase 9e
 *
 * One-click blocking for entire services (Facebook, TikTok, YouTube, etc.).
 * Each service maps to a curated list of domains. When toggled on, all
 * domains for that service are added to the RuleStore as block rules.
 *
 * AdGuard Home feature — easier for users than adding individual domains.
 */
object BlockedServices {

    data class Service(
        val id: String,
        val name: String,
        val emoji: String,
        val domains: List<String>,
    )

    val services = listOf(
        Service("facebook", "Facebook / Meta", "📘", listOf(
            "facebook.com", "fb.com", "fbcdn.net", "messenger.com",
            "whatsapp.com", "whatsapp.net", "instagram.com",
            "cdninstagram.com", "threads.net",
        )),
        Service("tiktok", "TikTok", "🎵", listOf(
            "tiktok.com", "tiktokv.com", "tiktokcdn.com",
            "musical.ly", "byteoversea.com", "snssdk.com",
        )),
        Service("youtube", "YouTube", "▶", listOf(
            "youtube.com", "m.youtube.com", "youtu.be",
            "youtubei.googleapis.com", "ytimg.com",
        )),
        Service("reddit", "Reddit", "👽", listOf(
            "reddit.com", "redditmedia.com", "redditstatic.com",
            "redd.it", "redditads.com",
        )),
        Service("snapchat", "Snapchat", "👻", listOf(
            "snapchat.com", "snapkit.com", "sc-cdn.net",
            "snapads.com",
        )),
        Service("twitter", "X / Twitter", "🐦", listOf(
            "twitter.com", "x.com", "twimg.com",
            "t.co", "ads-twitter.com", "analytics.twitter.com",
        )),
        Service("discord", "Discord", "💬", listOf(
            "discord.com", "discordapp.com", "discord.gg",
            "discordapp.net", "discord.media",
        )),
        Service("pinterest", "Pinterest", "📌", listOf(
            "pinterest.com", "pinimg.com", "ct.pinterest.com",
        )),
        Service("linkedin", "LinkedIn", "💼", listOf(
            "linkedin.com", "licdn.com", "lnkd.in",
        )),
        Service("netflix", "Netflix", "🎬", listOf(
            "netflix.com", "nflxvideo.net", "nflximg.net",
        )),
        Service("spotify", "Spotify", "🎵", listOf(
            "spotify.com", "scdn.co", "spotifycdn.com",
        )),
        Service("twitch", "Twitch", "🎮", listOf(
            "twitch.tv", "ttvnw.net", "jtvnw.net",
            "twitchcdn.net",
        )),
        Service("telegram", "Telegram", "✈️", listOf(
            "telegram.org", "t.me", "telegram.me",
            "tdesktop.com",
        )),
    )

    /** Returns the service with the given ID, or null. */
    fun byId(id: String): Service? = services.find { it.id == id }

    /** Returns the total number of domains across all services. */
    fun totalDomains(): Int = services.sumOf { it.domains.size }
}
