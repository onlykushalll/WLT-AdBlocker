# WLT-Adblocker

Production-grade, privacy-first, system-wide ad blocker for Android — built from the best ideas in open-source adblocking.

## What It Is

WLT-Adblocker is a multi-layer ad blocker that combines techniques from uBlock Origin, AdGuard, RethinkDNS, NetGuard, HostShield, and BlockAds into one free, open-source, on-device app.

**No root. No cloud. No telemetry. No account.**

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         YOUR PHONE                                │
│                                                                   │
│  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │  Games  │  │  Apps   │  │ Browser  │  │ YouTube App      │   │
│  └────┬────┘  └────┬────┘  └────┬─────┘  └──────┬───────────┘   │
│       └────────────┴────────────┴────────────────┘               │
│                          │                                        │
│              ┌───────────▼───────────┐                            │
│              │   WLT VpnService      │  Layer 1: DNS              │
│              │   (Go trie+bloom)     │  Blocks 85-90%             │
│              └───────────┬───────────┘                            │
│                          │                                        │
│              ┌───────────▼───────────┐                            │
│              │  CNAME Cloaking Check  │  Detects disguised        │
│              │  (in upstream response)│  tracker CNAMEs           │
│              └───────────┬───────────┘                            │
│                          │                                        │
│              ┌───────────▼───────────┐                            │
│              │  DoH Upstream Resolver │  Encrypted DNS            │
│              │  (Cloudflare/Google)   │  (privacy-preserving)     │
│              └───────────────────────┘                            │
└─────────────────────────────────────────────────────────────────┘
```

## Go Core Engine (wlt.aar)

The filtering engine is written in Go and compiled to a native Android library via gomobile:

- **Domain Trie** — reversed-label trie with wildcard support, O(m) lookup
- **Counting Bloom Filter** — 4-bit counters, suffix-aware, 0.08% false-positive rate
- **Game Ad Intelligence** — 12 SDK fingerprints (AdMob, Unity, AppLovin, ironSource, Chartboost, Vungle, Meta, AdColony, Mintegral, Fyber, Tapjoy, InMobi)
- **Ad Forensics Engine** — records per-layer decisions, tracks why ads got through
- **DNS Parser** — RFC 1035, CNAME extraction, compression pointers, NXDOMAIN/0.0.0.0/REFUSED builders
- **SNI Extractor** — TLS ClientHello parsing (no MITM needed)
- **CNAME Cloaking Detection** — checks CNAME chains for disguised trackers
- **DoH Bypass Prevention** — blocks known DoH endpoints (dns.google, cloudflare-dns.com, etc.)

## Android App Features

- **VPN DNS Interception** — system-wide, no root
- **Go Engine** — primary block engine (trie/bloom/forensics via gomobile)
- **Kotlin Fallback** — if Go engine fails to load
- **DoH Resolver** — Cloudflare/Google/Quad9/AdGuard upstreams
- **Custom Rules** — user-defined block/allow domains, wired to engine
- **Per-App Firewall** — exclude apps from VPN filtering
- **Pause Protection** — temporarily disable for 5/15/30/60 minutes
- **Query Log** — 2000-entry ring buffer with filterable UI
- **Block-Rate Chart** — Canvas sparkline, 60-minute history
- **Blocklist Gallery** — 14 lists (OISD, AdGuard, HaGeZi, malware, crypto)
- **Blocklist Auto-Update** — WorkManager, 24-hour schedule
- **DNS Latency Tester** — tests 4 upstream providers
- **Ad Forensics Screen** — live blocked/allowed queries with SDK badges
- **Onboarding** — 4-page first-launch flow
- **Quick Settings Tile** — one-tap VPN toggle
- **Settings Export/Import** — JSON backup of rules and config
- **Material 3 UI** — dark green/teal theme, 10 screens

## Blocklists

| List | Domains | Category |
|------|---------|----------|
| WLT Game Ads | 150+ | Game SDK + DoH bypass |
| WLT Passthrough | 70+ | Banking/gov/critical |
| WLT CNAME Cloak | 16 | Tracker CNAME targets |
| OISD Big | ~450K | Ads/trackers (remote) |
| AdGuard DNS | ~90K | Ads (remote, ABP format) |
| HaGeZi Normal | ~180K | Ads (remote) |
| + 8 more | — | Trackers, malware, crypto |

## Security Tests

8 unit tests in `BlockEngineSecurityTest.kt`:
1. Ad domains blocked (13/13)
2. Legitimate domains allowed (0 false positives)
3. Wildcard subdomains blocked
4. Edge cases (empty/malformed/unicode)
5. Allowlist precedence
6. Custom rules override
7. DoH bypass prevention
8. Block response validation

## Build

```bash
# Go core → wlt.aar
cd wlt-core
gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 -androidapi 24 -o ../android/app/libs/wlt.aar .

# Android APK
cd ../android
./gradlew assembleDebug
```

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Filter engine | Go 1.26 + gomobile |
| Android UI | Kotlin + Jetpack Compose + Material 3 |
| VPN | Android VpnService (no root) |
| DNS | RFC 1035 parser (Go + Kotlin) |
| Upstream | DoH (RFC 8484) + UDP fallback |
| Persistence | DataStore + WorkManager |
| Build | Gradle 8.10.2 + AGP 8.7.3 |

## License

GPL-3.0 (compatible with AdGuard urlfilter, uBlock filter lists)
