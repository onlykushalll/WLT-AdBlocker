# WLT-Adblocker — Architecture & Build Plan

---

## 1. System Overview

WLT-Adblocker uses a **layered defense model**. Each layer catches what the previous one misses.

```
┌─────────────────────────────────────────────────────────────┐
│                        YOUR PHONE                           │
│                                                             │
│  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌─────────────┐  │
│  │  Games  │  │  Apps   │  │ Browser  │  │ YouTube App │  │
│  └────┬────┘  └────┬────┘  └────┬─────┘  └──────┬──────┘  │
│       │            │            │               │         │
│       └────────────┴────────────┴───────────────┘         │
│                          │                                 │
│              ┌───────────▼───────────┐                     │
│              │   WLT VpnService      │  Layer 1: DNS       │
│              │   (all apps, always)  │  Blocks 85-90%      │
│              └───────────┬───────────┘                     │
│                          │                                 │
│              ┌───────────▼───────────┐                     │
│              │   WLT Connection Filter │  Layer 2: SNI/IP   │
│              │   (TLS ClientHello)     │  Blocks 5-8% more  │
│              └───────────┬───────────┘                     │
│                          │                                 │
│              ┌───────────▼───────────┐                     │
│              │   WLT HTTPS Proxy     │  Layer 3: URL       │
│              │   (browsers only)     │  Blocks 3-5% more   │
│              └───────────┬───────────┘                     │
│                          │                                 │
│              ┌───────────▼───────────┐                     │
│              │   WLT Scriptlet Engine│  Layer 4: DOM       │
│              │   (browsers only)     │  Anti-adblock, YT   │
│              └───────────────────────┘                     │
│                                                             │
│  ❌ YouTube App SSAI ads — cannot be blocked at any layer  │
│     (same stream as video; see RESEARCH.md section 2.2)    │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Project Structure

```
Adblocker/
├── README.md
├── docs/
│   ├── RESEARCH.md
│   ├── ADBLOCKER-COMPARISON.md
│   └── ARCHITECTURE.md
│
├── wlt-core/                    # Go — shared filter engine
│   ├── go.mod
│   ├── filter/
│   │   ├── engine.go            # Wraps AdGuard urlfilter
│   │   ├── compiler.go          # Compile lists to binary format
│   │   └── updater.go           # Download + merge blocklists
│   ├── dns/
│   │   ├── trie.go              # Domain trie with wildcards
│   │   ├── cname.go             # CNAME chain resolver
│   │   └── resolver.go          # Upstream DoH/DoT/UDP
│   ├── net/
│   │   ├── sni.go               # TLS ClientHello SNI extractor
│   │   └── ipblock.go           # Hardcoded IP blocklist
│   └── blocklists/
│       ├── sources.json         # Default list URLs + metadata
│       ├── wlt-game-ads.txt     # Custom game SDK domains
│       └── wlt-passthrough.txt  # Never-block domains
│
├── android/                     # Kotlin + Jetpack Compose
│   ├── app/
│   │   └── src/main/
│   │       ├── java/com/wlt/adblocker/
│   │       │   ├── vpn/
│   │       │   │   ├── WltVpnService.kt
│   │       │   │   ├── DnsPacketHandler.kt
│   │       │   │   ├── ConnectionFilter.kt
│   │       │   │   └── TunInterface.kt
│   │       │   ├── proxy/       # Phase 2
│   │       │   │   ├── HttpsProxy.kt
│   │       │   │   └── ScriptletInjector.kt
│   │       │   ├── ui/
│   │       │   │   ├── MainScreen.kt
│   │       │   │   ├── StatsScreen.kt
│   │       │   │   ├── BlocklistScreen.kt
│   │       │   │   └── SettingsScreen.kt
│   │       │   ├── data/
│   │       │   │   ├── BlocklistRepository.kt
│   │       │   │   └── StatsRepository.kt
│   │       │   └── WltApplication.kt
│   │       └── jni/             # Go core via gomobile
│   └── build.gradle.kts
│
└── scripts/
    ├── update-blocklists.sh
    └── compile-filters.sh
```

---

## 3. Technology Stack

| Component | Choice | Reason |
|-----------|--------|--------|
| Filter engine | Go + [urlfilter](https://github.com/AdguardTeam/urlfilter) | Production-proven, GPL-compatible, DNSEngine built-in |
| Android UI | Kotlin + Jetpack Compose + Material 3 | Modern, matches BlockAds/HostShield quality |
| VPN | Android VpnService API | No root required, system-wide |
| TCP/IP stack | gVisor netstack (via firestack) | Phase 2 HTTPS proxy |
| Go ↔ Android | gomobile | Compile wlt-core to `.aar` |
| DNS parsing | Custom RFC 1035 (Kotlin) | NetGuard's approach — no heavy deps |
| Persistence | DataStore + Room | Preferences + query logs |
| List updates | WorkManager | Scheduled background updates |
| Build | Gradle + Go 1.22+ | Standard Android + Go toolchain |

---

## 4. Build Phases

### Phase 1 — MVP: DNS Ad Blocker (2–3 weeks)

**Goal:** Block game ads, in-app ads, trackers system-wide. No root.

Deliverables:
- [ ] `wlt-core` Go module with urlfilter DNSEngine
- [ ] Domain trie + blocklist compiler
- [ ] Default blocklists: OISD + AdGuard Mobile + WLT game ads
- [ ] Android VpnService with DNS-only interception
- [ ] RFC 1035 DNS packet parser (Kotlin)
- [ ] Basic UI: toggle on/off, stats counter, blocked domain log
- [ ] Upstream DNS: Cloudflare DoH default
- [ ] Auto blocklist update (24h schedule)

**Test targets:**
- AdMob game → ad fails to load, game continues
- Chrome browser → most ads blocked at DNS level
- WhatsApp/Instagram → trackers blocked, app works
- Banking app → passthrough list prevents breakage

### Phase 2 — Enhanced DNS (1–2 weeks)

- [ ] CNAME cloaking detection (resolve chain, re-check)
- [ ] SNI inspection on TLS ClientHello (block by SNI without full MITM)
- [ ] Hardcoded IP blocklist for SDK bypass
- [ ] Per-app bypass toggle
- [ ] Per-app stats (which app tried to load what)
- [ ] DoH/DoT upstream selection
- [ ] Allowlist/denylist user rules
- [ ] 50+ blocklist gallery (HostShield-style)

### Phase 3 — HTTPS Filtering (2–3 weeks)

- [ ] gVisor netstack integration (firestack)
- [ ] Local HTTPS proxy with user-installed CA
- [ ] URL-level network rules (NetworkEngine)
- [ ] Cosmetic CSS injection for browsers
- [ ] Scriptlet injection (YouTube web, anti-adblock)
- [ ] 284-domain passthrough list (banking/gov)
- [ ] Per-app MITM toggle (browsers only by default)

### Phase 4 — Advanced (ongoing)

- [ ] Root mode (iptables redirect, AdAway-style)
- [ ] WireGuard split-tunnel compatibility
- [ ] Connection tracker with live log
- [ ] Scheduled rules (block social media 9–5)
- [ ] Export/import settings
- [ ] F-Droid release
- [ ] YouTube web scriptlet auto-update pipeline
- [ ] ReVanced/NewPipe integration guide for YouTube app

---

## 5. DNS Packet Handler (Phase 1 Core)

```kotlin
// Simplified flow — WltVpnService.kt
class WltVpnService : VpnService() {
    private val blockEngine: BlockEngine  // Go via gomobile

    override fun onStartCommand() {
        val tun = establishVpn()  // TUN interface, DNS-only routes
        scope.launch { packetLoop(tun) }
    }

    private suspend fun packetLoop(tun: ParcelFileDescriptor) {
        val buffer = ByteBuffer.allocate(32767)
        while (isActive) {
            val length = Os.read(tun.fileDescriptor, buffer)
            val packet = buffer.array().copyOf(length)

            when (val parsed = PacketParser.parse(packet)) {
                is DnsQuery -> handleDns(parsed, tun)
                else -> forwardRaw(packet, tun)  // non-DNS passthrough
            }
        }
    }

    private fun handleDns(query: DnsQuery, tun: ParcelFileDescriptor) {
        val domain = query.question.name

        if (blockEngine.shouldBlock(domain)) {
            val response = DnsResponse.nxdomain(query)
            tun.write(response.toPacket())
            statsRepository.recordBlock(domain, query.uid)
        } else {
            val upstream = dnsResolver.forward(query)
            // CNAME check
            upstream.cnameChain.forEach { cname ->
                if (blockEngine.shouldBlock(cname)) {
                    tun.write(DnsResponse.nxdomain(query).toPacket())
                    return
                }
            }
            tun.write(upstream.toPacket())
        }
    }
}
```

---

## 6. Privacy & Trust Principles

WLT-Adblocker is designed to be **trustworthy by architecture**, not by policy:

1. **All filtering on-device** — no cloud dependency for blocking decisions
2. **No telemetry** — zero data leaves the device
3. **Open source** — every line auditable on GitHub
4. **Minimal permissions** — VPN + notification only (Phase 1)
5. **No account required** — no signup, no email
6. **Blocklist sources are public** — user can inspect every list
7. **Passthrough list is curated** — banking/gov never broken

---

## 7. Success Metrics (How We Evaluate)

After each phase, run these tests:

| Test | Pass criteria |
|------|--------------|
| AdMob test game | Interstitial ad does not appear |
| Unity Ads test game | Rewarded ad fails gracefully |
| Chrome: cnn.com | Zero display ads visible |
| Chrome: youtube.com | Pre-roll blocked or skippable immediately |
| YouTube app | Document limitation; suggest ReVanced |
| Instagram app | Sponsored posts still visible (first-party); trackers blocked |
| Banking app (Chase/etc.) | Login and transactions work |
| WhatsApp | Messages send/receive normally |
| 24h battery test | < 5% additional drain vs no VPN |
| Memory under load | < 80MB with all lists loaded |
| Cold start | VPN active within 2 seconds |

---

## 8. Next Step

**Start Phase 1 MVP** — scaffold `wlt-core` Go module + Android project with DNS-only VpnService.

Confirm before proceeding:
1. **Android only** for now? (recommended based on your use case)
2. **Phase 1 scope** — DNS blocker first, HTTPS filtering later?
3. **Device for testing** — which Android version / device do you have?
