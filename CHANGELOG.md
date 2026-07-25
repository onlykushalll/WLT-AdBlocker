# Changelog

All notable changes to WLT-AdBlocker will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] — 2026-07-25

### Phase 13 — Post-ECH + Performance
- JA4+ ad SDK fingerprint database (GetSDKName, AddAdSDKFingerprintWithName, AllKnownFingerprints)
- PCAP export (PcapExporter.kt — capture packets as .pcap for Wireshark)
- Root mode (RootMode.kt — optional /etc/hosts modification for rooted devices)

### Phase 12 — Advanced Filtering
- 9 new uBlock Origin scriptlets (71 → 80 total)
  - trusted-replace-node-text, trusted-set-constant/cookie/localStorage/sessionStorage
  - trusted-prune-inbound/outbound-object, json-prune-fetch/xhr-response
- :remove-attr + :style procedural cosmetic filters
- 8 noop redirect resources (1x1.gif, 2x2.png, noop.js/css/html/json/txt/vast.xml)
- $dnstype modifier in ruleparser ($dnstype=A, $dnstype=~AAAA)
- RegexManager — LRU-bounded regex cache (max 1000, ~1MB)

### Phase 11 — WireGuard + Encrypted Tunnel
- WireGuard config parser (internal/wireguard — parse .conf files, validate keys)
- Tunnel manager (state, data usage, connect/disconnect)
- WireGuardScreen.kt — import .conf, connect/disconnect, split tunneling info
- Exposed via gomobile: NewWGTunnel, ParseWGConfig, WGTunnelUp/Down/State/RxBytes/TxBytes

### Phase 10 — User Power Features
- Regex rules UI with 6 presets (RegexRulesScreen.kt)
- 5 protection levels (Light/Normal/Pro/Pro++/Ultimate) with HaGeZi/OISD source URLs
- Battery optimization screen with OEM-specific instructions (Samsung/Xiaomi/Huawei/Oppo/OnePlus/Asus)
- CSV export of query log + blocked summary

### Phase 9 — Per-App Intelligence
- IP→domain reverse lookup cache (DomainIpCache.kt — LRU 5K entries, 5min TTL)
- Per-app network statistics (AppNetworkStats.kt — per-UID queries/blocked/trackers/domains)
- App analytics screen (AppAnalyticsScreen.kt — tracker breakdown, block rate)
- Domain categorization (BlockCategory.kt — 12 categories with fromBlocklist/fromDomain heuristics)
- Blocked services (BlockedServices.kt — 13 services with one-click toggle)
- Blocked services screen (BlockedServicesScreen.kt)
- extractAnswerIps() in DnsPacketParser for A/AAAA record IP extraction

### Phase 8 — DNS Infrastructure
- DNS response cache (DnsCache.kt — LRU 10K entries, ~70% query reduction, <1ms cache hits)
- Block DoT port 853 (UDP+TCP) — prevents DNS bypass via DNS-over-TLS
- QUIC blocking toggle (block UDP 443, opt-in) — forces TCP fallback
- ECH config blocking (cloudflare-ech.com + crypto.cloudflare.com)
- Added parseTcp() to PacketIo.kt for TCP port inspection

### Phase 7 — Wire Existing Engines
- 7a: AddRegex(pattern) + RegexCount() via gomobile (Pi-hole regex matching)
- 7b: DecisionNullIPv6 (AAAA ::) + DecisionNODATA (AdGuard response types)
- 7c: ParseRule(line) + AddParsedRule(rule) for ABP/uBlock syntax support
- 7d: preparser wired to blocklist loading (!#if ext_wlt, env_android, cap_dns_blocking, cap_mitm)
- 7e: DomainAge async RDAP checker + dynamicBlocks trie in CheckDNS cascade
- 7f: JA4+ TLS fingerprinting: CheckSNIWithJA4, GetJA4, AddAdSDKFingerprint (post-ECH prep)

### Phase 6 — Expanded Blocking
- Expanded blocklist from 697 to 888 domains (+27%)
- 15 new scriptlets (56 → 71): de-amp, prevent-webgl, prevent-audio-fingerprint, etc.
- Regex support in Go engine (Pi-hole technique)
- Expanded tracking param stripping (16 → 45+ parameters)
- AAAA null IP (IPv6) support — BuildNullIPv6()
- NODATA response type — BuildNODATA()
- DoH bypass prevention expanded (9 → 30+ domains)

### Phase 5 — Security Hardening + Safe Search
- Fixed all 5 critical/high security audit findings (C1-C3, H1-H2)
- Safe Search DNS rewriting (25 CNAME rules: Google, Bing, DuckDuckGo, YouTube, Pixabay)
- DGA detection wired into block cascade
- Configurable block response type (NXDOMAIN / NullIP / REFUSED)
- Light theme, 19 permissions

### Phase 4 — Advanced Filtering
- JA4+ TLS fingerprinting package
- DGA detection package
- SponsorBlock API client (12 categories)
- DNS rewrite engine (NXDOMAIN, NullIP, REFUSED, CustomIP, CNAME)
- Domain age checker via RDAP
- ABP/uBlock rule parser
- Pre-parsing directives processor

### Phase 3 — HTTPS MITM
- MITM CA cert generation (ECDSA P-256, per-leaf keypair)
- HTTPS MITM proxy with m3u-prune, scriptlet injection, tracking param stripping
- MITM allowlist + relayRaw for non-opt-in domains (privacy-safe)
- Bounded goroutine pool (64 sem) + 10MB LimitReader
- Cosmetic filtering engine (CSS injection + 9 procedural filters)
- 49 scriptlets + HLS ad segment stripper (SCTE-35)

### Phase 2 — SNI Inspection
- TLS ClientHello SNI extractor
- TCP connection tracking + SNI inspection
- Streaming TLS ClientHello parser (handles split TCP segments)

### Phase 1 — DNS Blocking
- Go core engine with trie, bloom filter, game SDK detection
- Android VpnService for DNS interception
- DoH (RFC 8484) upstream with UDP fallback
- CNAME cloaking detection
- DoH bypass prevention
- 10 blocklists, 49 scriptlets, 12 game SDK fingerprints
- 10 Compose screens
- Per-app firewall, per-UID tracking, pause protection, quick settings tile
- WorkManager 24h auto-update
- Settings export/import (JSON)

### Final Statistics (v1.0.0)
- 23 Go packages, 150+ tests passing
- 80 scriptlets
- 888 blocklist domains
- 56 Kotlin files
- 15 Compose screens
- 9 procedural cosmetic filters
- 8 noop redirect resources
- 13 blocked services
- 12 domain categories
- 5 protection levels
- APK: 68.5 MB
