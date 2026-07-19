// Package mobile is the gomobile binding layer exposing the WLT engine to
// Android (Kotlin) via gomobile bind.
//
// gomobile restriction: methods can return at most 1 value + optional error.
// So we use result structs instead of multi-return.
//
// Build with:
//   gomobile bind -target=android/arm64,android/arm,android/386,android/amd64 \
//                 -androidapi 24 -o wlt.aar .
package mobile

import (
        "wlt-core/engine"
        "wlt-core/filter"
        "wlt-core/internal/forensics"
)

// DnsResult holds the result of a DNS check.
type DnsResult struct {
        Block   bool
        Response []byte
        Reason  string
}

// CheckResult holds the result of a non-DNS check (SNI/IP).
type CheckResult struct {
        Block  bool
        Reason string
}

// Engine is the mobile-facing wrapper around engine.Engine.
type Engine struct {
        eng    *engine.Engine
        loader *filter.Loader
}

// NewEngine creates a new engine with default Phase 1 config.
func NewEngine() *Engine {
        cfg := engine.DefaultConfig()
        return &Engine{
                eng:    engine.New(cfg),
                loader: filter.NewLoader(),
        }
}

// NewEngineWithConfig creates an engine with custom layer toggles.
func NewEngineWithConfig(enableDNS, enableSNI, enableHTTPS, enableScriptlet bool) *Engine {
        cfg := engine.DefaultConfig()
        cfg.EnableDNS = enableDNS
        cfg.EnableSNI = enableSNI
        cfg.EnableHTTPS = enableHTTPS
        cfg.EnableScriptlet = enableScriptlet
        return &Engine{
                eng:    engine.New(cfg),
                loader: filter.NewLoader(),
        }
}

// CheckDNS evaluates a raw DNS packet. Returns a DnsResult struct.
func (e *Engine) CheckDNS(query []byte, response []byte) *DnsResult {
        d, resp := e.eng.CheckDNS(query, response)
        return &DnsResult{
                Block:    d.Block,
                Response: resp,
                Reason:   d.Reason,
        }
}

// CheckSNI evaluates a TLS ClientHello payload. Returns a CheckResult.
func (e *Engine) CheckSNI(payload []byte) *CheckResult {
        d := e.eng.CheckSNI(payload)
        return &CheckResult{Block: d.Block, Reason: d.Reason}
}

// CheckIP evaluates a destination IP. Returns a CheckResult.
func (e *Engine) CheckIP(ip string) *CheckResult {
        d := e.eng.CheckIP(ip)
        return &CheckResult{Block: d.Block, Reason: d.Reason}
}

// AddBlockDomain adds a domain to the blocklist at runtime.
func (e *Engine) AddBlockDomain(domain string) {
        e.eng.AddBlockDomain(domain)
}

// AddBlockDomains adds many domains.
func (e *Engine) AddBlockDomains(domains []string) {
        e.eng.AddBlockDomains(domains)
}

// AddAllowDomain adds a passthrough domain.
func (e *Engine) AddAllowDomain(domain string) {
        e.eng.AddAllowDomain(domain)
}

// AddDenyDomain adds a user-forced block.
func (e *Engine) AddDenyDomain(domain string) {
        e.eng.AddDenyDomain(domain)
}

// LoadBlocklistFile loads a local blocklist file.
// format: 0=auto, 1=hosts, 2=adblock, 3=domains
func (e *Engine) LoadBlocklistFile(path string, format int) int {
        src := filter.Source{Path: path, Format: filter.Format(format), Enabled: true}
        count := 0
        e.loader.Load(src, func(d string) {
                e.eng.AddBlockDomain(d)
                count++
        })
        return count
}

// LoadAllowlistFile loads a passthrough file.
func (e *Engine) LoadAllowlistFile(path string, format int) int {
        src := filter.Source{Path: path, Format: filter.Format(format), Enabled: true}
        count := 0
        e.loader.Load(src, func(d string) {
                e.eng.AddAllowDomain(d)
                count++
        })
        return count
}

// StatsJSON returns aggregate stats as JSON.
func (e *Engine) StatsJSON() string {
        s := e.eng.GetStats()
        var b []byte
        b = append(b, '{')
        b = append(b, `"totalQueries":`...)
        b = append(b, itoa(s.TotalQueries)...)
        b = append(b, `,"totalBlocked":`...)
        b = append(b, itoa(s.TotalBlocked)...)
        b = append(b, `,"totalAllowed":`...)
        b = append(b, itoa(s.TotalAllowed)...)
        b = append(b, `,"blocklistSize":`...)
        b = append(b, itoa(int64(e.eng.BlocklistSize()))...)
        b = append(b, `,"allowlistSize":`...)
        b = append(b, itoa(int64(e.eng.AllowlistSize()))...)
        b = append(b, '}')
        return string(b)
}

// BlocklistSize returns the number of blocklist rules loaded.
func (e *Engine) BlocklistSize() int {
        return e.eng.BlocklistSize()
}

// AllowlistSize returns the number of allowlist rules loaded.
func (e *Engine) AllowlistSize() int {
        return e.eng.AllowlistSize()
}

// SetLayerEnabled toggles a cascade layer.
// layer: 1=DNS, 2=SNI, 3=HTTPS, 4=Scriptlet
func (e *Engine) SetLayerEnabled(layer int, enabled bool) {
        e.eng.EnableLayer(forensics.Layer(layer), enabled)
}

// IsLayerEnabled checks if a layer is active.
func (e *Engine) IsLayerEnabled(layer int) bool {
        return e.eng.IsLayerEnabled(forensics.Layer(layer))
}

// ClearForensics wipes the forensic trace buffer.
func (e *Engine) ClearForensics() {
        e.eng.Forensics().Clear()
}

// itoa is a minimal int64 -> string.
func itoa(n int64) []byte {
        if n == 0 {
                return []byte{'0'}
        }
        neg := false
        if n < 0 {
                neg = true
                n = -n
        }
        var buf [20]byte
        i := len(buf)
        for n > 0 {
                i--
                buf[i] = byte('0' + n%10)
                n /= 10
        }
        if neg {
                i--
                buf[i] = '-'
        }
        return buf[i:]
}
