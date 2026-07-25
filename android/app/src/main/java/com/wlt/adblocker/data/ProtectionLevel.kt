package com.wlt.adblocker.data

/**
 * Phase 10b: Protection Level
 *
 * Tiered protection levels inspired by HaGeZi DNS blocklists.
 * Each level enables/disables different blocklist sources and
 * features to balance blocking vs false positives.
 */
enum class ProtectionLevel(
    val displayName: String,
    val description: String,
    val estimatedDomains: String,
) {
    LIGHT(
        "Light",
        "Minimal blocking — only WLT bundled lists. No false positives.",
        "~900 domains",
    ),
    NORMAL(
        "Normal",
        "Balanced — WLT bundled + HaGeZi Normal. Recommended for most users.",
        "~500K domains",
    ),
    PRO(
        "Pro",
        "Aggressive — WLT bundled + HaGeZi Pro + OISD Big. May cause rare false positives.",
        "~2M domains",
    ),
    PRO_PLUS(
        "Pro++",
        "Very aggressive — HaGeZi Pro++ + aggressive DGA. May break some sites.",
        "~3M domains",
    ),
    ULTIMATE(
        "Ultimate",
        "Maximum blocking — everything enabled. Will break some apps/sites.",
        "~4M domains",
    );

    companion object {
        /**
         * Returns the remote blocklist URLs to enable for this protection level.
         * Used by BlocklistManager to download/load the appropriate lists.
         */
        fun remoteSources(level: ProtectionLevel): List<String> = when (level) {
            LIGHT -> emptyList() // Bundled only
            NORMAL -> listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/normal-onlydomains.txt",
            )
            PRO -> listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
                "https://big.oisd.nl/domainswild",
            )
            PRO_PLUS -> listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/proplus-onlydomains.txt",
                "https://big.oisd.nl/domainswild",
            )
            ULTIMATE -> listOf(
                "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/ultimate-onlydomains.txt",
                "https://big.oisd.nl/domainswild",
                "https://urlhaus.abuse.ch/downloads/hostfile/",
            )
        }

        /**
         * Returns whether aggressive DGA detection should be enabled.
         */
        fun aggressiveDGA(level: ProtectionLevel): Boolean = when (level) {
            LIGHT, NORMAL -> false
            PRO, PRO_PLUS, ULTIMATE -> true
        }

        /**
         * Returns whether domain age checking should be enabled.
         */
        fun domainAgeCheck(level: ProtectionLevel): Boolean = when (level) {
            LIGHT -> false
            NORMAL, PRO, PRO_PLUS, ULTIMATE -> true
        }
    }
}
