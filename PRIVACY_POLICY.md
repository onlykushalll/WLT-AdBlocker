# WLT-AdBlocker Privacy Policy

**Last updated: July 25, 2026**

WLT-AdBlocker ("WLT", "we", "us", "our") is a privacy-first, open-source Android ad blocker. This privacy policy explains what data we collect (spoiler: nothing) and how the app works.

## The Short Version

**WLT collects zero data. No telemetry, no analytics, no crash reports, no accounts, no cloud. Everything happens on your device.**

## Data We Collect

**None.**

WLT does not collect, transmit, or store any personal data. Specifically:

- ❌ No telemetry or usage statistics
- ❌ No crash reports
- ❌ No analytics
- ❌ No account or login system
- ❌ No cloud sync
- ❌ No IP logging
- ❌ No DNS query logging to remote servers
- ❌ No advertising identifiers (GAID/IDFA) collection
- ❌ No device fingerprinting
- ❌ No location tracking

## Data Stored On Your Device

The following data is stored locally on your device and never transmitted anywhere:

| Data | Purpose | Retention |
|---|---|---|
| DNS query log | Show recent queries in UI | 2000 entries (ring buffer, in-memory) |
| Block statistics | Show blocked/allowed counts | 60-minute time series (in-memory) |
| Custom rules | Your personal block/allow rules | Until you delete them |
| Regex rules | Your Pi-hole-style regex patterns | Until you delete them |
| App bypass list | Per-app firewall configuration | Until you change it |
| Blocked services | One-click service blocking toggles | Until you change them |
| Settings | Your preferences (theme, DNS, protection level, etc.) | Until you change them |
| Blocklists | Bundled + downloaded domain lists | Updated every 24h via WorkManager |
| CA certificate | Phase 3 HTTPS MITM (if enabled) | Until you regenerate or uninstall |
| WireGuard config | Tunnel configuration (if used) | Until you delete it |
| PCAP capture | Packet capture (if enabled) | Until you stop capture and share |
| DNS cache | Cached DNS responses (performance) | 10K entries, 5min-1hr TTL (in-memory) |
| IP→domain cache | Reverse lookup for per-app attribution | 5K entries, 5min TTL (in-memory) |
| Per-app stats | Per-UID network statistics | 100 apps, in-memory |

You can clear all local data at any time by:
1. Settings → Export/Import → Clear all data
2. Or uninstall the app

## How WLT Works

### VPN-Based DNS Interception

WLT uses Android's VpnService to intercept DNS queries (UDP port 53) on your device. When an app makes a DNS query:

1. WLT intercepts the query
2. WLT checks the DNS cache first (70% cache hit rate, <1ms)
3. If not cached, WLT checks the domain against blocklists (trie + bloom + regex + game SDK + DGA + domain age)
4. If blocked: WLT returns NXDOMAIN, 0.0.0.0, ::, NODATA, or REFUSED (configurable)
5. If allowed: WLT forwards the query to your chosen upstream DNS via DoH
6. The upstream response is cached and returned to the app

**No DNS queries are logged to any remote server.** The query log is in-memory only and cleared when the app is closed.

### DoH (DNS-over-HTTPS) Upstream

WLT uses encrypted DNS (DoH) for upstream queries. This means your ISP cannot see your DNS queries. Your chosen upstream DNS provider (e.g., Cloudflare 1.1.1.1) can see the queries, but WLT does not log or transmit them.

### DoH/DoT Bypass Prevention

Some apps try to bypass VPN DNS by using hardcoded DoH (DNS-over-HTTPS) or DoT (DNS-over-TLS) endpoints. WLT:
- Blocks 30+ known DoH/ODoH provider domains
- Blocks DoT port 853 (UDP + TCP)
- Optionally blocks QUIC (UDP 443) to prevent DoQ

### CNAME Cloaking Detection

Some trackers disguise themselves as first-party domains using CNAME records. WLT inspects upstream DNS responses for CNAME chains and blocks 50 known tracker CNAME targets (covering 100K subdomains).

### Domain Age Checking (RDAP)

WLT asynchronously queries RDAP servers to check the age of newly-seen domains. Domains registered less than 30 days ago are flagged as suspicious and blocked. **RDAP queries are made directly from your device to public RDAP servers. No intermediary is used.**

### Per-App Firewall

WLT can exclude specific apps from VPN filtering using `VpnService.Builder.addDisallowedApplication()`. Excluded apps use the system network directly.

### Phase 3: HTTPS MITM (Optional, Off by Default)

If you explicitly enable Phase 3 (HTTPS MITM), WLT will:
1. Generate a local CA certificate (ECDSA P-256, fresh keypair per leaf)
2. You must manually install this CA in Android's trust store
3. WLT will intercept HTTPS connections **only for domains in your MITM allowlist**
4. WLT can inject scriptlets, strip tracking parameters, prune HLS ad segments, serve noop resources

**Phase 3 is OFF by default. It only activates if you explicitly enable it and install the CA.** All other HTTPS traffic is relayed without decryption.

### WireGuard Tunnel (Optional)

If you configure a WireGuard tunnel, WLT can route DNS queries through the encrypted tunnel, hiding them from your ISP. The WireGuard configuration is stored locally and never transmitted.

### PCAP Export (Optional)

If you enable PCAP capture, WLT captures raw IP packets to a .pcap file in the app's cache directory. This file is only accessible to you (via file sharing) and is not transmitted anywhere.

## Permissions

WLT requests the following Android permissions:

| Permission | Why |
|---|---|
| `INTERNET` | Forward DNS queries upstream |
| `ACCESS_NETWORK_STATE` | Detect network changes |
| `ACCESS_WIFI_STATE` | Detect WiFi changes |
| `CHANGE_NETWORK_STATE` | Advanced connectivity management |
| `FOREGROUND_SERVICE` | Keep VPN alive in background |
| `FOREGROUND_SERVICE_SPECIALUSE` | VPN foreground service type (API 34+) |
| `POST_NOTIFICATIONS` | Show VPN status notification (Android 13+) |
| `QUERY_ALL_PACKAGES` | List installed apps for per-app firewall |
| `PACKAGE_USAGE_STATS` | Per-app traffic attribution (Android 10+) |
| `RECEIVE_BOOT_COMPLETED` | Auto-start VPN on boot (opt-in) |
| `BIND_QUICK_SETTINGS_TILE` | Quick settings tile for VPN toggle |
| `WAKE_LOCK` | Keep VPN alive during device sleep |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Prevent OEMs from killing VPN |
| `ACCESS_FINE_LOCATION` | Advanced network attribution (WiFi SSID) |
| `ACCESS_COARSE_LOCATION` | Advanced network attribution |
| `READ_PHONE_STATE` | UID resolution on older Android |
| `WRITE_EXTERNAL_STORAGE` | CA cert export (pre-Q) |
| `READ_EXTERNAL_STORAGE` | Settings import (pre-API 33) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Alternative FGS type compatibility |

**WLT does not request internet permission for telemetry — only for forwarding DNS queries upstream.**

## Open Source

WLT is 100% open source under GPL v3. You can audit every line of code at: https://github.com/onlykushalll/WLT-AdBlocker

## Changes to This Policy

We will update this privacy policy if needed. All changes will be committed to the public GitHub repository.

## Contact

For privacy questions or concerns, open an issue on GitHub: https://github.com/onlykushalll/WLT-AdBlocker/issues
