# Famous Adblockers — Feature Comparison Matrix

> Used to extract best features for WLT-Adblocker synthesis.

---

## Legend

| Symbol | Meaning |
|--------|---------|
| ✅ | Full support |
| ⚠️ | Partial / limited |
| ❌ | Not supported |
| 💰 | Paid feature |
| 🔓 | Open source |

---

## Tier 1: Open Source (Primary Study Targets)

| Feature | uBlock Origin | AdAway | Rethink DNS | BlockAds | HostShield | Blokada 5 | Pi-hole | NetGuard |
|---------|--------------|--------|-------------|----------|------------|-----------|---------|----------|
| **Open Source** | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ | 🔓 ✅ |
| **Platform** | Browser ext | Android | Android | Android | Android | Android/iOS | Linux/router | Android |
| **Root required** | N/A | ⚠️ Optional | ❌ | ⚠️ Optional | ⚠️ Optional | ❌ | N/A | ❌ |
| **DNS filtering** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **URL/network rules** | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ⚠️ | ❌ |
| **Cosmetic filtering** | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Scriptlet injection** | ✅ | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **HTTPS MITM** | N/A | ❌ | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **CNAME uncloaking** | ✅ | ❌ | ⚠️ | ⚠️ | ✅ | ❌ | ⚠️ | ❌ |
| **SNI inspection** | N/A | ❌ | ⚠️ | ✅ | ⚠️ | ❌ | ❌ | ⚠️ |
| **Per-app firewall** | ❌ | ❌ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| **Per-app bypass** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ |
| **DoH/DoT upstream** | N/A | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **Auto list updates** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ |
| **Game ad blocking** | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅* | ✅ |
| **YouTube app ads** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **YouTube web ads** | ✅ | N/A | N/A | ⚠️ | N/A | N/A | N/A | N/A |
| **Connection logging** | ⚠️ | ❌ | ✅ | ⚠️ | ✅ | ❌ | ✅ | ✅ |
| **WireGuard VPN** | ❌ | ❌ | ✅ | ✅ | ❌ | 💰 | ❌ | ❌ |
| **No telemetry** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **GitHub stars** | 53K+ | 9K+ | 5K+ | New | New | 3K+ | 50K+ | New |

*Pi-hole blocks game ads on network level when phone uses Pi-hole as DNS.

### Repositories

| Project | Repo | License | Best Feature to Steal |
|---------|------|---------|------------------------|
| **uBlock Origin** | [gorhill/uBlock](https://github.com/gorhill/uBlock) | GPL-3.0 | Scriptlet engine, cosmetic filtering, filter compiler |
| **AdAway** | [AdAway/AdAway](https://github.com/AdAway/AdAway) | GPL-3.0 | Hosts file management, root + VPN dual mode |
| **Rethink DNS** | [celzero/rethink-app](https://github.com/celzero/rethink-app) | Apache-2.0 | Firestack (gVisor netstack), WireGuard, firewall rules |
| **BlockAds** | [pass-with-high-score/blockads-android](https://github.com/pass-with-high-score/blockads-android) | GPL-3.0 | HTTPS filtering + scriptlets on Android, passthrough list |
| **HostShield** | [SysAdminDoc/HostShield](https://github.com/SysAdminDoc/HostShield) | GPL-3.0 | CNAME cloaking DB, 50+ blocklist gallery, AMOLED UI |
| **Blokada 5** | [blokadaorg/five-android](https://github.com/blokadaorg/five-android) | MPL-2.0 | Simple UX, on-device only |
| **Pi-hole** | [pi-hole/pi-hole](https://github.com/pi-hole/pi-hole) | EUPL-1.2 | FTL DNS engine, gravity blocklist system, regex blocking |
| **NetGuard** | [iamthehimansh/NetGuard](https://github.com/iamthehimansh/NetGuard) | GPL-3.0 | Clean DNS packet parser, DomainTrie, SNI extractor |
| **AdGuard urlfilter** | [AdguardTeam/urlfilter](https://github.com/AdguardTeam/urlfilter) | GPL-3.0 | Production filter engine (Go), DNSEngine + NetworkEngine |
| **AdGuard Home** | [AdguardTeam/AdGuardHome](https://github.com/AdguardTeam/AdGuardHome) | GPL-3.0 | Full DNS server with filtering, parental controls |
| **Firestack** | [celzero/firestack](https://github.com/celzero/firestack) | Apache-2.0 | gVisor netstack wrapper for Android VPN apps |
| **serverless-dns** | [serverless-dns/serverless-dns](https://github.com/serverless-dns/serverless-dns) | MPL-2.0 | Cloud DNS resolver with blocklists (Rethink backend) |

---

## Tier 2: Commercial / Closed Source (Feature Reference)

| Feature | AdGuard (paid) | Blokada 6 | NextDNS | Brave Browser |
|---------|---------------|-----------|---------|---------------|
| **Open Source** | ⚠️ Partial | ⚠️ Client only | ❌ | 🔓 ✅ |
| **DNS filtering** | ✅ | ✅ | ✅ | ✅ |
| **HTTPS filtering** | 💰 ✅ | ❌ | ❌ | ✅ (built-in) |
| **Cosmetic filtering** | 💰 ✅ | ❌ | ❌ | ✅ |
| **Scriptlets** | 💰 ✅ | ❌ | ❌ | ⚠️ |
| **CNAME uncloaking** | ✅ | ⚠️ | ✅ | ✅ |
| **Game ads** | ⚠️ | ⚠️ | ⚠️ | ❌ (browser only) |
| **YouTube app** | ❌ | ❌ | ❌ | ❌ |
| **YouTube web** | ✅ | N/A | N/A | ✅ |
| **Per-app control** | 💰 ✅ | ❌ | ⚠️ | ❌ |
| **Price** | ~$30/yr | ~$60/yr | Free tier + $20/yr | Free |

---

## Tier 3: Specialized / Niche

| Project | Focus | WLT Takeaway |
|---------|-------|-------------|
| **ReVanced** | YouTube client patching | Document as optional companion for YouTube app |
| **NewPipe** | YouTube alternative client | Same — not an adblocker but solves YouTube app problem |
| **DNS66** | Simple Android DNS blocker | Good minimal architecture reference |
| **personalDNSfilter** | Android DNS + host filtering | Lightweight, good for MVP |
| **Invizible Pro** | Tor + DNSCrypt + firewall | Advanced privacy stack patterns |
| **Bindhosts** (Magisk) | Systemless hosts blocking | Root mode option for WLT |

---

## WLT-Adblocker Feature Synthesis

Features to combine from each project:

```
FROM uBlock Origin:
  ✦ Scriptlet filtering engine (##+js rules)
  ✦ Cosmetic filter compiler
  ✦ Resource redirect engine
  ✦ Anti-adblock filter lists

FROM Rethink DNS / Firestack:
  ✦ gVisor netstack integration
  ✦ Per-app firewall with UID resolution
  ✦ WireGuard split-tunnel support
  ✦ Connection tracker + logging

FROM BlockAds:
  ✦ HTTPS MITM for browsers (optional, per-app)
  ✦ Passthrough list (banking/payments/gov)
  ✦ Dual mode: VPN + root iptables

FROM HostShield:
  ✦ CNAME cloaking detection (AdGuard + NextDNS CNAME DBs)
  ✦ 50+ blocklist gallery with one-click enable
  ✦ Serve-stale DNS caching
  ✦ Fail-closed DoH certificate pinning

FROM Pi-hole:
  ✦ Gravity-style blocklist compilation
  ✦ Regex domain blocking
  ✦ Query statistics dashboard

FROM NetGuard:
  ✦ Clean RFC 1035 DNS packet parser
  ✦ DomainTrie with wildcard support
  ✦ SNI extractor from TLS ClientHello

FROM AdGuard urlfilter:
  ✦ Production DNSEngine + NetworkEngine (Go library)
  ✦ Full ABP + AdGuard syntax support

FROM AdAway:
  ✦ Hosts file sources management
  ✦ Root mode via /system/etc/hosts

FROM Blokada 5:
  ✦ Simple one-tap enable UX
  ✦ On-device only philosophy
```

---

## Blocklist Strategy for WLT-Adblocker

### Default enabled (covers 90% of cases)

1. **OISD Full** — unified, well-maintained, low false positives
2. **AdGuard DNS filter** — ad domains
3. **AdGuard Tracking Protection** — trackers
4. **AdGuard Mobile Ads** — mobile-specific ad domains
5. **Game Ad SDK list** (custom WLT list) — AdMob, Unity, AppLovin, ironSource, etc.

### Optional (user enables)

6. EasyList + EasyPrivacy (for HTTPS filtering mode)
7. AdGuard Annoyances (cookie notices, popups)
8. AdGuard CNAME trackers (CNAME cloaking)
9. StevenBlack Extended (aggressive, more false positives)
10. Phishing/malware lists (PhishTank, URLhaus)

### Custom WLT lists (we maintain)

- `wlt-game-ads.txt` — mobile game SDK domains
- `wlt-bypass-ips.txt` — hardcoded ad server IPs
- `wlt-passthrough.txt` — banking/payment/gov (never block)
- `wlt-youtube-web.txt` — YouTube web scriptlets (browser mode only)
