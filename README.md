# WLT-AdBlocker

> Privacy-first, open-source Android ad blocker powered by a Go core engine.
> No root required. No cloud. No telemetry. No accounts.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Go Tests](https://img.shields.io/badge/Go%20Tests-23%20packages-brightgreen)](#testing)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)](#requirements)
[![Go Version](https://img.shields.io/badge/Go-1.22%2B-00ADD8)](#building-from-source)
[![Scriptlets](https://img.shields.io/badge/Scriptlets-80-orange)](#scriptlets)
[![Blocklists](https://img.shields.io/badge/Blocklists-888%20domains-yellow)](#blocklists)

## Features

### 4-Layer Smart Cascade
WLT uses a 4-layer defense system to block ads, trackers, and malware:

| Layer | Technique | Status |
|---|---|---|
| **Phase 1 — DNS Blocking** | Trie + Bloom filter + Game SDK + DoH + CNAME-cloak + DGA detection + Domain age (RDAP) | ✅ Active |
| **Phase 2 — SNI Inspection** | TLS ClientHello SNI extraction + JA4+ fingerprinting | ✅ Active |
| **Phase 3 — HTTPS MITM** | Local CA (ECDSA P-256), per-domain cert, m3u-prune, 80 scriptlets, 8 noop resources | ✅ Opt-in |
| **Phase 4 — Advanced** | Regex matching (Pi-hole), ABP rule parser, preparser directives, DNS rewrite | ✅ Active |

### Key Capabilities

- **888 blocklist domains** across 10 curated lists
- **80 scriptlets** (uBlock Origin + Brave + YouTube + Spotify + Twitch + Reddit + Twitter + Instagram + crypto mining + fingerprinting protection)
- **23 Go packages** compiled to native library (arm64/arm/x86/x86_64)
- **12 game SDK fingerprints** (AdMob, Unity, AppLovin, ironSource, Chartboost, Vungle, Meta, AdColony, Mintegral, Fyber, Tapjoy, InMobi)
- **Regex domain matching** (Pi-hole-style patterns with LRU-bounded RegexManager)
- **Domain age checking** via async RDAP (NextDNS technique — blocks domains < 30 days old)
- **JA4+ TLS fingerprinting** (post-ECH preparation — identifies ad SDKs by TLS stack)
- **ABP/uBlock rule parser** (paste `||example.com^$important` rules directly)
- **Pre-parsing directives** (`!#if ext_wlt`, `!#if env_android`, `!#if cap_mitm`)
- **DNS rewrite engine** (AdGuard-style: NXDOMAIN, NullIP, REFUSED, NODATA, CNAME + Safe Search)
- **DNS response cache** (LRU 10K entries, ~70% query reduction, <1ms cache hits)
- **DoT port 853 blocking** (prevents DNS bypass via DNS-over-TLS)
- **QUIC blocking** (opt-in, forces TCP fallback for visible SNI)
- **ECH config blocking** (prevents Encrypted Client Hello bypass)
- **DGA detection** (Shannon entropy + n-gram + vowel/digit ratio)
- **DoH bypass prevention** (blocks 30+ known DoH/ODoH provider domains)
- **CNAME cloaking detection** (50 CNAME targets covering 100K subdomains)
- **Per-app firewall** (VpnService.Builder.addDisallowedApplication)
- **Per-UID tracking** (ConnectivityManager.getConnectionOwnerUid)
- **IP→domain reverse lookup** (NetGuard technique — in-memory LRU cache)
- **Per-app analytics** (queries, blocked, trackers detected, data usage)
- **Domain categorization** (12 categories: Advertising, Tracking, Analytics, Social, etc.)
- **Blocked services** (13 services: Facebook, TikTok, YouTube, Reddit, Snapchat, Twitter, Discord, Pinterest, LinkedIn, Netflix, Spotify, Twitch, Telegram)
- **WireGuard support** (config parser, tunnel manager, split tunneling)
- **PCAP export** (capture packets for Wireshark analysis)
- **Root mode** (optional — modify /etc/hosts directly for rooted devices)
- **5 protection levels** (Light / Normal / Pro / Pro++ / Ultimate)
- **Pause protection** (5/15/30/60 minute pause with auto-resume)
- **Quick Settings tile** (one-tap VPN toggle)
- **15 Compose screens** (Dashboard, QueryLog, Blocklists, CustomRules, AppFirewall, DnsLatency, Forensics, Settings, Onboarding, PauseProtection, BlockedServices, AppAnalytics, RegexRules, BatteryOptimization, WireGuard)

### Privacy Guarantees

- ✅ **No root required** — Uses Android VpnService
- ✅ **No cloud proxy** — All filtering happens on-device
- ✅ **No telemetry** — Zero outbound analytics
- ✅ **No DNS logging to remote servers** — Query log is in-memory only
- ✅ **DoH-first upstream** — Encrypted DNS to Cloudflare/Google/Quad9/AdGuard
- ✅ **No accounts** — No login, no sync, no cloud
- ✅ **Open source** — GPL v3, full source code available

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    Android App (Kotlin)                   │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │   VPN    │  │  Compose │  │  DataStore│  │ WorkManager│  │
│  │ Service  │  │   UI     │  │  Prefs    │  │ (24h auto)│  │
│  └────┬─────┘  └──────────┘  └──────────┘  └──────────┘  │
│       │              15 Screens                             │
│  ┌────▼─────────────────────────────────────────────────┐ │
│  │              Go Core Engine (wlt.aar)                 │ │
│  │  ┌────────┐ ┌──────┐ ┌────────┐ ┌────────┐ ┌───────┐ │ │
│  │  │ Engine │ │ Trie │ │ Bloom  │ │GameSDK │ │Regexps│ │ │
│  │  └────────┘ └──────┘ └────────┘ └────────┘ └───────┘ │ │
│  │  ┌────────┐ ┌──────┐ ┌────────┐ ┌────────┐ ┌───────┐ │ │
│  │  │DomainAge│ │ JA4+ │ │DNSRewrite│ │RuleParser│ │Preparser│ │ │
│  │  └────────┘ └──────┘ └────────┘ └────────┘ └───────┘ │ │
│  │  ┌────────┐ ┌──────┐ ┌────────┐ ┌────────┐ ┌───────┐ │ │
│  │  │ MITM   │ │HTTPS │ │Scriptlets│ │Cosmetic│ │m3uprune│ │ │
│  │  │  CA    │ │Proxy │ │ (80)    │ │Engine  │ │       │ │ │
│  │  └────────┘ └──────┘ └────────┘ └────────┘ └───────┘ │ │
│  │  ┌────────┐ ┌──────┐ ┌────────┐                       │ │
│  │  │WireGuard│ │DnsCache│ │NoopRes │                    │ │
│  │  └────────┘ └──────┘ └────────┘                       │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

## Requirements

- Android 8.0 (API 26) or later
- ~70 MB storage for APK
- ~50 MB RAM for Go engine + blocklists

## Download

Download the latest APK from the [releases page](../../releases) or build from source.

### Installation

1. Download `WLT-Adblocker-debug.apk`
2. Enable "Install from unknown sources" in Android settings
3. Install the APK
4. Open WLT and tap the shield to grant VPN permission
5. Done — WLT is now protecting your device

## Building from Source

### Prerequisites

- **Go 1.22+** — for the core engine
- **Android SDK** (API 35+, build-tools 34)
- **Android NDK** (for gomobile bind)
- **JDK 17** (Eclipse Temurin recommended)
- **Gradle 8.10+** (wrapper included)

### Build Steps

```bash
# 1. Build the Go core engine into wlt.aar
cd wlt-core
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 \
  -androidapi=21 -o ../android/app/libs/wlt.aar .

# 2. Build the APK
cd ../android
./gradlew :app:assembleDebug

# 3. Find the APK
ls app/build/outputs/apk/debug/app-debug.apk
```

> **Note**: If gomobile/gobind are blocked by WDAC (Windows Device Guard),
> rebuild them with a different name: `go build -o wlt-gomobile.exe golang.org/x/mobile/cmd/gomobile`

### Testing

```bash
# Run all Go tests (23 packages, 150+ tests)
cd wlt-core
go test ./... -count=1
go vet ./...
```

## Project Structure

```
WLT-AdBlocker/
├── wlt-core/                    # Go core engine (23 packages)
│   ├── mobile.go                # gomobile API layer
│   ├── engine/                  # Block engine (4-layer cascade + regex + domain age)
│   ├── dns/                     # RFC 1035 DNS parser (NXDOMAIN/NullIP/NullIPv6/NODATA/REFUSED)
│   ├── filter/                  # Blocklist loader + preparser
│   ├── net/                     # SNI extractor + IP blocklist
│   └── internal/
│       ├── trie/                # Reversed-label domain trie (thread-safe)
│       ├── bloom/               # Counting bloom filter (suffix-aware)
│       ├── gamesdk/             # 12 game SDK fingerprints
│       ├── forensics/           # Ad forensics engine
│       ├── mitm/                # CA cert generation + per-leaf keypair signing
│       ├── httpsproxy/          # HTTPS MITM proxy + noop resources + MITM allowlist
│       ├── scriptlets/          # 80 scriptlets
│       ├── cosmetic/            # CSS injection + 9 procedural filters
│       ├── m3uprune/            # HLS ad segment stripper (SCTE-35)
│       ├── sponsorblock/        # SponsorBlock API client (12 categories)
│       ├── ja4/                 # JA4+ TLS fingerprinting + ad SDK database
│       ├── dga/                 # DGA detection (entropy + n-gram)
│       ├── dnsrewrite/          # DNS rewrite engine (5 types + Safe Search)
│       ├── domainage/           # RDAP domain age checker (async)
│       ├── ruleparser/          # ABP/uBlock rule parser ($important, $badfilter, $dnstype)
│       ├── preparser/           # Pre-parsing directives (!#if/!#else/!#endif/!#include)
│       └── wireguard/           # WireGuard config parser + tunnel manager
├── android/                     # Android app (Kotlin + Compose)
│   └── app/src/main/
│       ├── java/com/wlt/adblocker/
│       │   ├── vpn/             # VPN service, engines, DnsCache, DomainIpCache, RootMode
│       │   ├── filter/          # Domain trie + blocklist manager
│       │   ├── data/            # RuleStore, QueryLog, StatsHistory, AppNetworkStats,
│       │   │                    # BlockCategory, BlockedServices, ProtectionLevel, PcapExporter
│       │   └── ui/              # 15 Compose screens + theme
│       └── assets/blocklists/   # 10 blocklist files (888 domains)
├── research-2025/               # Deep research (42 files, 6000+ lines)
├── scripts/                     # Build/deploy scripts
│   └── mcpilot/                 # MCPilot remote control scripts
├── ROADMAP.md                   # Phase 7-13 roadmap
├── CHANGELOG.md                 # Version history
├── PRIVACY_POLICY.md            # Privacy policy
├── CONTRIBUTING.md              # Contribution guidelines
└── LICENSE                      # GPL v3
```

## Blocklists

| File | Domains | Category |
|---|---|---|
| `wlt-game-ads.txt` | 202 | Game SDK ads + DoH bypass + ECH config |
| `wlt-trackers.txt` | 228 | Analytics + attribution + telemetry |
| `wlt-smart-tv-ads.txt` | 85 | Samsung/Roku/LG/Vizio ACR |
| `wlt-passthrough.txt` | 83 | Banking/gov allowlist |
| `wlt-youtube-ads.txt` | 83 | YouTube CSAI/SSAI/tracking |
| `wlt-crypto-mining.txt` | 75 | Mining pools + browser miners |
| `wlt-social-ads.txt` | 61 | TikTok/Snapchat/Twitch/Pangle |
| `wlt-cname-cloak.txt` | 50 | CNAME cloaking targets (covers 100K subdomains) |
| `wlt-spotify-ads.txt` | 21 | Spotify ad/tracking |
| **Total** | **888** | |

## Scriptlets (80)

### Ad Networks (7)
adsbygoogle, doubleclick, googletag, google-analytics, facebook-pixel, twitter-ads, amazon-ads

### Network Blocking (5)
fetch-blocker, xhr-blocker, noeval, prevent-fetch, prevent-xhr

### Anti-Adblock (7)
abort-current-script, anti-adblock, overlay-buster, abort-on-property-read, abort-on-property-write, abort-on-stack-trace, prevent-bab

### Popups (3)
prevent-window-open, close-window, no-window-open-if

### DOM Manipulation (6)
remove-class, prevent-refresh, remove-node-text, replace-node-text, remove-attr, set-attr

### Timers (2)
adjust-setInterval, adjust-setTimeout

### Privacy / Fingerprinting (7)
prevent-canvas, prevent-webgl, prevent-audio-fingerprint, prevent-font-enumeration, webrtc-if, window-name-defuser, no-floc

### Cookies / Storage (5)
set-cookie, set-local-storage-item, set-session-storage-item, remove-cookie, remove-cache-storage-item

### XML/JSON (5)
xml-prune, json-prune, m3u-prune, json-prune-fetch-response, json-prune-xhr-response

### YouTube (5)
yt-player-intercept, yt-speed-up-ads, yt-remove-ad-survey, yt-block-ads-request, yt-sponsorblock

### Spotify (1)
spotify-ad-intercept

### Twitch (3)
twitch-video-swap, twitch-mute-ads, twitch-block-ad-request

### Social (3)
reddit-hide-promoted, twitter-hide-promoted, instagram-hide-sponsored

### Crypto (1)
block-crypto-miners

### Trusted (10)
trusted-replace-fetch-response, trusted-replace-xhr-response, trusted-click-element, trusted-replace-argument, trusted-replace-node-text, trusted-set-constant, trusted-set-cookie, trusted-set-local-storage-item, trusted-set-session-storage-item, trusted-prune-inbound-object, trusted-prune-outbound-object

### De-AMP (1)
de-amp (Brave technique — redirect AMP to canonical)

### Utilities (8)
href-sanitizer, spoof-css, set-constant, break-on-call, call-nothrow, disable-newtab-links, alert-buster, noeval-if

## Honest Limitations

WLT is powerful, but it's not magic. Here's what it **cannot** do:

- **YouTube app ads** — The YouTube app uses Server-Side Ad Insertion (SSAI) and certificate pinning. DNS blocking reduces tracking but doesn't remove in-stream ads. Use [ReVanced](https://revanced.app) or [NewPipe](https://newpipe.net) for ad-free YouTube.
- **Spotify app ads** — Same as YouTube — server-side audio ads + cert pinning. Use [xManager](https://github.com/xManager-App/xManager) for ad-free Spotify.
- **TikTok in-feed ads** — Ads share the same CDN as organic content. DNS blocking only reduces telemetry.
- **Certificate-pinned apps** — HTTPS MITM (Phase 3) doesn't work on apps that pin certificates (banking, social media, streaming).

**What WLT DOES well:**
- DNS-level blocking across ALL apps (ads, trackers, malware, telemetry)
- Per-app firewall (block specific apps from network)
- Browser ad blocking via scriptlets (Phase 3 HTTPS MITM)
- Game ad blocking (12 SDK fingerprints)
- Smart TV ad/ACR blocking
- Crypto mining blocking
- Privacy protection (DoH bypass, CNAME cloaking, DGA, domain age, DoT blocking)
- WireGuard tunnel support for encrypted DNS upstream
- PCAP export for network analysis

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Privacy Policy

See [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

## Acknowledgments

WLT-AdBlocker incorporates techniques and ideas from:
- [uBlock Origin](https://github.com/gorhill/uBlock) — Scriptlets, cosmetic filtering, m3u-prune
- [Pi-hole](https://github.com/pi-hole/pi-hole) — Regex matching, gravity database concept
- [AdGuard Home](https://github.com/AdguardTeam/AdGuardHome) — DNS rewrite syntax, Safe Search
- [NetGuard](https://github.com/M66B/NetGuard) — Per-UID tracking, IP→domain reverse lookup
- [RethinkDNS](https://github.com/celzero/rethink-app) — Go + gomobile architecture, WireGuard
- [Blokada](https://github.com/blokadaorg/blokada) — Go + gomobile architecture
- [Brave](https://github.com/brave/adblock-rust) — De-AMP, Rust adblock engine concepts
- [AdAway](https://github.com/AdAway/AdAway) — Hosts-based blocking, root mode
- [NextDNS](https://nextdns.io) — Domain age checking, CNAME cloaking blocklist
- [SponsorBlock](https://sponsor.ajay.app) — Crowdsourced segment skipping
- [TwitchAdSolutions](https://github.com/pixeltris/TwitchAdSolutions) — Twitch video-swap technique

## Roadmap

See [ROADMAP.md](ROADMAP.md) for the development roadmap. All phases (7-13) are now complete.
