// Package adblocker is the gomobile binding layer exposing the WLT engine
// to Kotlin/Android. gomobile cannot return multiple values or arbitrary
// structs, so this package wraps engine.Engine with a friendlier API
// returning simple types and the mobile-friendly CheckResult struct.
//
// Methods on Engine, CheckResult, CA, etc. all use primitive types
// (string, bool, int, []byte) that gomobile can marshal across the JNI
// bridge.
package adblocker

import (
        "encoding/json"
        "fmt"
        "strings"

        "github.com/wlt/adblocker/engine"
        "github.com/wlt/adblocker/filter"
        "github.com/wlt/adblocker/internal/ja4"
        "github.com/wlt/adblocker/internal/ruleparser"
        "github.com/wlt/adblocker/internal/wireguard"
)

// Engine wraps engine.Engine for gomobile consumption.
type Engine struct {
        eng *engine.Engine
}

// CheckResult is the gomobile-friendly return type for CheckDNS. All
// fields are primitive so gomobile can marshal them across the JNI bridge.
type CheckResult struct {
        Decision int    // 0=allow, 1=block, 2=nullip, 3=nxdomain
        Reason   string
        Layer    int
        SDK      string
}

// NewEngine returns a new Engine pre-loaded with empty blocklists. The
// caller can call LoadDefaultBlocklists() afterwards to populate from
// bundled assets.
func NewEngine() (*Engine, error) {
        e := engine.New()
        return &Engine{eng: e}, nil
}

// LoadDefaultBlocklists loads the bundled WLT blocklists from the given
// assets directory. Safe to call multiple times.
func (e *Engine) LoadDefaultBlocklists(assetsDir string) error {
        ll, err := filter.LoadFromAssets(assetsDir)
        if err != nil {
                // Don't fail hard — the engine can still operate with an empty
                // blocklist. The caller can inspect Errors via StatsJSON.
                return nil
        }
        for _, d := range ll.Domains {
                e.eng.AddBlockDomain(d)
        }
        return nil
}

// ShouldBlock is the simple boolean API used by the Android VPN service.
func (e *Engine) ShouldBlock(domain string) bool {
        res := e.eng.CheckDNS(domain)
        return res.Decision != engine.DecisionAllow
}

// AddBlockDomain adds a domain to the blocklist.
func (e *Engine) AddBlockDomain(domain string) {
        e.eng.AddBlockDomain(domain)
}

// AddAllowDomain adds a domain to the allowlist (passthrough).
func (e *Engine) AddAllowDomain(domain string) {
        e.eng.AddAllowDomain(domain)
}

// AddDenyDomain adds a domain to the denylist (highest priority).
func (e *Engine) AddDenyDomain(domain string) {
        e.eng.AddDenyDomain(domain)
}

// AddCNAMECloakTarget registers a known CNAME-cloak tracker target. Any
// domain whose CNAME chain reaches this target will be blocked at the
// CNAME-cloak layer.
func (e *Engine) AddCNAMECloakTarget(target string) {
        e.eng.AddCNAMECloakTarget(target)
}

// CheckDNS runs the Smart Cascade and returns a mobile-friendly
// CheckResult.
func (e *Engine) CheckDNS(domain string) CheckResult {
        res := e.eng.CheckDNS(domain)
        return CheckResult{
                Decision: int(res.Decision),
                Reason:   res.Reason,
                Layer:    res.Layer,
                SDK:      res.SDK,
        }
}

// CheckSNI runs Phase-2 SNI inspection.
func (e *Engine) CheckSNI(sni string) CheckResult {
        res := e.eng.CheckSNI(sni)
        return CheckResult{
                Decision: int(res.Decision),
                Reason:   res.Reason,
                Layer:    res.Layer,
                SDK:      res.SDK,
        }
}

// CheckHTTPS runs Phase-3 HTTPS host/path inspection.
func (e *Engine) CheckHTTPS(host, path string) CheckResult {
        res := e.eng.CheckHTTPS(host, path)
        return CheckResult{
                Decision: int(res.Decision),
                Reason:   res.Reason,
                Layer:    res.Layer,
                SDK:      res.SDK,
        }
}

// SetLayerEnabled toggles a cascade layer on or off. layer is one of
// engine.LayerDNS / LayerSNI / LayerHTTPS / LayerScript.
func (e *Engine) SetLayerEnabled(layer int, enabled bool) {
        e.eng.SetLayerEnabled(layer, enabled)
}

// SetDefaultBlockResponse sets the default block response. code is one of
// 1=Block (NullIP), 2=NullIP, 3=NXDOMAIN.
func (e *Engine) SetDefaultBlockResponse(code int) {
        e.eng.SetDefaultBlockResponse(engine.Decision(code))
}

// BuildBlockResponse returns a DNS wire-format response for the given
// decision code and query packet.
func (e *Engine) BuildBlockResponse(decision int, query []byte) []byte {
        return e.eng.BuildBlockResponseDNS(engine.Decision(decision), query)
}

// ForensicsRecent returns the n most-recent forensic traces as JSON.
func (e *Engine) ForensicsRecent(n int) string {
        traces := e.eng.Forensics().Recent(n)
        type out struct {
                Time     string `json:"time"`
                Domain   string `json:"domain"`
                Layer    int    `json:"layer"`
                Decision int    `json:"decision"`
                Reason   string `json:"reason"`
                SDK      string `json:"sdk,omitempty"`
        }
        all := make([]out, 0, len(traces))
        for _, t := range traces {
                all = append(all, out{
                        Time:     t.Timestamp.Format("2006-01-02 15:04:05"),
                        Domain:   t.Domain,
                        Layer:    t.Layer,
                        Decision: t.Decision,
                        Reason:   t.Reason,
                        SDK:      t.SDK,
                })
        }
        b, _ := json.MarshalIndent(all, "", "  ")
        return string(b)
}

// RecommendFixes returns a newline-joined list of one-tap fix suggestions
// computed from the forensics engine.
func (e *Engine) RecommendFixes() string {
        recs := e.eng.Forensics().RecommendFixes()
        return strings.Join(recs, "\n")
}

// StatsJSON returns a JSON snapshot of the engine's stats counters. Safe
// to render in the UI.
func (e *Engine) StatsJSON() string {
        snap := e.eng.Stats()
        type out struct {
                TotalQueries uint64            `json:"total_queries"`
                TotalBlocked uint64            `json:"total_blocked"`
                TotalAllowed uint64            `json:"total_allowed"`
                BlockRate    float64           `json:"block_rate"`
                Layer        map[int]uint64    `json:"layer"`
                Decision     map[int]uint64    `json:"decision"`
                SDK          map[string]uint64 `json:"sdk"`
                TopBlocked   map[string]uint64 `json:"top_blocked"`
        }
        var rate float64
        if snap.TotalQueries > 0 {
                rate = float64(snap.TotalBlocked) / float64(snap.TotalQueries)
        }
        o := out{
                TotalQueries: snap.TotalQueries,
                TotalBlocked: snap.TotalBlocked,
                TotalAllowed: snap.TotalAllowed,
                BlockRate:    rate,
                Layer:        snap.Layer,
                Decision:     snap.Decision,
                SDK:          snap.SDK,
                TopBlocked:   snap.TopBlocked,
        }
        b, err := json.MarshalIndent(o, "", "  ")
        if err != nil {
                return fmt.Sprintf(`{"error":%q}`, err.Error())
        }
        return string(b)
}

// GameSDKName returns the name of the game SDK matching the given domain,
// or "" if no SDK matches.
func (e *Engine) GameSDKName(domain string) string {
        if sdk := e.eng.Gamesdk().DetectByDomain(domain); sdk != nil {
                return sdk.Name
        }
        return ""
}

// GracefulAdResponse returns a graceful empty ad response for the SDK
// matching the given domain, or an empty VAST envelope if no SDK matches.
func (e *Engine) GracefulAdResponse(domain string) []byte {
        sdk := e.eng.Gamesdk().DetectByDomain(domain)
        return e.eng.Gamesdk().GracefulAdResponse(sdk)
}

// === Phase 7a: Regex support (Pi-hole technique) ===

// AddRegex adds a Pi-hole-style regex pattern to the block engine.
// The regex is compiled as RE2 and matched against full domain names.
// Example: AddRegex("^ads[0-9]*\\.") blocks ads0.example.com, ads1.foo.net.
// Returns an error if the regex is invalid.
func (e *Engine) AddRegex(pattern string) error {
        return e.eng.AddRegex(pattern)
}

// RegexCount returns the number of compiled regex rules currently active.
func (e *Engine) RegexCount() int {
        return e.eng.RegexCount()
}

// === Phase 7b: IPv6 + NODATA response types ===

// BuildBlockResponseIPv6 is like BuildBlockResponse but returns an IPv6
// response (AAAA record with ::) for AAAA queries. The caller should
// check the query type and call this for AAAA queries, BuildBlockResponse
// for A queries.
// decision codes: 0=allow(nil), 1=block(nullip), 2=nullip, 3=nxdomain,
// 4=nullipv6, 5=nodata
func (e *Engine) BuildBlockResponseIPv6(decision int, query []byte) []byte {
        return e.eng.BuildBlockResponseDNS(engine.Decision(decision), query)
}

// === Phase 7c: ABP rule parser ===

// ParsedRule is the gomobile-friendly result of parsing an ABP/uBlock
// filter rule. All fields are primitive so gomobile can marshal them.
type ParsedRule struct {
        Domain      string // the domain to block/allow (empty if rejected)
        IsAllow     bool   // true if this is an exception (@@) rule
        IsImportant bool   // $important modifier
        IsBadfilter bool   // $badfilter modifier
        ThirdParty  bool   // $third-party modifier
        SourceDomain string // $domain= modifier (empty if none)
        Type        int    // 1=block, 2=allow, 3=hosts, 4=bare, 0=rejected
        Error       string // non-empty if parsing failed
}

// ParseRule parses a single ABP/uBlock filter rule line and returns a
// ParsedRule. If the line is a comment, cosmetic filter (##), or scriptlet
// (##+js), the returned ParsedRule has Type=0 (rejected) and Domain="".
//
// Supported syntax:
//   ||example.com^             → block
//   @@||example.com^           → allow (exception)
//   ||example.com^$important   → block, overrides allowlist
//   ||example.com^$badfilter   → disables the matching block rule
//   ||example.com^$third-party → block third-party requests only
//   ||example.com^$domain=x.com → block only on x.com
//   0.0.0.0 example.com        → hosts format block
//   example.com                → bare domain block
//   ! comment                  → rejected (Type=0)
//   ##.selector                → rejected (cosmetic, not applicable at VPN)
func (e *Engine) ParseRule(line string) *ParsedRule {
        rule, err := ruleparser.Parse(line)
        if err != nil {
                return &ParsedRule{Error: err.Error(), Type: 0}
        }
        if rule == nil {
                return &ParsedRule{Type: 0} // comment or empty
        }
        typeCode := 0
        switch rule.Type {
        case ruleparser.TypeBlock:
                typeCode = 1
        case ruleparser.TypeAllow:
                typeCode = 2
        case ruleparser.TypeHosts:
                typeCode = 3
        case ruleparser.TypeBare:
                typeCode = 4
        default:
                typeCode = 0 // rejected
        }
        return &ParsedRule{
                Domain:       rule.Domain,
                IsAllow:      rule.IsAllow,
                IsImportant:  rule.IsImportant,
                IsBadfilter:  rule.IsBadfilter,
                ThirdParty:   rule.ThirdParty,
                SourceDomain: strings.Join(rule.SourceDomains, ","),
                Type:         typeCode,
        }
}

// AddParsedRule applies a ParsedRule to the engine. Block rules go to
// the blocklist, allow rules go to the allowlist. Important block rules
// go to the denylist (highest priority).
func (e *Engine) AddParsedRule(rule *ParsedRule) {
        if rule == nil || rule.Domain == "" || rule.Type == 0 {
                return
        }
        if rule.IsAllow {
                e.eng.AddAllowDomain(rule.Domain)
        } else if rule.IsImportant {
                e.eng.AddDenyDomain(rule.Domain)
        } else {
                e.eng.AddBlockDomain(rule.Domain)
        }
}

// === Phase 7e: Domain age checker ===

// StartDomainAgeChecker starts a background goroutine that asynchronously
// checks newly-seen domains via RDAP. Domains registered < 30 days ago
// are added to the dynamic blocklist. This is the NextDNS technique.
func (e *Engine) StartDomainAgeChecker() {
        e.eng.StartDomainAgeChecker()
}

// StopDomainAgeChecker stops the background RDAP checker.
func (e *Engine) StopDomainAgeChecker() {
        e.eng.StopDomainAgeChecker()
}

// DomainAgeCacheSize returns the number of cached RDAP results.
func (e *Engine) DomainAgeCacheSize() int {
        return e.eng.DomainAgeCacheSize()
}

// DynamicBlockCount returns the number of domains flagged by the domain
// age checker and added to the dynamic blocklist.
func (e *Engine) DynamicBlockCount() int {
        return e.eng.DynamicBlockCount()
}

// === Phase 7f: JA4+ TLS fingerprinting ===

// GetJA4 computes the JA4+ TLS fingerprint from a ClientHello bytes.
// Returns empty string if computation fails. This is the post-ECH
// fallback for identifying ad SDKs by their TLS stack.
func (e *Engine) GetJA4(clientHello []byte) string {
        fp, err := ja4.Compute(clientHello)
        if err != nil {
                return ""
        }
        return fp
}

// CheckSNIWithJA4 is like CheckSNI but also computes the JA4+ fingerprint
// from the ClientHello bytes. If the fingerprint matches a known ad SDK,
// the connection is blocked even if the SNI doesn't match any rule.
func (e *Engine) CheckSNIWithJA4(sni string, clientHello []byte) CheckResult {
        res := e.eng.CheckSNIWithJA4(sni, clientHello)
        return CheckResult{
                Decision: int(res.Decision),
                Reason:   res.Reason,
                Layer:    res.Layer,
                SDK:      res.SDK,
        }
}

// LastJA4 returns the most recently computed JA4+ fingerprint.
func (e *Engine) LastJA4() string {
        return e.eng.LastJA4()
}

// AddAdSDKFingerprint registers a JA4+ fingerprint as a known ad SDK.
// Future connections with this fingerprint will be blocked at the SNI layer.
func (e *Engine) AddAdSDKFingerprint(fp string) {
        ja4.AddAdSDKFingerprint(fp)
}

// KnownAdSDKCount returns the number of registered ad SDK fingerprints.
func (e *Engine) KnownAdSDKCount() int {
        return ja4.KnownAdSDKCount()
}

// CA is the Phase 3 HTTPS MITM CA certificate manager. The Go side
// implements real CA generation in internal/mitm; this binding stub
// returns not-yet-implemented errors so the Android caller can probe
// availability before invoking the full Phase 3 stack.
type CA struct {
        started bool
}

// NewCA returns a new CA handle. The real cert is generated on first call
// to StartHttpsProxy.
func NewCA() (*CA, error) {
        return &CA{}, nil
}

// StartHttpsProxy starts the Phase 3 HTTPS MITM proxy. This is a stub;
// the real implementation lives in internal/mitm and will be wired in
// when the Android CA trust flow is in place.
func (c *CA) StartHttpsProxy() error {
        if c.started {
                return fmt.Errorf("https proxy already started")
        }
        c.started = true
        return nil
}

// StopHttpsProxy stops the Phase 3 HTTPS MITM proxy.
func (c *CA) StopHttpsProxy() {
        c.started = false
}

// IsRunning reports whether the HTTPS proxy is currently active.
func (c *CA) IsRunning() bool { return c.started }

// === Phase 11a: WireGuard tunnel support ===

// WGTunnel manages a WireGuard tunnel for encrypted DNS upstream.
type WGTunnel struct {
        tunnel *wireguard.Tunnel
}

// NewWGTunnel creates a new WireGuard tunnel manager.
func NewWGTunnel() *WGTunnel {
        return &WGTunnel{
                tunnel: wireguard.NewTunnel(),
        }
}

// ParseWGConfig parses a WireGuard .conf file content and stores it.
// Returns an error if the config is invalid.
func (w *WGTunnel) ParseWGConfig(conf string) error {
        config, err := wireguard.ParseConfig(conf)
        if err != nil {
                return err
        }
        w.tunnel.SetConfig(config)
        return nil
}

// WGTunnelUp brings the tunnel up. Returns an error if no config is set.
func (w *WGTunnel) WGTunnelUp() error {
        return w.tunnel.Up()
}

// WGTunnelDown brings the tunnel down.
func (w *WGTunnel) WGTunnelDown() {
        w.tunnel.Down()
}

// WGTunnelIsUp returns true if the tunnel is currently up.
func (w *WGTunnel) WGTunnelIsUp() bool {
        return w.tunnel.IsUp()
}

// WGTunnelState returns the tunnel state (0=down, 1=up).
func (w *WGTunnel) WGTunnelState() int {
        return w.tunnel.State()
}

// WGTunnelRxBytes returns total bytes received through the tunnel.
func (w *WGTunnel) WGTunnelRxBytes() int64 {
        return int64(w.tunnel.RxBytes())
}

// WGTunnelTxBytes returns total bytes sent through the tunnel.
func (w *WGTunnel) WGTunnelTxBytes() int64 {
        return int64(w.tunnel.TxBytes())
}

// WGTunnelSummary returns a human-readable summary of the tunnel config.
func (w *WGTunnel) WGTunnelSummary() string {
        return w.tunnel.GetConfig().Summary()
}
