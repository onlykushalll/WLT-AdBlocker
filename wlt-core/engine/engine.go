// Package engine implements the WLT BlockEngine: a 4-layer Smart Cascade
// that combines a denylist, an allowlist, a counting bloom filter, a
// suffix-matching trie, a Game Ad Intelligence engine, and CNAME-cloak
// detection to decide whether to block, null-IP, NXDOMAIN, or allow a
// given DNS/SNI/HTTPS request.
//
// The cascade order is:
//
//   1. Denylist (highest priority; overrides everything)
//   2. Allowlist (passthrough — banking/gov/critical domains)
//   3. Bloom fast-reject (negative pre-check; trie confirms positives)
//   4. Trie (suffix-matching blocklist)
//   5. Game Ad Intelligence (block known SDK ad-server domains)
//   6. CNAME-cloak detection (block benign domains that CNAME to trackers)
//
// Every decision is recorded by the forensics engine so the UI can
// explain "why did this ad get through?".
package engine

import (
        "regexp"
        "strings"
        "sync"
        "time"

        "github.com/wlt/adblocker/dns"
        "github.com/wlt/adblocker/internal/bloom"
        "github.com/wlt/adblocker/internal/domainage"
        "github.com/wlt/adblocker/internal/forensics"
        "github.com/wlt/adblocker/internal/gamesdk"
        "github.com/wlt/adblocker/internal/ja4"
        "github.com/wlt/adblocker/internal/trie"
)

// Decision constants. These are the values returned in CheckResult.
type Decision int

const (
        DecisionAllow    Decision = 0
        DecisionBlock    Decision = 1
        DecisionNullIP   Decision = 2
        DecisionNXDOMAIN Decision = 3
        DecisionNullIPv6 Decision = 4 // Phase 7b: IPv6 null IP (::)
        DecisionNODATA   Decision = 5 // Phase 7b: NOERROR with empty answer
)

// Layer constants used by CheckResult.Layer and SetLayerEnabled.
const (
        LayerDNS    = 0
        LayerSNI    = 1
        LayerHTTPS  = 2
        LayerScript = 3
)

// CheckResult is the outcome of a single CheckDNS / CheckSNI / CheckHTTPS
// call. It is designed to be gomobile-friendly (simple types only).
type CheckResult struct {
        Decision Decision
        Reason   string
        Layer    int
        SDK      string
        Trace    *forensics.Trace
}

// Engine is the WLT BlockEngine.
type Engine struct {
        mu sync.RWMutex

        // Data structures.
        denylist      *trie.Trie
        allowlist     *trie.Trie
        trie          *trie.Trie
        bloom         *bloom.Filter
        regexps       []*regexp.Regexp // Phase 6: Pi-hole-style regex matching
        dynamicBlocks *trie.Trie      // Phase 7e: domains flagged by domainage checker

        gamesdk   *gamesdk.Engine
        forensics *forensics.Engine

        cnameCloak map[string]bool // known CNAME-cloak targets

        stats *statsCounters

        // Layer enable toggles. Indexed by Layer* constants.
        layers [4]bool

        // Default block response — one of DecisionBlock, DecisionNullIP,
        // DecisionNXDOMAIN.
        defaultBlock Decision

        // Phase 7e: Domain age checker (async RDAP queries)
        domainAge     *domainage.Checker
        domainAgeSeen sync.Map           // domains already sent for RDAP check
        domainAgeChan chan string        // channel for async RDAP checks
        domainAgeStop chan struct{}      // stop signal for background goroutine
        domainAgeOn   bool               // is background checker running?

        // Phase 7f: JA4+ last fingerprint (for logging/debugging)
        lastJA4 string
}

// New returns a new Engine with empty data structures and all 4 layers
// enabled. The default block response is DecisionNullIP (less destructive
// than NXDOMAIN for game apps).
func New() *Engine {
        return &Engine{
                denylist:      trie.New(),
                allowlist:     trie.New(),
                trie:          trie.New(),
                bloom:         bloom.New(10000, 0.0005),
                regexps:       nil,
                dynamicBlocks: trie.New(),
                gamesdk:       gamesdk.New(),
                forensics:     forensics.New(5000),
                cnameCloak:    make(map[string]bool),
                stats:         newStatsCounters(),
                layers:        [4]bool{true, true, true, true},
                defaultBlock:  DecisionNullIP,
                domainAge:     domainage.New(),
                domainAgeChan: make(chan string, 1000),
                domainAgeStop: make(chan struct{}),
        }
}

// AddRegex adds a Pi-hole-style regex pattern to the block engine.
// The regex is compiled (RE2) and matched against full domain names.
// Example: AddRegex(`^ads[0-9]*\.`) blocks ads0.example.com, ads1.foo.net, etc.
func (e *Engine) AddRegex(pattern string) error {
        re, err := regexp.Compile(pattern)
        if err != nil {
                return err
        }
        e.mu.Lock()
        e.regexps = append(e.regexps, re)
        e.mu.Unlock()
        return nil
}

// RegexCount returns the number of compiled regex rules.
func (e *Engine) RegexCount() int {
        e.mu.RLock()
        defer e.mu.RUnlock()
        return len(e.regexps)
}

// StartDomainAgeChecker starts a background goroutine that asynchronously
// checks newly-seen domains via RDAP. If a domain is < 30 days old, it's
// added to the dynamic blocklist. This is the NextDNS technique — catches
// fresh malware, DGA, and phantom-squatting domains that aren't in any
// static blocklist yet.
//
// The check is async: the first query to a new domain always passes, but
// if RDAP says it's suspicious, the second query will be blocked.
func (e *Engine) StartDomainAgeChecker() {
        if e.domainAgeOn {
                return
        }
        e.domainAgeOn = true
        go e.domainAgeLoop()
}

// StopDomainAgeChecker stops the background RDAP checker goroutine.
func (e *Engine) StopDomainAgeChecker() {
        if !e.domainAgeOn {
                return
        }
        e.domainAgeOn = false
        close(e.domainAgeStop)
}

// domainAgeLoop is the background goroutine that processes domains from
// the channel and checks their age via RDAP.
func (e *Engine) domainAgeLoop() {
        for {
                select {
                case domain := <-e.domainAgeChan:
                        if e.domainAge.IsSuspicious(domain) {
                                e.dynamicBlocks.Insert(domain)
                        }
                case <-e.domainAgeStop:
                        return
                }
        }
}

// CheckDomainAge manually checks a domain's age and returns true if it
// should be blocked (domain < 30 days old). This is a synchronous check
// that may take 200-500ms (RDAP query). Prefer StartDomainAgeChecker for
// production use.
func (e *Engine) CheckDomainAge(domain string) bool {
        return e.domainAge.IsSuspicious(domain)
}

// DomainAgeCacheSize returns the number of cached RDAP results.
func (e *Engine) DomainAgeCacheSize() int {
        return e.domainAge.CacheSize()
}

// DynamicBlockCount returns the number of domains flagged by the domain
// age checker.
func (e *Engine) DynamicBlockCount() int {
        return e.dynamicBlocks.Size()
}

// LastJA4 returns the most recently computed JA4+ fingerprint. Useful
// for debugging and logging.
func (e *Engine) LastJA4() string {
        return e.lastJA4
}

// CheckSNIWithJA4 is like CheckSNI but also computes the JA4+ TLS
// fingerprint from the ClientHello bytes. If the fingerprint matches a
// known ad SDK, the connection is blocked even if the SNI doesn't match
// any blocklist rule. This is the post-ECH fallback — when SNI is
// encrypted, JA4+ can still identify ad SDKs by their TLS stack.
func (e *Engine) CheckSNIWithJA4(sni string, clientHello []byte) CheckResult {
        // First run the normal SNI cascade.
        res := e.CheckSNI(sni)
        if res.Decision != DecisionAllow {
                return res
        }

        // Compute JA4+ fingerprint.
        if len(clientHello) > 0 {
                fp, err := ja4.Compute(clientHello)
                if err == nil && fp != "" {
                        e.mu.Lock()
                        e.lastJA4 = fp
                        e.mu.Unlock()

                        // Check against known ad SDK fingerprints.
                        if ja4.IsKnownAdSDK(fp) {
                                return e.block(sni, LayerSNI, "JA4+ ad SDK fingerprint: "+fp, "")
                        }
                }
        }

        return res
}

// AddBlockDomain adds a domain to the suffix-matching blocklist and to
// the bloom filter.
func (e *Engine) AddBlockDomain(domain string) {
        domain = strings.TrimSpace(domain)
        if domain == "" {
                return
        }
        e.trie.Insert(domain)
        e.bloom.Add(domain)
}

// AddAllowDomain adds a domain to the allowlist (passthrough).
func (e *Engine) AddAllowDomain(domain string) {
        domain = strings.TrimSpace(domain)
        if domain == "" {
                return
        }
        e.allowlist.Insert(domain)
}

// AddDenyDomain adds a domain to the denylist. Denylist rules override
// allowlist rules (a user-explicit block always wins).
func (e *Engine) AddDenyDomain(domain string) {
        domain = strings.TrimSpace(domain)
        if domain == "" {
                return
        }
        e.denylist.Insert(domain)
}

// AddCNAMECloakTarget registers a known CNAME-cloak tracker target. Any
// domain whose CNAME chain reaches this target will be blocked at the
// CNAME-cloak layer.
func (e *Engine) AddCNAMECloakTarget(target string) {
        target = strings.ToLower(strings.TrimSpace(target))
        if target == "" {
                return
        }
        e.mu.Lock()
        e.cnameCloak[target] = true
        e.mu.Unlock()
}

// SetLayerEnabled enables or disables one cascade layer. Disabled layers
// return DecisionAllow without consulting their data structures.
func (e *Engine) SetLayerEnabled(layer int, enabled bool) {
        e.mu.Lock()
        defer e.mu.Unlock()
        if layer < 0 || layer >= len(e.layers) {
                return
        }
        e.layers[layer] = enabled
}

// IsLayerEnabled reports whether a cascade layer is currently enabled.
func (e *Engine) IsLayerEnabled(layer int) bool {
        e.mu.RLock()
        defer e.mu.RUnlock()
        if layer < 0 || layer >= len(e.layers) {
                return false
        }
        return e.layers[layer]
}

// SetDefaultBlockResponse sets the default block response returned by the
// cascade when a positive match is found.
func (e *Engine) SetDefaultBlockResponse(d Decision) {
        e.mu.Lock()
        defer e.mu.Unlock()
        e.defaultBlock = d
}

// CheckDNS runs the Smart Cascade for a DNS query domain. Returns a
// CheckResult with the decision, reason, and forensic trace.
func (e *Engine) CheckDNS(domain string) CheckResult {
        domain = strings.ToLower(strings.TrimSpace(domain))
        domain = strings.TrimSuffix(domain, ".")

        e.stats.incTotals(int(DecisionAllow)) // will be corrected below if blocked
        e.stats.incLayer(LayerDNS)

        if !e.IsLayerEnabled(LayerDNS) {
                return e.allow(domain, LayerDNS, "DNS layer disabled")
        }
        if domain == "" {
                return e.allow(domain, LayerDNS, "empty domain")
        }

        // 1. Denylist (highest priority).
        if e.denylist.Contains(domain) {
                return e.block(domain, LayerDNS, "denylist match", "")
        }

        // 2. Allowlist.
        if e.allowlist.Contains(domain) {
                return e.allow(domain, LayerDNS, "allowlist match")
        }

        // 3. Bloom fast-reject (negative answer is always accurate). If the
        // bloom says "definitely not in blocklist" we skip the trie but still
        // fall through to the gamesdk layer. If the bloom says "maybe in
        // blocklist" we confirm with the trie.
        bloomHit := e.bloom.Contains(domain)
        if bloomHit {
                // 4. Trie (confirms bloom positives).
                if e.trie.Contains(domain) {
                        return e.block(domain, LayerDNS, "blocklist match (trie)", "")
                }
        }

        // 5. Game Ad Intelligence.
        if sdk := e.gamesdk.DetectByDomain(domain); sdk != nil {
                return e.block(domain, LayerDNS, "game SDK ad-server: "+sdk.Name, sdk.Name)
        }

        // 5b. Regex matching (Phase 6 — Pi-hole technique).
        // Catches patterns that suffix matching misses, e.g. ^ads[0-9]*\.
        e.mu.RLock()
        for _, re := range e.regexps {
                if re.MatchString(domain) {
                        e.mu.RUnlock()
                        return e.block(domain, LayerDNS, "regex match", "")
                }
        }
        e.mu.RUnlock()

        // 5c. Dynamic blocks (Phase 7e — domain age checker).
        // Domains flagged by the async RDAP background checker.
        if e.dynamicBlocks.Contains(domain) {
                return e.block(domain, LayerDNS, "dynamic block (domain age < 30 days)", "")
        }

        // 5d. Domain age async check (Phase 7e).
        // If the background checker is running and this domain hasn't been
        // checked yet, send it for async RDAP lookup. The first query
        // passes, but if RDAP says it's suspicious, subsequent queries
        // will be blocked by the dynamicBlocks check above.
        if e.domainAgeOn {
                if _, seen := e.domainAgeSeen.LoadOrStore(domain, true); !seen {
                        // New domain — send for async RDAP check.
                        select {
                        case e.domainAgeChan <- domain:
                        default:
                                // Channel full — drop this check. The domain
                                // will be re-checked on the next query cycle.
                        }
                }
        }

        // 6. CNAME-cloak detection (handled by CheckDNSWithCNAMEs in the VPN
        // layer; this method just records that the cascade reached the end
        // without a match).
        return e.allow(domain, LayerDNS, "no match in any layer")
}

// CheckDNSWithCNAMEs is like CheckDNS but additionally consults the
// CNAME-cloak database: if any CNAME target in the response is a known
// cloak target, the original query domain is blocked at the cascade
// CNAME-cloak layer.
func (e *Engine) CheckDNSWithCNAMEs(domain string, cnames []string) CheckResult {
        res := e.CheckDNS(domain)
        if res.Decision != DecisionAllow {
                return res
        }
        e.mu.RLock()
        cloak := e.cnameCloak
        e.mu.RUnlock()
        for _, c := range cnames {
                c = strings.ToLower(strings.TrimSpace(c))
                if cloak[c] {
                        return e.block(domain, LayerDNS, "CNAME-cloak to "+c, "")
                }
        }
        return res
}

// CheckSNI runs the Smart Cascade for a TLS ClientHello SNI hostname
// (Phase 2). The cascade is identical to DNS but uses LayerSNI for
// forensics/stats.
func (e *Engine) CheckSNI(sni string) CheckResult {
        sni = strings.ToLower(strings.TrimSpace(sni))
        e.stats.incTotals(int(DecisionAllow))
        e.stats.incLayer(LayerSNI)
        if !e.IsLayerEnabled(LayerSNI) {
                return e.allow(sni, LayerSNI, "SNI layer disabled")
        }
        if sni == "" {
                return e.allow(sni, LayerSNI, "empty SNI")
        }
        if e.denylist.Contains(sni) {
                return e.block(sni, LayerSNI, "denylist match", "")
        }
        if e.allowlist.Contains(sni) {
                return e.allow(sni, LayerSNI, "allowlist match")
        }
        if e.bloom.Contains(sni) {
                if e.trie.Contains(sni) {
                        return e.block(sni, LayerSNI, "blocklist match (trie)", "")
                }
        }
        if sdk := e.gamesdk.DetectByDomain(sni); sdk != nil {
                return e.block(sni, LayerSNI, "game SDK ad-server: "+sdk.Name, sdk.Name)
        }
        return e.allow(sni, LayerSNI, "no match in any layer")
}

// CheckHTTPS runs the Smart Cascade for an HTTPS request host/path pair
// (Phase 3). path may be empty. Returns a CheckResult.
func (e *Engine) CheckHTTPS(host, path string) CheckResult {
        host = strings.ToLower(strings.TrimSpace(host))
        e.stats.incTotals(int(DecisionAllow))
        e.stats.incLayer(LayerHTTPS)
        if !e.IsLayerEnabled(LayerHTTPS) {
                return e.allow(host, LayerHTTPS, "HTTPS layer disabled")
        }
        if host == "" {
                return e.allow(host, LayerHTTPS, "empty host")
        }
        if e.denylist.Contains(host) {
                return e.block(host, LayerHTTPS, "denylist match", "")
        }
        if e.allowlist.Contains(host) {
                return e.allow(host, LayerHTTPS, "allowlist match")
        }
        if e.bloom.Contains(host) {
                if e.trie.Contains(host) {
                        return e.block(host, LayerHTTPS, "blocklist match (trie)", "")
                }
        }
        if sdk := e.gamesdk.DetectByDomain(host); sdk != nil {
                return e.block(host, LayerHTTPS, "game SDK ad-server: "+sdk.Name, sdk.Name)
        }
        // Path-based checks (e.g. /ads/, /doubleclick/) would live here in a
        // future expansion.
        _ = path
        return e.allow(host, LayerHTTPS, "no match in any layer")
}

// allow is the internal helper that records an allow decision.
func (e *Engine) allow(domain string, layer int, reason string) CheckResult {
        e.stats.incDecision(int(DecisionAllow))
        tr := &forensics.Trace{
                Timestamp: time.Now(),
                Domain:    domain,
                Layer:     layer,
                Decision:  forensics.DecisionAllow,
                Reason:    reason,
        }
        e.forensics.Record(*tr)
        return CheckResult{
                Decision: DecisionAllow,
                Reason:   reason,
                Layer:    layer,
                Trace:    tr,
        }
}

// block is the internal helper that records a block decision and bumps
// the appropriate stats counters.
func (e *Engine) block(domain string, layer int, reason, sdk string) CheckResult {
        e.mu.RLock()
        def := e.defaultBlock
        e.mu.RUnlock()

        // Correct the incTotals(Allow) call from CheckDNS/CheckSNI/CheckHTTPS.
        e.stats.mu.Lock()
        e.stats.totalAllowed--
        e.stats.totalBlocked++
        e.stats.mu.Unlock()

        e.stats.incDecision(int(def))
        e.stats.incTopBlocked(domain)
        if sdk != "" {
                e.stats.incSDK(sdk)
        }

        dec := forensics.DecisionBlock
        switch def {
        case DecisionNullIP:
                dec = forensics.DecisionBlock // we map NullIP to Block in forensics
        case DecisionNXDOMAIN:
                dec = forensics.DecisionBlock
        }

        tr := &forensics.Trace{
                Timestamp: time.Now(),
                Domain:    domain,
                Layer:     layer,
                Decision:  dec,
                Reason:    reason,
                SDK:       sdk,
        }
        e.forensics.Record(*tr)
        return CheckResult{
                Decision: def,
                Reason:   reason,
                Layer:    layer,
                SDK:      sdk,
                Trace:    tr,
        }
}

// Forensics returns the underlying forensics engine so the UI can pull
// recent traces and recommended fixes.
func (e *Engine) Forensics() *forensics.Engine { return e.forensics }

// Gamesdk returns the underlying Game Ad Intelligence engine.
func (e *Engine) Gamesdk() *gamesdk.Engine { return e.gamesdk }

// Stats returns a snapshot of the engine's stats counters.
func (e *Engine) Stats() statsSnapshot { return e.stats.snapshot() }

// Bloom returns the underlying bloom filter (used by tests).
func (e *Engine) Bloom() *bloom.Filter { return e.bloom }

// Trie returns the underlying blocklist trie (used by tests).
func (e *Engine) Trie() *trie.Trie { return e.trie }

// Denylist returns the underlying denylist trie.
func (e *Engine) Denylist() *trie.Trie { return e.denylist }

// Allowlist returns the underlying allowlist trie.
func (e *Engine) Allowlist() *trie.Trie { return e.allowlist }

// BuildBlockResponseDNS returns a DNS wire-format response appropriate for
// the given decision. For DecisionNullIP it returns BuildNullIP(query);
// for DecisionNXDOMAIN it returns BuildNXDOMAIN(query); for DecisionBlock
// it defaults to NullIP (the safest non-crashing response for game apps).
// For DecisionAllow it returns nil.
// Phase 7b: Added DecisionNullIPv6 (returns AAAA ::) and DecisionNODATA
// (returns NOERROR with empty answer).
func (e *Engine) BuildBlockResponseDNS(decision Decision, query []byte) []byte {
        switch decision {
        case DecisionNXDOMAIN:
                return dns.BuildNXDOMAIN(query)
        case DecisionNullIPv6:
                return dns.BuildNullIPv6(query)
        case DecisionNODATA:
                return dns.BuildNODATA(query)
        case DecisionAllow:
                return nil
        default:
                return dns.BuildNullIP(query)
        }
}
