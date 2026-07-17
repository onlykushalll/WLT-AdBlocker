// Package engine is the heart of WLT-Adblocker. It orchestrates the four-layer
// Smart Cascade (DNS → SNI → HTTPS → Scriptlet) and produces forensic traces
// for every decision.
//
// Architecture:
//   ┌─────────────┐
//   │ BlockEngine │  ← public API: CheckDNS(), CheckSNI(), CheckHTTPS()
//   └──────┬──────┘
//          │ uses
//   ┌──────┴──────────────────────────────────────┐
//   │ trie  │  bloom  │  gamesdk  │  forensics  │ allowlist
//   └─────────────────────────────────────────────┘
//
// Decision flow for a DNS query (CheckDNS):
//   1. Bloom filter fast-reject (if not in bloom, definitely not blocked)
//   2. Allowlist check (user-trusted domains never blocked)
//   3. Trie exact/wildcard match (blocklists)
//   4. Game SDK fingerprint check
//   5. CNAME chain check (if response contains CNAMEs)
//   6. Record forensic trace
//   7. Return decision + response packet
package engine

import (
        "strings"
        "sync"
        "sync/atomic"
        "time"

        "wlt-core/dns"
        "wlt-core/internal/bloom"
        "wlt-core/internal/forensics"
        "wlt-core/internal/gamesdk"
        "wlt-core/internal/trie"
        netw "wlt-core/net"
)

// BlockResponse is the action to take for a blocked request.
type BlockResponse int

const (
        ResponseNXDomain BlockResponse = iota // return NXDOMAIN (default)
        ResponseNullIP                         // return 0.0.0.0 (AdAway style)
        ResponseRefused                        // return REFUSED (HostShield style)
)

// Decision is the engine's verdict for a request.
type Decision struct {
        Block      bool
        Reason     string
        Rule       string
        Layer      forensics.Layer
        SDK        gamesdk.SDK
        WouldBlock bool // if a disabled layer would have blocked
        Response   BlockResponse
        TraceID    int64
}

// Stats holds aggregate counters.
type Stats struct {
        TotalQueries  int64
        TotalBlocked  int64
        TotalAllowed  int64
        TotalMissed   int64
        ByLayer       map[forensics.Layer]int64
        BySDK         map[gamesdk.SDK]int64
}

// statsCounters holds the atomic counters and the mutex-guarded maps separately.
type statsCounters struct {
        TotalQueries int64
        TotalBlocked int64
        TotalAllowed int64
        TotalMissed  int64

        mu     sync.RWMutex
        ByLayer map[forensics.Layer]int64
        BySDK   map[gamesdk.SDK]int64
}

// incLayer increments a layer counter under the stats mutex.
func (s *statsCounters) incLayer(l forensics.Layer) {
        s.mu.Lock()
        s.ByLayer[l]++
        s.mu.Unlock()
}

// incSDK increments an SDK counter under the stats mutex.
func (s *statsCounters) incSDK(sd gamesdk.SDK) {
        s.mu.Lock()
        s.BySDK[sd]++
        s.mu.Unlock()
}

// snapshot returns a copy of the maps for GetStats.
func (s *statsCounters) snapshot() (map[forensics.Layer]int64, map[gamesdk.SDK]int64) {
        s.mu.RLock()
        defer s.mu.RUnlock()
        l := make(map[forensics.Layer]int64, len(s.ByLayer))
        for k, v := range s.ByLayer {
                l[k] = v
        }
        sd := make(map[gamesdk.SDK]int64, len(s.BySDK))
        for k, v := range s.BySDK {
                sd[k] = v
        }
        return l, sd
}

// Engine is the main WLT block engine. Thread-safe.
type Engine struct {
        // Blocklists
        trie      *trie.Trie
        bloom     *bloom.Filter
        allowlist *trie.Trie // user-trusted domains (never blocked)
        denylist  *trie.Trie // user-forced blocks (always blocked, overrides allowlist)

        // Detection
        gamesdk *gamesdk.Engine
        ipBlock *netw.IPBlocklist

        // Forensics
        forensics *forensics.Recorder

        // Config
        mu             sync.RWMutex
        blockResponse  BlockResponse
        cascadeEnabled map[forensics.Layer]bool
        // CNAME cloaking database (domains known to CNAME-cloak to trackers)
        cnameCloak map[string]bool

        // Stats
        stats *statsCounters
}

// Config configures a new Engine.
type Config struct {
        // ExpectedBlocklistSize sizes the bloom filter (default 500000).
        ExpectedBlocklistSize int
        // BlockResponse controls what response blocked queries get.
        BlockResponse BlockResponse
        // EnableLayer toggles each cascade layer.
        EnableDNS       bool
        EnableSNI       bool
        EnableHTTPS     bool
        EnableScriptlet bool
        // ForensicBufferSize is the max traces to keep (default 5000).
        ForensicBufferSize int
}

// DefaultConfig returns a sensible Phase 1 config (DNS-only).
func DefaultConfig() Config {
        return Config{
                ExpectedBlocklistSize: 500000,
                BlockResponse:         ResponseNXDomain,
                EnableDNS:             true,
                EnableSNI:             false, // Phase 2
                EnableHTTPS:           false, // Phase 3
                EnableScriptlet:       false, // Phase 3
                ForensicBufferSize:    5000,
        }
}

// New creates an Engine with the given config.
func New(cfg Config) *Engine {
        if cfg.ExpectedBlocklistSize <= 0 {
                cfg.ExpectedBlocklistSize = 500000
        }
        if cfg.ForensicBufferSize <= 0 {
                cfg.ForensicBufferSize = 5000
        }
        return &Engine{
                trie:      trie.New(),
                bloom:     bloom.New(cfg.ExpectedBlocklistSize, 0.001),
                allowlist: trie.New(),
                denylist:  trie.New(),
                gamesdk:   gamesdk.New(),
                ipBlock:   netw.NewIPBlocklist(),
                forensics: forensics.NewRecorder(cfg.ForensicBufferSize),
                blockResponse: cfg.BlockResponse,
                cascadeEnabled: map[forensics.Layer]bool{
                        forensics.LayerDNS:       cfg.EnableDNS,
                        forensics.LayerSNI:       cfg.EnableSNI,
                        forensics.LayerHTTPS:     cfg.EnableHTTPS,
                        forensics.LayerScriptlet: cfg.EnableScriptlet,
                },
                cnameCloak: make(map[string]bool),
                stats: &statsCounters{
                        ByLayer: make(map[forensics.Layer]int64),
                        BySDK:   make(map[gamesdk.SDK]int64),
                },
        }
}

// AddBlockDomain adds a domain to the blocklist (trie + bloom).
// Used by the blocklist loader and by user denylist additions.
func (e *Engine) AddBlockDomain(domain string) {
        d := normalizeDomain(domain)
        if d == "" {
                return
        }
        // Add domain and all parent suffixes to bloom so suffix matching works:
        // rule "example.com" must bloom-match "sub.example.com".
        e.bloom.Add(d)
        labels := strings.Split(d, ".")
        for i := 1; i < len(labels); i++ {
                e.bloom.Add(strings.Join(labels[i:], "."))
        }
        e.trie.Insert(d)
}

// bloomContainsAnySuffix checks if the domain OR any of its parent suffixes
// is in the bloom filter. Used as a fast negative pre-check before the trie walk.
func (e *Engine) bloomContainsAnySuffix(domain string) bool {
        d := normalizeDomain(domain)
        if d == "" {
                return false
        }
        if e.bloom.Contains(d) {
                return true
        }
        labels := strings.Split(d, ".")
        for i := 1; i < len(labels); i++ {
                if e.bloom.Contains(strings.Join(labels[i:], ".")) {
                        return true
                }
        }
        return false
}

// AddBlockDomains bulk-adds domains. Faster than individual adds for list loading.
func (e *Engine) AddBlockDomains(domains []string) {
        for _, d := range domains {
                e.AddBlockDomain(d)
        }
}

// AddAllowDomain adds a domain to the allowlist (passthrough, never blocked).
func (e *Engine) AddAllowDomain(domain string) {
        e.allowlist.Insert(normalizeDomain(domain))
}

// AddDenyDomain adds a user-forced block (overrides allowlist).
func (e *Engine) AddDenyDomain(domain string) {
        e.denylist.Insert(normalizeDomain(domain))
}

// AddCNAMECloak marks a domain as a known CNAME-cloaking tracker.
// When a CNAME chain ends at this domain, we block even if the original
// query domain wasn't in any list.
func (e *Engine) AddCNAMECloak(domain string) {
        e.mu.Lock()
        defer e.mu.Unlock()
        e.cnameCloak[normalizeDomain(domain)] = true
}

// LoadGameIPs loads the hardcoded IP blocklist for game SDKs.
func (e *Engine) LoadGameIPs(ips []string) {
        e.ipBlock.AddAll(ips)
        e.gamesdk.LoadHardcodedIPs(ips)
}

// CheckDNS evaluates a DNS query. Returns a Decision and (if blocked) the
// response packet to send back to the app.
//
// `query` is the raw DNS packet bytes. If `response` is provided (the upstream
// reply), CNAME chains are inspected for cloaking.
func (e *Engine) CheckDNS(query []byte, response []byte) (Decision, []byte) {
        atomic.AddInt64(&e.stats.TotalQueries, 1)
        d := Decision{Layer: forensics.LayerDNS}

        domain, err := dns.ExtractQueryDomain(query)
        if err != nil {
                d.Block = false
                d.Reason = "unparseable query: " + err.Error()
                return d, nil
        }
        d.Reason = "queried: " + domain

        // 1. Denylist (user-forced) — overrides everything.
        if ok, _ := e.denylist.Contains(domain); ok {
                d.Block = true
                d.Reason = "user denylist"
                d.Rule = "denylist"
                return e.finalizeBlock(d, query, domain, gamesdk.SDKUnknown)
        }

        // 2. Allowlist (passthrough) — skip all blocking.
        if ok, _ := e.allowlist.Contains(domain); ok {
                d.Block = false
                d.Reason = "user allowlist (passthrough)"
                atomic.AddInt64(&e.stats.TotalAllowed, 1)
                e.recordTrace(d, domain, "", 0, query, response)
                return d, nil
        }

        // 3. Bloom filter fast-reject — check domain AND all parent suffixes.
        // (A rule "example.com" blocks "sub.example.com", so we must check each suffix.)
        if !e.bloomContainsAnySuffix(domain) {
                // Definitely not in blocklist — allow.
                // But still check game SDK (SDK domains may not be in main list).
                sdk := e.gamesdk.DetectByDomain(domain)
                if sdk != gamesdk.SDKUnknown {
                        d.Block = true
                        d.Reason = "game SDK: " + string(sdk)
                        d.Rule = "gamesdk:" + string(sdk)
                        d.SDK = sdk
                        return e.finalizeBlock(d, query, domain, sdk)
                }
                d.Block = false
                d.Reason = "not in blocklist (bloom reject)"
                atomic.AddInt64(&e.stats.TotalAllowed, 1)
                e.recordTrace(d, domain, "", 0, query, response)
                return d, nil
        }

        // 4. Trie exact/wildcard match.
        if ok, kind := e.trie.Contains(domain); ok {
                d.Block = true
                d.Reason = "blocklist match (" + kind.String() + ")"
                d.Rule = "blocklist"
                // Detect SDK for stats.
                sdk := e.gamesdk.DetectByDomain(domain)
                d.SDK = sdk
                return e.finalizeBlock(d, query, domain, sdk)
        }

        // 5. Game SDK fingerprint (may not be in main blocklist).
        sdk := e.gamesdk.DetectByDomain(domain)
        if sdk != gamesdk.SDKUnknown {
                d.Block = true
                d.Reason = "game SDK: " + string(sdk)
                d.Rule = "gamesdk:" + string(sdk)
                d.SDK = sdk
                return e.finalizeBlock(d, query, domain, sdk)
        }

        // 6. CNAME cloaking check (if we have the upstream response).
        if len(response) > 0 {
                cnames := dns.ExtractCNAMEs(response)
                for _, cname := range cnames {
                        e.mu.RLock()
                        cloak := e.cnameCloak[cname]
                        e.mu.RUnlock()
                        if cloak {
                                d.Block = true
                                d.Reason = "CNAME cloak to " + cname
                                d.Rule = "cname-cloak:" + cname
                                return e.finalizeBlock(d, query, domain, gamesdk.SDKUnknown)
                        }
                        // Also check if the CNAME target itself is blocked.
                        if ok, _ := e.trie.Contains(cname); ok {
                                d.Block = true
                                d.Reason = "CNAME target blocked: " + cname
                                d.Rule = "cname:" + cname
                                return e.finalizeBlock(d, query, domain, gamesdk.SDKUnknown)
                        }
                }
        }

        // Allowed.
        d.Block = false
        d.Reason = "no match"
        atomic.AddInt64(&e.stats.TotalAllowed, 1)
        e.recordTrace(d, domain, "", 0, query, response)
        return d, nil
}

// CheckSNI evaluates a TLS ClientHello's SNI hostname (Layer 2).
// `payload` is the TCP segment containing the ClientHello.
func (e *Engine) CheckSNI(payload []byte) Decision {
        atomic.AddInt64(&e.stats.TotalQueries, 1)
        d := Decision{Layer: forensics.LayerSNI}

        if !e.cascadeEnabled[forensics.LayerSNI] {
                d.Reason = "SNI layer disabled"
                d.WouldBlock = e.wouldSNIBlock(payload)
                return d
        }

        sni, err := netw.ExtractSNI(payload)
        if err != nil || sni == "" {
                d.Block = false
                d.Reason = "no SNI in ClientHello"
                return d
        }
        d.Reason = "SNI: " + sni

        if ok, _ := e.allowlist.Contains(sni); ok {
                d.Block = false
                d.Reason = "allowlist"
                atomic.AddInt64(&e.stats.TotalAllowed, 1)
                return d
        }
        if ok, _ := e.trie.Contains(sni); ok {
                d.Block = true
                d.Reason = "blocklist match (SNI)"
                d.Rule = "sni:blocklist"
                sdk := e.gamesdk.DetectByDomain(sni)
                d.SDK = sdk
                e.finalizeDecision(d, sni, "", 0)
                return d
        }
        if sdk := e.gamesdk.DetectByDomain(sni); sdk != gamesdk.SDKUnknown {
                d.Block = true
                d.Reason = "game SDK (SNI): " + string(sdk)
                d.Rule = "sni:gamesdk:" + string(sdk)
                d.SDK = sdk
                e.finalizeDecision(d, sni, "", 0)
                return d
        }
        d.Block = false
        d.Reason = "no SNI match"
        atomic.AddInt64(&e.stats.TotalAllowed, 1)
        return d
}

// wouldSNIBlock is used for forensics: "if SNI layer were enabled, would it have blocked?"
func (e *Engine) wouldSNIBlock(payload []byte) bool {
        sni, err := netw.ExtractSNI(payload)
        if err != nil || sni == "" {
                return false
        }
        if ok, _ := e.allowlist.Contains(sni); ok {
                return false
        }
        if ok, _ := e.trie.Contains(sni); ok {
                return true
        }
        if e.gamesdk.DetectByDomain(sni) != gamesdk.SDKUnknown {
                return true
        }
        return false
}

// CheckIP evaluates a destination IP against the hardcoded IP blocklist.
// Used for SDKs that connect directly to IPs, bypassing DNS.
func (e *Engine) CheckIP(ip string) Decision {
        atomic.AddInt64(&e.stats.TotalQueries, 1)
        d := Decision{Layer: forensics.LayerSNI}
        if e.ipBlock.Contains(ip) {
                d.Block = true
                d.Reason = "hardcoded ad IP"
                d.Rule = "ip:" + ip
                return d
        }
        if e.gamesdk.IsHardcodedIP(ip) {
                d.Block = true
                d.Reason = "game SDK hardcoded IP"
                d.Rule = "gamesdk-ip:" + ip
                return d
        }
        d.Block = false
        d.Reason = "IP not blocked"
        atomic.AddInt64(&e.stats.TotalAllowed, 1)
        return d
}

// finalizeBlock builds the response packet and records stats + forensics.
func (e *Engine) finalizeBlock(d Decision, query []byte, domain string, sdk gamesdk.SDK) (Decision, []byte) {
        d.Block = true
        atomic.AddInt64(&e.stats.TotalBlocked, 1)
        e.stats.incLayer(d.Layer)
        if sdk != gamesdk.SDKUnknown {
                e.stats.incSDK(sdk)
        }
        var resp []byte
        switch e.blockResponse {
        case ResponseNullIP:
                msg, _ := dns.Parse(query)
                resp = dns.BuildNullIP(msg)
        case ResponseRefused:
                msg, _ := dns.Parse(query)
                resp = dns.BuildRefused(msg)
        default:
                msg, _ := dns.Parse(query)
                resp = dns.BuildNxDomain(msg)
        }
        e.recordTrace(d, domain, "", 0, query, resp)
        return d, resp
}

// finalizeDecision records stats + forensics for non-DNS layers.
func (e *Engine) finalizeDecision(d Decision, domain, path string, port int) {
        if d.Block {
                atomic.AddInt64(&e.stats.TotalBlocked, 1)
        } else {
                atomic.AddInt64(&e.stats.TotalAllowed, 1)
        }
        e.stats.incLayer(d.Layer)
        if d.SDK != gamesdk.SDKUnknown {
                e.stats.incSDK(d.SDK)
        }
        e.recordTrace(d, domain, path, port, nil, nil)
}

// recordTrace builds and stores a forensic trace.
func (e *Engine) recordTrace(d Decision, domain, path string, port int, query, response []byte) {
        t := &forensics.Trace{
                Timestamp: time.Now(),
                Domain:    domain,
                Path:      path,
                Port:      port,
                SDK:       string(d.SDK),
                FinalBlock: d.Block,
        }
        t.Results = append(t.Results, forensics.LayerResult{
                Layer:      d.Layer,
                Decision:   decisionToForensic(d),
                Rule:       d.Rule,
                Reason:     d.Reason,
                WouldBlock: d.WouldBlock,
                FixAction:  fixActionFor(d),
        })
        e.forensics.Record(t)
}

func decisionToForensic(d Decision) forensics.Decision {
        if d.Block {
                return forensics.DecisionBlock
        }
        if d.WouldBlock {
                return forensics.DecisionMiss
        }
        return forensics.DecisionAllow
}

func fixActionFor(d Decision) string {
        if d.Block {
                return ""
        }
        if !d.WouldBlock {
                return ""
        }
        switch d.Layer {
        case forensics.LayerDNS:
                return "Add domain to denylist"
        case forensics.LayerSNI:
                return "Enable SNI inspection in Settings"
        case forensics.LayerHTTPS:
                return "Enable HTTPS filtering for this app"
        case forensics.LayerScriptlet:
                return "Install YouTube scriptlet pack"
        }
        return ""
}

// GetStats returns a snapshot of aggregate stats.
func (e *Engine) GetStats() Stats {
        return Stats{
                TotalQueries: atomic.LoadInt64(&e.stats.TotalQueries),
                TotalBlocked: atomic.LoadInt64(&e.stats.TotalBlocked),
                TotalAllowed: atomic.LoadInt64(&e.stats.TotalAllowed),
                TotalMissed:  atomic.LoadInt64(&e.stats.TotalMissed),
                ByLayer:      copyLayerMap(e.stats.ByLayer),
                BySDK:        copySDKMap(e.stats.BySDK),
        }
}

func copyLayerMap(m map[forensics.Layer]int64) map[forensics.Layer]int64 {
        out := make(map[forensics.Layer]int64, len(m))
        for k, v := range m {
                out[k] = atomic.LoadInt64(&v) // not perfect, but close
        }
        return out
}

func copySDKMap(m map[gamesdk.SDK]int64) map[gamesdk.SDK]int64 {
        out := make(map[gamesdk.SDK]int64, len(m))
        for k, v := range m {
                out[k] = atomic.LoadInt64(&v)
        }
        return out
}

// Forensics exposes the recorder for the UI.
func (e *Engine) Forensics() *forensics.Recorder { return e.forensics }

// GameSDK exposes the game SDK engine for the UI.
func (e *Engine) GameSDK() *gamesdk.Engine { return e.gamesdk }

// BlocklistSize returns the number of blocklist rules loaded.
func (e *Engine) BlocklistSize() int { return e.trie.Size() }

// AllowlistSize returns the number of allowlist rules loaded.
func (e *Engine) AllowlistSize() int { return e.allowlist.Size() }

// SetBlockResponse changes the response type for blocked queries.
func (e *Engine) SetBlockResponse(r BlockResponse) {
        e.mu.Lock()
        defer e.mu.Unlock()
        e.blockResponse = r
}

// EnableLayer toggles a cascade layer at runtime.
func (e *Engine) EnableLayer(layer forensics.Layer, enabled bool) {
        e.mu.Lock()
        defer e.mu.Unlock()
        e.cascadeEnabled[layer] = enabled
}

// IsLayerEnabled reports whether a layer is active.
func (e *Engine) IsLayerEnabled(layer forensics.Layer) bool {
        e.mu.RLock()
        defer e.mu.RUnlock()
        return e.cascadeEnabled[layer]
}

// normalizeDomain lowercases and trims dots.
func normalizeDomain(d string) string {
        d = strings.ToLower(strings.TrimSpace(d))
        return strings.Trim(d, ".")
}
