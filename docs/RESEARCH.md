# WLT-Adblocker — Deep Research Report

> Research date: July 2026  
> Goal: Understand adblocking at every layer so WLT-Adblocker can combine the best of all worlds.

---

## 1. How Adblocking Actually Works

Every ad blocker is a **rule interpreter**. Filter lists (EasyList, AdGuard, StevenBlack, etc.) contain tens of thousands of rules in a special syntax. The blocker parses these rules into efficient data structures and matches them against network requests, DNS queries, or DOM elements.

### 1.1 The Four Filtering Layers

| Layer | What it blocks | Where it runs | Strength | Weakness |
|-------|---------------|---------------|----------|----------|
| **DNS filtering** | Ad/tracker domains | Network stack (VPN, router, Pi-hole) | System-wide, all apps, low battery | Cannot block same-domain ads (YouTube, Instagram) |
| **Network/URL filtering** | Specific URLs, paths, parameters | Proxy, browser extension, MITM | Blocks requests before they load | Needs visibility into HTTPS (MITM or browser API) |
| **Cosmetic filtering** | DOM elements (banners, placeholders) | Browser content script | Hides leftover ad containers | Browser-only, post-load |
| **Scriptlet injection** | Anti-adblock scripts, inline ads | Browser at `document_start` | Defeats anti-adblock, YouTube web tricks | Browser-only, complex to maintain |

**Key insight:** No single layer blocks everything. Production-grade blocking requires **stacking layers**.

### 1.2 Filter List Ecosystem

| List | Maintainer | Rules (~) | Focus |
|------|-----------|-----------|-------|
| [EasyList](https://easylist.to/) | Community volunteers | 70K+ | General web ads |
| [EasyPrivacy](https://easylist.to/) | Community | 40K+ | Trackers |
| [AdGuard Base](https://github.com/AdguardTeam/AdguardFilters) | AdGuard team | 50K+ | Ads + annoyances |
| [StevenBlack hosts](https://github.com/StevenBlack/hosts) | Steven Black | 150K+ | DNS hosts format |
| [OISD](https://oisd.nl/) | Stephan van Rhijn | 200K+ | Unified DNS blocklist |
| [AdGuard CNAME trackers](https://github.com/AdguardTeam/cname-trackers) | AdGuard | 5K+ | CNAME cloaking |
| [uBlock filters](https://github.com/uBlockOrigin/uAssets) | uBlock team | 30K+ | Scriptlets, redirects |
| [Fanboy Annoyances](https://www.fanboy.co.nz/) | Fanboy | 20K+ | Cookie notices, social widgets |

Filter syntax standards:
- **Adblock Plus syntax** — de facto standard (`||domain.com^`, `##.selector`)
- **AdGuard syntax** — extensions (`#%#//scriptlet(...)`, `$redirect=`)
- **Hosts format** — `0.0.0.0 ads.example.com` (DNS-only)

### 1.3 Filter Matching Engines (Open Source)

| Engine | Language | Repo | Used by |
|--------|----------|------|---------|
| **urlfilter** | Go | [AdguardTeam/urlfilter](https://github.com/AdguardTeam/urlfilter) | AdGuard Home, BlockAds |
| **tsurlfilter** | TypeScript | [AdguardTeam/tsurlfilter](https://github.com/AdguardTeam/tsurlfilter) | AdGuard browser extension |
| **uBO static-net-filtering** | JavaScript | [gorhill/uBlock](https://github.com/gorhill/uBlock) | uBlock Origin |
| **Domain Trie** | Kotlin/Go | Various | DNS blockers (NetGuard, BlockAds) |

Data structures used:
- **Trie / Radix tree** — O(domain length) DNS lookups
- **Bloom filters** — fast negative lookups
- **Typed-array hostname DB** — uBlock's memory-efficient cosmetic filter storage
- **Token-based URL matching** — hash URL parts for network rules

---

## 2. Why Your Current Setup Fails

### 2.1 AdGuard on Mobile — Specific Gaps

AdGuard mobile uses primarily **DNS filtering** in its free/VPN mode. This explains your experience:

| Scenario | AdGuard behavior | Why |
|----------|-----------------|-----|
| Game ads (AdMob, Unity) | **Partial** — blocks if SDK uses known ad domains | Works for ~70% of games; some SDKs use hardcoded IPs |
| YouTube app ads | **Fails** | Ads served from same `googlevideo.com` / `youtube.com` as video |
| YouTube in browser | **Works** (with extension/filtering) | Can apply URL + cosmetic + scriptlet rules |
| In-app browser ads | **Partial** | Depends on whether HTTPS filtering is enabled |

### 2.2 The YouTube Problem (Architectural, Not Fixable by DNS Alone)

YouTube ads are the hardest case in adblocking:

1. **Same-domain delivery** — Video and ads come from `*.googlevideo.com` with dynamically generated subdomains per session
2. **Server-side ad insertion (SSAI)** — Ads are stitched into the video stream on Google's servers before reaching your device
3. **UMP protocol** — Mobile apps bundle video metadata, ad metadata, and content in unified requests
4. **No separate ad URL** — There is nothing to intercept at DNS or even URL level inside the native app

**What actually works for YouTube:**
- Browser extensions with scriptlets (uBlock Origin, AdGuard extension) — on **desktop web only**
- Modified YouTube clients (ReVanced, NewPipe) — separate apps, not adblockers
- YouTube Premium — Google's own paywall

**Honest assessment:** WLT-Adblocker cannot promise 100% YouTube in-app ad blocking without either (a) a modified YouTube client, or (b) stream-level analysis/reconstruction (extremely complex, legally gray).

### 2.3 Game Ads — Much More Blockable

Mobile game ads use third-party SDK domains that **can** be DNS-blocked:

| SDK | Domains to block |
|-----|-----------------|
| Google AdMob | `googleads.g.doubleclick.net`, `pagead2.googlesyndication.com`, `admob.com` |
| Unity Ads | `unityads.unity3d.com`, `config.unityads.unity3d.com` |
| AppLovin | `applovin.com`, `ms.applovin.com` |
| ironSource | `ironsrc.com`, `supersonicads.com` |
| Chartboost | `chartboost.com` |
| Vungle | `vungle.com` |
| Meta Audience Network | `facebook.com/ads`, `an.facebook.com` |

**Bypass techniques games use:**
- Hardcoded IP addresses (skip DNS entirely) → need IP blocklists or connection-level blocking
- Certificate pinning on ad SDK connections → need root or per-app bypass
- In-app fallback ads (cached locally) → cosmetic layer can't help; DNS still works on refresh

**Expected game ad block rate with good DNS + SNI filtering: 85–95%**

---

## 3. Adblocker Bypass Techniques (What We Must Counter)

| Technique | Description | Countermeasure |
|-----------|-------------|----------------|
| **CNAME cloaking** | `track.yoursite.com` → CNAME → `tracker.com` | Resolve full CNAME chain before blocking (HostShield, Brave, AdGuard) |
| **Hardcoded IPs** | SDK connects directly to IP, no DNS lookup | IP blocklists, SNI inspection on TLS ClientHello |
| **DoH/DoT bypass** | App uses its own encrypted DNS to evade system DNS | Force all DNS through local resolver; block known DoH endpoints |
| **ECH (Encrypted Client Hello)** | Hides SNI in TLS handshake | Limited counter; monitor outer SNI patterns |
| **First-party ads** | Ads served from same domain as content | Requires URL/content inspection (HTTPS MITM or browser API) |
| **SSAI** | Ads embedded in video stream server-side | Stream analysis or client modification only |
| **Anti-adblock scripts** | Detect blocked requests, show "disable adblock" message | Scriptlet injection (`##+js(...)`) |
| **Manifest V3** | Chrome limits extension network interception | Use declarativeNetRequest + scriptlets (reduced capability) |
| **Cert pinning** | App pins TLS cert, rejects MITM | Cannot MITM pinned apps; rely on DNS layer only |

---

## 4. Technical Components We Need

### 4.1 Core Engine (Go — shared across platforms)

```
wlt-core/
├── filter/          # Rule parser (ABP + AdGuard syntax)
├── dns/             # DNS engine (Trie + CNAME chain resolution)
├── net/             # Network/URL engine (urlfilter wrapper)
├── scriptlet/       # Scriptlet registry + injection templates
└── blocklist/       # List downloader, compiler, updater
```

**Recommended base:** [AdguardTeam/urlfilter](https://github.com/AdguardTeam/urlfilter) (Go, GPL-3.0, actively maintained, has DNSEngine + NetworkEngine)

### 4.2 Android VPN Service

Uses Android `VpnService` API — no root required:

```
Phone → VpnService (local TUN) → DNS parser → Block check → Forward/Block
                                      ↓
                              (optional) tun2socks → HTTPS proxy → URL filter
```

Key libraries:
- **gVisor netstack** — userspace TCP/IP stack ([used by RethinkDNS/firestack](https://github.com/celzero/firestack))
- **tun2socks** — TUN to SOCKS bridge
- **dnsjava** or custom RFC 1035 parser

Reference implementations:
- [celzero/rethink-app](https://github.com/celzero/rethink-app) — most mature open-source Android DNS firewall
- [pass-with-high-score/blockads-android](https://github.com/pass-with-high-score/blockads-android) — HTTPS filtering + scriptlets on Android
- [SysAdminDoc/HostShield](https://github.com/SysAdminDoc/HostShield) — CNAME cloaking, 50+ blocklists

### 4.3 DNS Packet Flow

```
App sends DNS query for "ads.doubleclick.net"
    ↓
VpnService intercepts UDP port 53 packet
    ↓
Parse IP header → UDP header → DNS query (RFC 1035)
    ↓
Extract domain name → Normalize (lowercase, strip trailing dot)
    ↓
Check DomainTrie / DNSEngine
    ↓
MATCH  → Return NXDOMAIN or 0.0.0.0 (instant, no upstream query)
NO MATCH → Forward to upstream DNS (DoH/DoT/UDP) → Return real response
    ↓
If CNAME in response → Resolve chain → Re-check each hop against blocklist
```

### 4.4 HTTPS Filtering (Advanced Layer)

Only for **non-pinned** apps (browsers mainly):

```
Browser TCP connection → tun2socks → Local HTTPS proxy
    ↓
TLS ClientHello → Extract SNI → Check URL rules
    ↓
If match + user opted in → MITM with locally generated CA cert
    ↓
Inspect HTTP request URL → NetworkEngine match
    ↓
For HTML responses → Inject cosmetic CSS + scriptlets
    ↓
Return modified response to browser
```

**Critical:** Must maintain passthrough list for banking, payments, gov apps (BlockAds uses 284 curated domains).

---

## 5. What "Production Grade" Actually Means

Realistic targets for WLT-Adblocker:

| Metric | Target | Notes |
|--------|--------|-------|
| Game ad block rate | **90%+** | DNS + SNI + SDK domain lists |
| In-app ad block rate (non-YouTube) | **85%+** | Third-party ad networks |
| Browser web ad block rate | **95%+** | DNS + HTTPS filtering |
| YouTube web (browser) | **80%+** | Scriptlets; cat-and-mouse with Google |
| YouTube native app | **Limited** | Same-domain limitation; consider ReVanced integration docs |
| Tracker block rate | **95%+** | EasyPrivacy + AdGuard Tracking Protection |
| Battery impact | **< 5%** extra drain | DNS-only mode; full proxy optional |
| Memory | **< 80MB** | Compiled filter lists in Trie/Bloom |
| Startup time | **< 2s** | Pre-compiled blocklists on disk |
| False positive rate | **< 0.1%** | Allowlist + passthrough list |

**"Un-bypassable" is not achievable.** Google, Meta, and ad networks employ full-time engineers to evade blockers. WLT-Adblocker's goal is to be **the hardest to bypass among open-source options** through multi-layer defense and rapid filter updates.

---

## 6. Legal & Distribution Notes

- Ad blocking is legal in most jurisdictions for personal use
- Google Play **removed AdAway** for violating Developer Distribution Agreement section 4.4
- Distribution: F-Droid, GitHub Releases, direct APK — not Google Play (unless using "privacy tool" framing carefully)
- GPL-3.0 license required if using AdGuard urlfilter
- HTTPS MITM requires clear user consent and CA cert installation

---

## 7. Key Sources

- [AdGuard: How ad blocking works](https://adguard.com/kb/general/ad-filtering/how-ad-blocking-works/)
- [0x65.dev: Not all adblockers are born equal](https://0x65.dev/blog/2019-12-20/not-all-adblockers-are-born-equal.html)
- [AdGuard: YouTube server-side ad insertion](https://adguard.com/en/blog/youtube-server-side-ad-insertion.html)
- [Casper's Cloak: DNS filtering limitations](https://casperscloak.com/blog/how-dns-level-filtering-actually-works)
- [Brave: Fighting CNAME trickery](https://brave.com/privacy-updates/6-cname-trickery/)
- [Pi-hole architecture](https://github.com/pi-hole/pi-hole)
- [Rethink DNS + Firewall](https://github.com/celzero/rethink-app)
