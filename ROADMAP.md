# WLT-Adblocker Roadmap — Phases 7-13

> **Current state**: Phase 1-6 complete. 22 Go packages, 143 tests, 71 scriptlets,
> 886 blocklist domains, 49.7 MB APK with Go native engine.
>
> **Key insight from audit**: 5 Go packages are built but NOT wired to the
> Android app (ruleparser, preparser, domainage, dnsrewrite, ja4). Phase 7
> focuses on wiring these existing components — highest ROI, lowest risk.

---

## Phase 7: Wire Existing Engines (1 session)

**Goal**: Connect the 5 Go packages that are built, tested, but not exposed
to Android. This is pure integration work — the code exists, we just need
to call it.

### 7a: Expose regex via gomobile API
- **What**: Add `AddRegex(pattern string) error` and `RegexCount() int` to
  `mobile.go` (Engine struct). The Go engine already has regex support
  (Phase 6), but it's not callable from Kotlin.
- **Why**: Lets users add Pi-hole-style regex rules from the app UI.
- **Files**: `wlt-core/mobile.go`, `vpn/GoBlockEngine.kt`
- **Risk**: Low — just adding methods to existing struct
- **Test**: Add regex rule `^ads[0-9]*\.`, verify `ads0.example.com` is blocked

### 7b: Expose BuildNullIPv6 + BuildNODATA via gomobile
- **What**: Add `BuildBlockResponseIPv6(decision int, query []byte) []byte`
  and expose NODATA as a new decision code. The Go DNS parser already has
  `BuildNullIPv6()` and `BuildNODATA()` (Phase 6), but they're not callable.
- **Why**: IPv6 networks (T-Mobile, Jio) need AAAA null IP (::), not just
  IPv4 null IP (0.0.0.0). NODATA is the AdGuard-preferred response type.
- **Files**: `wlt-core/mobile.go`, `wlt-core/engine/engine.go`
- **Risk**: Low — exposing existing functions
- **Test**: Send AAAA query, verify response has ::

### 7c: Wire ruleparser to custom rules UI
- **What**: The Go `ruleparser` package parses ABP syntax (`||domain^`,
  `@@||allow^`, `$important`, `$badfilter`, hosts format). Currently the
  custom rules UI only accepts bare domains. Wire ruleparser so users can
  paste uBlock/ABP rules directly.
- **Why**: Users have existing uBlock filter lists they want to import.
- **Files**: `wlt-core/mobile.go` (add `ParseRule(line string) *ParsedRule`),
  `vpn/KotlinBlockEngine.kt` (call ParseRule for custom rules),
  `ui/screens/CustomRulesScreen.kt` (accept ABP syntax)
- **Risk**: Medium — need to handle rejected rules (cosmetic `##`, scriptlet `##+js`)
- **Test**: Paste `||doubleclick.net^$important`, verify it blocks + overrides allowlist

### 7d: Wire preparser to blocklist loading
- **What**: The Go `preparser` package handles `!#if/!#else/!#endif/!#include`
  directives with WLT-specific tokens (`ext_wlt`, `env_android`, `cap_mitm`).
  Currently blocklists are loaded raw — preparser is not called.
- **Why**: Remote blocklists (HaGeZi, AdGuard) use pre-parsing directives.
  Without preparser, WLT ignores these directives and may load rules
  intended for browser extensions (not applicable at VPN level).
- **Files**: `wlt-core/filter/loader.go` (call preparser.Process before parsing),
  `wlt-core/mobile.go` (expose if needed)
- **Risk**: Low — preparser is tested, just needs calling
- **Test**: Load a blocklist with `!#if ext_ublock` block, verify it's skipped

### 7e: Wire domainage to block decision pipeline
- **What**: The Go `domainage` package checks RDAP for domain creation date
  and flags domains < 30 days old as suspicious (NextDNS technique). Currently
  it's not called in the CheckDNS cascade.
- **Why**: Catches fresh malware, DGA, phantom-squatting domains that aren't
  in any blocklist yet.
- **Challenge**: RDAP queries are network calls (slow, ~200-500ms). Can't
  block synchronously. Need async approach:
  1. Query passes through (allowed initially)
  2. Background goroutine queries RDAP
  3. If suspicious, add to dynamic blocklist for future queries
  4. Cache result for 24 hours
- **Files**: `wlt-core/engine/engine.go` (add domainage checker as
  background goroutine), `wlt-core/mobile.go`
- **Risk**: Medium — async blocking means first query to a new domain
  always passes. But this is acceptable — we block on second query.
- **Test**: Query a domain registered yesterday, verify it's blocked on
  second query (after RDAP check completes)

### 7f: Wire JA4+ to SNI inspection
- **What**: The Go `ja4` package computes JA4+ TLS fingerprints. Currently
  not called from the SNI inspection path. After ECH deployment, SNI will
  be encrypted — JA4+ is the fallback identification method.
- **Why**: Post-ECH preparation. Even without SNI, we can identify ad SDKs
  by their TLS stack fingerprint.
- **Challenge**: Need a database of known ad SDK JA4+ fingerprints. Currently
  empty. Phase 7 just wires the computation; Phase 12 builds the database.
- **Files**: `wlt-core/engine/engine.go` (compute JA4+ in CheckSNI, log it),
  `wlt-core/mobile.go` (expose `GetJA4(clientHello []byte) string`)
- **Risk**: Low — just computing and logging, not blocking yet
- **Test**: Send TLS ClientHello, verify JA4+ fingerprint is computed and logged

---

## Phase 8: DNS Infrastructure (1 session)

**Goal**: Prevent DNS bypass and improve DNS performance. These are
defensive improvements — without them, apps can escape WLT's filtering.

### 8a: DNS response cache
- **What**: Add a DNS cache (ConcurrentHashMap + LRU, 10,000 entries) that
  caches both allowed and blocked responses.
  - Allowed: Cache for upstream TTL (capped at 3600s)
  - Blocked: Cache for 300s (5 min)
  - NXDOMAIN: Cache for 900s (15 min)
- **Why**: 70% reduction in upstream queries. Faster responses for cache
  hits (<1ms vs 50-200ms). Less battery, less bandwidth.
- **Files**: `vpn/DnsCache.kt` (new), `vpn/WltVpnService.kt` (check cache
  before upstream, store after)
- **Risk**: Low — cache is additive, doesn't change blocking logic
- **Test**: Query google.com twice, verify second query is <1ms

### 8b: Block DoT port 853
- **What**: Add a firewall rule in the VPN packet loop that drops all
  UDP/TCP packets to port 853 (DNS-over-TLS).
- **Why**: Apps can use DoT to bypass VPN DNS entirely. Port 853 is
  exclusively for DoT — blocking it forces apps through WLT's DNS.
- **Files**: `vpn/WltVpnService.kt` (add port check in packet loop),
  `ui/screens/SettingsScreen.kt` (toggle: "Block DoT port 853")
- **Risk**: Low — port 853 is only used for DoT, no legitimate traffic
  on this port
- **Test**: App tries DoT to 1.1.1.1:853, verify connection is dropped

### 8c: QUIC blocking option
- **What**: Add a toggle to block UDP port 443 (QUIC/HTTP/3). When enabled,
  apps fall back to TCP+TLS where SNI is visible.
- **Why**: QUIC encrypts more of the handshake. Blocking it forces TCP
  fallback where WLT can inspect SNI. Also prevents DoQ (DNS-over-QUIC).
- **Challenge**: Breaks legitimate HTTP/3 (performance regression). Must
  be opt-in, not default.
- **Files**: `vpn/WltVpnService.kt` (drop UDP 443 when enabled),
  `ui/screens/SettingsScreen.kt` (toggle: "Block QUIC (force TCP)")
- **Risk**: Medium — may break some apps that require QUIC. User must opt in.
- **Test**: Enable QUIC blocking, verify Chrome falls back to TCP

### 8d: ECH config blocking
- **What**: Block domains that serve ECH (Encrypted Client Hello) configs.
  Without the config, clients fall back to regular TLS (visible SNI).
- **Why**: ECH encrypts SNI, breaking Phase 2 (SNI inspection). Blocking
  ECH config endpoints forces fallback to visible SNI.
- **Domains to block**: `cloudflare-ech.com`, `dns.cloudflare.com/ech`,
  `crypto.cloudflare.com`
- **Challenge**: This is a privacy regression (ECH is good for privacy).
  Must be opt-in, clearly labeled.
- **Files**: Add domains to `wlt-game-ads.txt`, add toggle in Settings
- **Risk**: Medium — breaks ECH for all sites, not just ad sites
- **Test**: Visit Cloudflare site, verify SNI is visible (not encrypted)

---

## Phase 9: Per-App Intelligence (1-2 sessions)

**Goal**: Show users which apps are connecting to which domains, which
trackers each app uses, and how much data WLT is saving per app. This is
the biggest user-visible improvement — it transforms WLT from a "set and
forget" tool into an actionable privacy dashboard.

### 9a: SQLite IP→domain reverse lookup
- **What**: When WLT resolves a DNS query (e.g., `ads.example.com` →
  `1.2.3.4`), store the mapping in SQLite with a 5-minute TTL. When a
  TCP/UDP connection is made to `1.2.3.4`, look up the domain.
- **Why**: This is the NetGuard technique. It enables per-app domain
  attribution — "this app connected to this domain at this time". Without
  it, WLT can only see DNS queries, not actual TCP/UDP connections.
- **Files**: `data/DomainCache.kt` (new — SQLite or LRU cache),
  `vpn/WltVpnService.kt` (store DNS results, lookup on TCP/UDP)
- **Risk**: Medium — SQLite adds complexity, but NetGuard proves it works
- **Test**: App connects to 1.2.3.4, verify WLT shows "connected to ads.example.com"

### 9b: Per-connection tracking
- **What**: Log every TCP/UDP connection (app UID, domain, IP, port,
  timestamp, bytes in/out, blocked/allowed). Bounded ring buffer (5,000
  entries) to limit memory.
- **Why**: RethinkDNS's key feature. Users can see exactly what each app
  is doing. Enables per-app analytics (9c).
- **Files**: `data/ConnectionLog.kt` (new — ring buffer),
  `vpn/WltVpnService.kt` (log connections),
  `vpn/ConnectionFilter.kt` (already tracks TCP — extend it)
- **Risk**: Medium — performance impact of logging every connection.
  Mitigate: Only log when connection tracking is enabled (toggle).
- **Test**: Open browser, visit site, verify connection appears in log

### 9c: Per-app analytics screen
- **What**: New screen showing per-app breakdown:
  - Queries, blocked, block rate
  - Data in/out (via TrafficStats)
  - Top domains contacted
  - Trackers detected (from blocklist category matches)
  - Data saved estimate
- **Why**: TrackerControl's key feature. Users can see "this game uses
  AdMob, AppsFlyer, and Google Analytics" and decide to block it.
- **Files**: `ui/screens/AppAnalyticsScreen.kt` (new),
  `data/AppNetworkStats.kt` (new — per-UID stats)
- **Risk**: Low — read-only display, no changes to blocking logic
- **Test**: Open app analytics for a game, verify tracker list is shown

### 9d: Domain categorization
- **What**: Tag each blocked domain with a category (Advertising, Tracking,
  Analytics, Social, Malware, Crypto, Smart TV, Game Ads). Show "Top
  categories blocked" in statistics.
- **Why**: AdGuard v2.23 feature. Users want to know what types of content
  are being blocked, not just raw counts.
- **Implementation**: Tag by blocklist source:
  - wlt-game-ads.txt → Game Ads
  - wlt-trackers.txt → Tracking
  - wlt-crypto-mining.txt → Crypto Mining
  - wlt-smart-t-ads.txt → Smart TV
  - wlt-social-ads.txt → Advertising (Social)
  - wlt-youtube-ads.txt → Advertising (Video)
  - wlt-spotify-ads.txt → Advertising (Audio)
  - wlt-cname-cloak.txt → Tracking (CNAME)
- **Files**: `data/BlockCategory.kt` (new enum),
  `vpn/KotlinBlockEngine.kt` (return category with block result),
  `ui/screens/ForensicsScreen.kt` (show category breakdown)
- **Risk**: Low — just tagging existing domains
- **Test**: Block ads.google.com, verify it shows as "Advertising"

### 9e: Blocked services UI
- **What**: One-click toggle to block entire services (Facebook, TikTok,
  YouTube, etc.). Each service = a curated list of domains.
- **Why**: AdGuard Home feature. Easier for users than adding individual
  domains. "Block TikTok" with one tap.
- **Services**: Facebook, TikTok, Instagram, Twitter/X, YouTube, Reddit,
  Snapchat, Discord, Pinterest, LinkedIn, Netflix, Spotify, Twitch,
  WhatsApp, Telegram
- **Files**: `data/BlockedServices.kt` (new — service → domain list map),
  `ui/screens/BlockedServicesScreen.kt` (new — toggle per service)
- **Risk**: Low — adds rules to existing RuleStore, no engine changes
- **Test**: Toggle "Block TikTok", verify tiktok.com is blocked

---

## Phase 10: User Power Features (1 session)

**Goal**: Give users more control — custom regex rules, protection levels,
battery optimization, and proper ABP syntax support.

### 10a: Regex UI
- **What**: New screen where users can add Pi-hole-style regex rules.
  Uses the AddRegex() method exposed in Phase 7a.
- **Why**: Power users want regex. Catches patterns that suffix matching
  misses (e.g., `^ads[0-9]*\.`).
- **Files**: `ui/screens/RegexRulesScreen.kt` (new),
  `data/RuleStore.kt` (add regex rules list)
- **Risk**: Low — regex is already in Go engine, just needs UI
- **Test**: Add `^ads[0-9]*\.`, verify ads0.example.com is blocked

### 10b: Protection Level selector
- **What**: Dropdown to select protection level: Light / Normal / Pro /
  Pro++ / Ultimate. Each level enables/disables different blocklists
  and features.
  - Light: WLT bundled only (886 domains)
  - Normal: + HaGeZi Normal (~500K domains)
  - Pro: + HaGeZi Pro + OISD Big (~2M domains)
  - Pro++: + HaGeZi Pro++ + aggressive DGA
  - Ultimate: + HaGeZi Ultimate + all features
- **Why**: HaGeZi-style tiered protection. Users can choose their
  false-positive tolerance.
- **Files**: `ui/screens/SettingsScreen.kt` (add selector),
  `data/BlocklistManager.kt` (load/unload lists based on level)
- **Risk**: Medium — 2M domains needs memory optimization (Phase 13a)
- **Test**: Switch to Pro, verify more domains are blocked

### 10c: Battery optimization screen
- **What**: Detect device OEM (Samsung, Xiaomi, Huawei, etc.) and show
  specific instructions to disable battery optimization for WLT.
- **Why**: OEMs kill VPN apps aggressively. Users don't know how to fix this.
- **Files**: `ui/screens/BatteryOptimizationScreen.kt` (new),
  `util/OemDetector.kt` (new — detect OEM from Build.MANUFACTURER)
- **Risk**: Low — informational screen, no engine changes
- **Test**: Run on Samsung, verify Samsung-specific instructions are shown

### 10d: Export/import improvements
- **What**: Extend settings export to include regex rules, blocked services,
  and protection level. Also add CSV export of query log.
- **Why**: Users want to back up their full configuration. CSV export for
  analysis.
- **Files**: `data/SettingsExportImport.kt` (extend),
  `data/QueryLog.kt` (add CSV export)
- **Risk**: Low — extending existing functionality
- **Test**: Export settings, import on fresh install, verify all rules restored

---

## Phase 11: WireGuard + Encrypted Tunnel (2 sessions)

**Goal**: Add WireGuard support so users can route DNS (and optionally all
traffic) through an encrypted tunnel. This is RethinkDNS's killer feature.

### 11a: wireguard-go integration
- **What**: Compile wireguard-go (Go implementation of WireGuard) via
  gomobile bind. Produces native WireGuard tunnel inside WLT.
- **Why**: RethinkDNS's key differentiator. Users can encrypt DNS upstream
  through WireGuard, hiding queries from ISP.
- **Challenge**: wireguard-go is a complex library. Need to compile it
  alongside WLT's Go engine.
- **Files**: `wlt-core/internal/wireguard/` (new package),
  `wlt-core/mobile.go` (expose tunnel start/stop)
- **Risk**: High — WireGuard is complex, may have build issues
- **Test**: Start WireGuard tunnel, verify DNS goes through tunnel

### 11b: WireGuard config UI
- **What**: Screen to import WireGuard config (.conf file or QR code).
  Show tunnel status, data usage, connected endpoint.
- **Files**: `ui/screens/WireGuardScreen.kt` (new),
  `data/WireGuardConfig.kt` (new — parse .conf files)
- **Risk**: Low — UI work, engine already done in 11a
- **Test**: Import .conf file, verify tunnel connects

### 11c: Split tunneling (per-app WireGuard)
- **What**: Route specific apps through WireGuard, others direct. Uses
  VpnService.Builder.addDisallowedApplication() for non-tunneled apps.
- **Why**: RethinkDNS feature. Users can route only DNS through WireGuard,
  or route specific apps (e.g., banking) through WireGuard for security.
- **Files**: `ui/screens/WireGuardScreen.kt` (extend with per-app toggles)
- **Risk**: Medium — split tunneling is complex on Android
- **Test**: Route only Chrome through WireGuard, verify other apps use direct

### 11d: Per-app DNS configuration
- **What**: Different DNS upstreams for different apps. Requires Android 12+
  (API 31+) split DNS.
- **Why**: RethinkDNS v055o feature. Example: App A uses Cloudflare DNS,
  App B uses Quad9.
- **Challenge**: Android's split DNS API is finicky. Needs careful testing.
- **Files**: `vpn/WltVpnService.kt` (per-app DNS via
  VpnService.Builder.setUnderlyingNetworks or similar)
- **Risk**: High — Android API is poorly documented
- **Test**: Set App A to use Cloudflare, App B to use Quad9, verify different
  upstreams

---

## Phase 12: Advanced Filtering (1 session)

**Goal**: Reach full uBlock Origin parity for scriptlets and procedural
filters. Also add redirect resources.

### 12a: 9 missing uBO scriptlets
- **What**: Add the remaining 9 high-priority uBO scriptlets:
  - trusted-replace-node-text
  - trusted-set-constant
  - trusted-set-cookie
  - trusted-set-local-storage-item
  - trusted-set-session-storage-item
  - trusted-prune-inbound-object
  - trusted-prune-outbound-object
  - json-prune-fetch-response
  - json-prune-xhr-response
- **Why**: Full uBO scriptlet parity (71 → 80 scriptlets)
- **Files**: `wlt-core/internal/scriptlets/scriptlets.go`
- **Risk**: Low — same pattern as existing scriptlets
- **Test**: Register each scriptlet, verify it loads

### 12b: More procedural cosmetic filters
- **What**: Add :xpath, :upward, :downward, :subject procedural filters
  to the cosmetic engine.
- **Why**: uBO procedural filter parity. More powerful element hiding.
- **Files**: `wlt-core/internal/cosmetic/cosmetic.go`
- **Risk**: Medium — XPath in Go requires a library (libxml2 binding)
- **Test**: Filter `:xpath(//div[@class="ad"])`, verify it matches

### 12c: Resource replacements ($redirect=)
- **What**: Bundle noop resources (1x1.gif, noop.js, noop.css, noop.json,
  noop.html, noop-vast.xml). When a request matches a $redirect= rule,
  serve the noop resource instead of blocking.
- **Why**: Prevents broken pages. Anti-anti-adblock (pages can't detect
  blocked resources if they get a valid response).
- **Files**: `wlt-core/internal/httpsproxy/proxy.go` (add noop resource
  serving), `wlt-core/internal/ruleparser/ruleparser.go` (parse $redirect=)
- **Risk**: Low — resources are small, serving is straightforward
- **Test**: Block ad with $redirect=noop.js, verify page gets empty JS

### 12d: $dnstype modifier
- **What**: Block specific DNS query types. `||example.com^$dnstype=A`
  blocks A queries but allows AAAA.
- **Why**: AdGuard feature. Useful for blocking A records but allowing
  AAAA (or vice versa).
- **Files**: `wlt-core/internal/ruleparser/ruleparser.go` (parse $dnstype),
  `wlt-core/engine/engine.go` (check query type in cascade)
- **Risk**: Low — additive to rule parser
- **Test**: Block with $dnstype=A, verify A is blocked but AAAA passes

### 12e: RegexManager (LRU cache)
- **What**: LRU cache for compiled regexes. Discard infrequently used
  regexes to save memory, recompile on demand.
- **Why**: Brave's adblock-rust technique. Prevents regex memory bloat
  when users add many regex rules.
- **Files**: `wlt-core/engine/engine.go` (replace `[]*regexp.Regexp`
  with RegexManager)
- **Risk**: Low — internal optimization, no API change
- **Test**: Add 1000 regex rules, verify memory stays bounded

---

## Phase 13: Post-ECH + Performance (2+ sessions)

**Goal**: Prepare for a post-ECH world where SNI is encrypted, and optimize
for large blocklists (2M+ domains).

### 13a: FlatBuffers for blocklist storage
- **What**: Compile blocklists to FlatBuffer format at load time. Reduces
  memory usage by ~75% for large blocklists.
- **Why**: Brave's technique. 2M domains in trie = ~50MB. With FlatBuffers,
  ~12MB. Critical for HaGeZi Ultimate tier.
- **Files**: `wlt-core/internal/trie/trie.go` (add FlatBuffer serialization),
  `wlt-core/filter/loader.go` (compile to FlatBuffer)
- **Risk**: High — major data structure change, needs thorough testing
- **Test**: Load 2M domains, verify memory < 20MB

### 13b: JA4+ ad SDK fingerprint database
- **What**: Build a database of known ad SDK JA4+ TLS fingerprints. When
  a TLS connection is made, compute JA4+ and check against database.
- **Why**: Post-ECH, SNI is encrypted. JA4+ identifies ad SDKs by their
  TLS stack even without SNI.
- **Challenge**: Need to collect fingerprints. Use Frida on test devices
  to capture ad SDK TLS handshakes.
- **Files**: `wlt-core/internal/ja4/ja4.go` (expand fingerprint database),
  `wlt-core/engine/engine.go` (block on JA4+ match)
- **Risk**: Medium — fingerprint collection is manual work
- **Test**: Ad SDK connects, verify JA4+ matches database, connection blocked

### 13c: TensorFlow Lite ML classifier
- **What**: On-device ML model that classifies DNS queries as ad/non-ad
  based on 28+ features (entropy, domain age, TTL, query frequency, etc.).
- **Why**: Post-ECH, traditional blocking methods weaken. ML can identify
  ad/tracker domains that aren't in any blocklist.
- **Challenge**: Need training data (labeled ad/non-ad domains). Need to
  train model offline, convert to .tflite, bundle in APK.
- **Files**: `ml/ad_detector.tflite` (new — model file),
  `vpn/AdDetector.kt` (new — TensorFlow Lite inference),
  `vpn/KotlinBlockEngine.kt` (add ML check to cascade)
- **Risk**: High — ML is complex, model accuracy is critical
- **Test**: Query novel ad domain (not in blocklist), verify ML blocks it

### 13d: Packet-size analysis (research)
- **What**: ML model that identifies ad traffic by packet size and timing
  patterns. The only viable client-side signal post-ECH.
- **Why**: When SNI is encrypted and JA4+ is insufficient, packet sizes
  are the last resort for traffic classification.
- **Status**: Research phase — not production-ready. Track academic papers.
- **Risk**: Very high — experimental, may not work reliably
- **Test**: Capture packet sizes for known ad traffic, train classifier

### 13e: Native JNI packet loop (optional)
- **What**: Port the Kotlin packet loop to C via JNI for 2-5x performance.
- **Why**: NetGuard's approach. Faster packet processing, lower battery.
- **Status**: Only worth it if performance becomes a bottleneck. WLT's
  Kotlin loop is "good enough" for DNS blocking.
- **Risk**: High — major rewrite, JNI complexity
- **Test**: Benchmark packets/second before and after

### 13f: PCAP export
- **What**: Export captured network traffic as .pcap file for analysis in
  Wireshark.
- **Why**: PCAPdroid's feature. Power users and security researchers want
  raw packet capture.
- **Files**: `data/PcapExporter.kt` (new), `ui/screens/SettingsScreen.kt`
  (export button)
- **Risk**: Low — write packets to file in pcap format
- **Test**: Capture 100 packets, export, open in Wireshark

### 13g: Root mode (optional)
- **What**: For rooted devices, modify /etc/hosts directly instead of using
  VpnService. Faster, less battery, system-wide.
- **Why**: AdAway's approach. Root mode is more efficient than VPN.
- **Status**: Optional — only for rooted users. Most users don't have root.
- **Files**: `vpn/RootMode.kt` (new — write to /etc/hosts via root shell)
- **Risk**: Medium — root access, but only for users who opt in
- **Test**: Enable root mode, verify /etc/hosts is updated

---

## Priority Summary

| Phase | Name | Sessions | Impact | Risk | Dependencies |
|---|---|---|---|---|---|
| **7** | Wire Existing Engines | 1 | High | Low | None |
| **8** | DNS Infrastructure | 1 | High | Low | None |
| **9** | Per-App Intelligence | 1-2 | Very High | Medium | 7b (IPv6) |
| **10** | User Power Features | 1 | Medium | Low | 7a (regex), 7c (ruleparser) |
| **11** | WireGuard + Tunnel | 2 | High | High | None |
| **12** | Advanced Filtering | 1 | Medium | Low | None |
| **13** | Post-ECH + Perf | 2+ | Future | High | 7f (JA4+), 13a (FlatBuffers) |

## Recommended Execution Order

1. **Phase 7** (wire existing) — immediate ROI, code already exists
2. **Phase 8** (DNS infrastructure) — prevents bypass, improves performance
3. **Phase 9** (per-app intelligence) — biggest user-visible improvement
4. **Phase 10** (user power) — builds on 7a and 7c
5. **Phase 12** (advanced filtering) — uBO parity, low risk
6. **Phase 11** (WireGuard) — high value but high risk, do after stable base
7. **Phase 13** (post-ECH) — future-proofing, research-heavy

## What This Roadmap Does NOT Do

- **Doesn't chase YouTube/Spotify/TikTok app ads** — These use SSAI + cert
  pinning. VPN-based blocking is fundamentally limited. WLT already has
  honest messaging about this (ReVancedIntegration.kt detects and recommends
  alternative clients).
- **Doesn't add cloud sync** — WLT is privacy-first. No accounts, no cloud.
- **Doesn't add ML for ML's sake** — ML (Phase 13c) is only for post-ECH
  preparation, where it's actually necessary.
- **Doesn't rewrite in Rust** — Go + gomobile is working well. Rust rewrite
  would take months for marginal benefit.
- **Doesn't chase Play Store distribution** — WLT uses QUERY_ALL_PACKAGES
  (forbidden on Play Store). F-Droid + direct APK distribution is the path.
