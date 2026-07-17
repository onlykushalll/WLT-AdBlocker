# WLT-Adblocker — Unique Features No Other Blocker Has

*Features to build that NO existing open-source adblocker combines — WLT's competitive moat.*

---

## The Gap in the Market

After cataloguing **300+ features** across 35+ adblockers, the market gap is clear:

| What exists | What's missing |
|-------------|----------------|
| DNS blockers (Blokada, HostShield) | + HTTPS filtering + scriptlets in ONE free OSS app |
| HTTPS filterers (BlockAds) | + CNAME uncloaking + 50 list gallery + tracker SDK scanner |
| Firewalls (Rethink, NetGuard) | + Full ad filter engine (not just DNS denylist) |
| Browser blockers (uBlock Origin) | + System-wide game/app blocking on mobile |
| Cloud DNS (NextDNS) | + 100% on-device, zero cloud, zero account |

**WLT-Adblocker = the union of the best features, plus novel ones nobody has shipped.**

---

## Tier 1: WLT Exclusive Features (Nobody Has These Combined)

### 1. Adaptive Multi-Layer Engine ("Smart Cascade")

**What:** Automatically escalate blocking layers based on what failed.

```
Layer 1 DNS block → failed? → Layer 2 SNI block → failed? → Layer 3 URL block → failed? → Layer 4 Scriptlet
```

**Why unique:** Every existing blocker picks ONE layer and sticks with it. WLT tries the cheapest layer first and escalates only when needed — saving battery while maximizing block rate.

**Inspired by:** HostShield DNS + BlockAds HTTPS + uBlock scriptlets, but with automatic escalation logic.

---

### 2. Game Ad Intelligence Engine

**What:** Dedicated real-time engine for mobile game ad SDKs that goes beyond static domain lists:

- **SDK fingerprinting:** Detect AdMob, Unity, AppLovin, ironSource, Chartboost, Vungle, Meta Audience Network by connection patterns
- **Hardcoded IP database:** Block known ad server IPs that bypass DNS (updated weekly)
- **Rewarded ad skip:** Detect rewarded video ad requests and return empty response (game continues without crash)
- **Interstitial null response:** Return valid-but-empty ad response so games don't show error screens
- **Per-game profiles:** Learn which domains each installed game uses, suggest custom rules

**Why unique:** No blocker maintains a dedicated game SDK engine. AdGuard partially blocks game ads via DNS but doesn't handle IP bypass or graceful ad failure.

**Data source:** WLT-maintained `wlt-game-ads.txt` + `wlt-game-ips.txt` + community submissions.

---

### 3. "Ad Forensics" — Explain Why an Ad Got Through

**What:** When user reports "I still see an ad", WLT analyzes the connection and shows:

```
❌ Ad detected from: applovin.com
   Layer 1 DNS: MISSED (hardcoded IP 34.120.x.x used, no DNS query)
   Layer 2 SNI: BLOCKED ✓ (would have worked if enabled)
   Layer 3 HTTPS: N/A (cert-pinned app)
   
   Recommendation: Enable SNI inspection for this app
   [Enable Now] [Add IP to blocklist] [Report to WLT]
```

**Why unique:** No adblocker explains its own failures. Users blame the blocker; WLT tells them exactly why and how to fix it.

---

### 4. Zero-Trust Passthrough System

**What:** Instead of a static banking list (BlockAds: 284 domains), WLT uses:

- **Community-verified passthrough list** (GitHub PRs, signed updates)
- **Automatic EV cert detection** — never MITM sites with Extended Validation certs
- **User override with biometric lock** — "I trust this app for MITM" requires fingerprint
- **Breakage reporter** — if a site breaks, one-tap adds to passthrough + reports upstream

**Why unique:** Static passthrough lists go stale. WLT's is community-maintained with cryptographic verification.

---

### 5. Blocklist Impact Simulator

**What:** Before enabling a blocklist, show exactly what would change:

```
Enabling "HaGeZi Pro++" would:
  + Block 47,000 new domains
  + Change 23 domains you queried in last 7 days
  ⚠️ May break: your-bank.com (1 query), work-vpn.com (3 queries)
  
  [Preview Changes] [Enable Anyway] [Enable + Auto-Allowlist Breakage]
```

**Inspired by:** HostShield's "Source Impact Preview" but extended to ALL list operations with rollback.

---

### 6. YouTube Web Scriptlet Pipeline (Auto-Update)

**What:** Dedicated auto-updating scriptlet pack for YouTube web (not app):

- `m3u-prune` for ad segments in HLS streams
- `json-prune` for YouTube API ad metadata
- Auto-pull from uBlock Origin + AdGuard YouTube filters daily
- "YouTube mode" toggle in quick settings

**Why unique:** BlockAds has scriptlets but no dedicated YouTube pipeline. uBlock Origin has the filters but only in browser extension form.

**Honest limit:** Still won't block YouTube **app** ads (architectural). WLT documents this clearly and links to ReVanced/NewPipe.

---

## Tier 2: Best-in-Class Features (Rare, WLT Will Ship Standard)

These exist in 1–2 products but should be **default** in WLT:

| # | Feature | Currently Best At | WLT Default? |
|---|---------|-----------------|--------------|
| 1 | CNAME + SVCB/HTTPS cloaking detection | HostShield | ✅ Phase 2 |
| 2 | Bloom + Trie + HashSet triple lookup | HostShield | ✅ Phase 1 |
| 3 | 50+ blocklist gallery with tier warnings | HostShield | ✅ Phase 2 |
| 4 | Tracker SDK scanner (405 signatures) | HostShield | ✅ Phase 3 |
| 5 | App privacy report (A-F grade) | HostShield | ✅ Phase 3 |
| 6 | HTTPS filtering + scriptlets on Android | BlockAds | ✅ Phase 3 |
| 7 | Per-app WireGuard split-tunnel | Rethink | ✅ Phase 4 |
| 8 | Per-app firewall with event rules | Rethink | ✅ Phase 2 |
| 9 | gVisor netstack for TCP/IP | Rethink/firestack | ✅ Phase 3 |
| 10 | m3u-prune YouTube web scriptlets | uBlock Origin | ✅ Phase 3 |
| 11 | 80+ scriptlet library | uBlock Origin | ✅ Phase 3 |
| 12 | DoH bypass prevention (65+ providers) | HostShield | ✅ Phase 2 |
| 13 | Serve-stale DNS (RFC 8767) | HostShield | ✅ Phase 2 |
| 14 | Fail-closed DoH cert pinning | HostShield | ✅ Phase 1 |
| 15 | SNI inspection without MITM | NetGuard/BlockAds | ✅ Phase 2 |
| 16 | Game SDK domain blocklist | WLT custom | ✅ Phase 1 |
| 17 | Automation API (Tasker/MacroDroid) | HostShield | ✅ Phase 3 |
| 18 | Evidence JSONL export | HostShield | ✅ Phase 4 |
| 19 | Query anomaly detection | HostShield | ✅ Phase 3 |
| 20 | Native tracking protection (OEM telemetry) | NextDNS | ✅ Phase 2 |

---

## Tier 3: Future/Innovation Features (Research-Stage)

Features from academic research or experimental projects — WLT roadmap Phase 5+:

### 7. On-Device ML Ad Classifier (Optional, Off by Default)

**Source:** [koshiq/LLM-Based-AdBlocker](https://github.com/koshiq/LLM-Based-AdBlocker), [dnsXai](https://github.com/hookprobe/hookprobe)

- Lightweight neural classifier (20 domain features: entropy, n-gram, keyword patterns)
- Classifies unknown domains into: Legitimate, Advertising, Tracking, Analytics, Malware, Cryptominer
- Runs ONLY on-device, no cloud inference
- Falls back to "allow + learn" for unknown domains, caches decision in Trie
- User can disable entirely (pure blocklist mode)

**Privacy:** No data leaves device. Model weights updated via signed blocklist-style updates.

---

### 8. Anti-Anti-Adblock Engine

**What:** Dedicated filter pack + scriptlets that detect and neutralize anti-adblock messages:

- Detect "disable adblock" overlays
- Auto-click "continue without supporting us" buttons
- Block anti-adblock JavaScript (abort-on-property-read patterns)
- Subscribe to uBlock's `uBlock filters – Anti-adblock` list

**Exists in:** uBlock Origin (browser only). WLT brings to Android HTTPS filtering mode.

---

### 9. Sponsored Content Detector (First-Party Ads)

**What:** For DNS/HTTPS layers, detect likely sponsored content domains:

- Monitor apps that load from same domain as content (Instagram, Twitter/X, Reddit)
- Flag (not block) sponsored post domains in connection log
- Future: ML content classifier for "this looks like an ad" on same-domain requests

**Honest limit:** Cannot block without breaking the app. WLT flags and reports instead.

---

### 10. Cross-Device Sync (Local Network, No Cloud)

**What:** Sync WLT settings/blocklists across your devices on same WiFi:

- mDNS discovery of other WLT instances on LAN
- Encrypted sync of: custom rules, allowlists, profiles
- No cloud server, no account, no internet required for sync
- QR code pairing for initial setup

**Why unique:** NextDNS syncs via cloud (needs account). WLT syncs peer-to-peer on LAN only.

---

### 11. ReVanced Integration Mode

**What:** Instead of pretending to block YouTube app ads, WLT integrates with ReVanced:

- Detect if ReVanced/NewPipe installed
- Offer "YouTube Protection" mode that routes YouTube app through ReVanced
- Maintain compatibility docs and patch status checker
- WLT DNS still blocks YouTube tracker domains

**Why unique:** No adblocker acknowledges the ReVanced ecosystem. WLT embraces it.

---

### 12. Community Threat Feed (Optional, Privacy-Preserving)

**What:** WLT users can optionally contribute anonymized data:

- Submit SHA-256 hashes of newly blocked ad domains (not URLs, not queries)
- WLT maintainers aggregate into `wlt-community-blocklist.txt`
- Users who contribute get early access to community list
- Differential privacy: minimum 50 users must report same domain before adding

**Inspired by:** dnsXai federated learning, but simpler and opt-in only.

---

## WLT Feature Priority Matrix

| Phase | Features | Unique to WLT | Block Rate Target |
|-------|----------|---------------|-------------------|
| **Phase 1** (MVP) | DNS engine, game SDK list, Bloom+Trie, DoH pinning, basic UI | Game Ad Intelligence, Ad Forensics v1 | Games: 90%, Apps: 85% |
| **Phase 2** | CNAME cloaking, SNI, per-app firewall, 50 list gallery, DoH bypass prevention | Smart Cascade v1, Impact Simulator | Games: 93%, Apps: 90% |
| **Phase 3** | HTTPS MITM, scriptlets, cosmetic CSS, tracker SDK scanner, YouTube pipeline | Anti-Anti-Adblock, Ad Forensics v2 | Browser: 95%, YT web: 80% |
| **Phase 4** | WireGuard split-tunnel, root mode, automation API, LAN sync | Cross-Device Sync, ReVanced mode | Full stack: 95%+ |
| **Phase 5** | On-device ML classifier, community feed, sponsored content detector | ML classifier, Community Feed | Unknown domains: 85% |

---

## What WLT Will NOT Promise

Being honest builds trust. WLT will **never** claim to block:

1. YouTube app ads (same-domain SSAI — use ReVanced)
2. Instagram/Facebook in-feed sponsored posts (first-party, same domain)
3. Cert-pinned app ads where SDK uses hardcoded IPs AND encrypted non-SNI connections
4. Ads embedded in offline/cached game content
5. "Un-bypassable" anything — we track bypass attempts and update, but it's a cat-and-mouse game

WLT's UI will have a **"Protection Limits"** section explaining these honestly, with workarounds where they exist.

---

## Competitive Positioning

```
                    FEATURE COMPLETENESS
                    ▲
                    │
    WLT-Adblocker ★ │                    ← Target (Phase 4)
                    │              AdGuard (paid)
                    │         Rethink + uBO combined
                    │    HostShield    BlockAds
                    │  Blokada    Pi-hole
                    │ AdAway  DNS66
                    ▼──────────────────────────────►
                    FREE                              PAID
                    
                    100% ON-DEVICE / OPEN SOURCE
```

**WLT's pitch:** "Everything AdGuard Premium does for blocking, minus the subscription, minus the cloud, minus the trust-me-bro — plus game ad intelligence no one else has."
