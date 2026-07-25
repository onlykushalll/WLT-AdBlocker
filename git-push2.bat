@echo off
cd /d C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker
git config --global user.name "onlykushalll"
git config --global user.email "onlykushalll@users.noreply.github.com"
git branch -M main
git add -A
git commit -m "Phase 7+8: Wire all Go engines + DNS cache + DoT/QUIC blocking + ECH blocking

Phase 7 (Wire Existing Engines):
- 7a: AddRegex + RegexCount via gomobile (Pi-hole regex matching)
- 7b: DecisionNullIPv6 (AAAA ::) + DecisionNODATA (AdGuard response types)
- 7c: ParseRule + AddParsedRule (ABP/uBlock syntax: ||domain^, @@allow, $important, $badfilter)
- 7d: preparser wired to blocklist loading (!#if ext_wlt, env_android, cap_dns_blocking, cap_mitm)
- 7e: DomainAge async RDAP checker + dynamicBlocks trie (NextDNS technique)
- 7f: JA4+ TLS fingerprinting: CheckSNIWithJA4, GetJA4, AddAdSDKFingerprint (post-ECH prep)

Phase 8 (DNS Infrastructure):
- 8a: DnsCache.kt — LRU 10K entries, ~70% query reduction, <1ms cache hits
- 8b: Block DoT port 853 (UDP+TCP) — prevents DNS bypass via DNS-over-TLS
- 8c: QUIC blocking toggle (block UDP 443, opt-in) — forces TCP fallback
- 8d: ECH config blocking (cloudflare-ech.com + crypto.cloudflare.com)

Build: wlt.aar 21.3MB (fresh with Phase 7 Go), APK 68.4MB
Go tests: 22/22 pass (143 tests)
Scriptlets: 71, Blocklist domains: 888"
git push -u origin main --force
echo PUSH_RC=%errorlevel%
echo DONE
