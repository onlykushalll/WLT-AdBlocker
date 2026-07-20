// Package mobile is the gomobile binding layer.
// Updated to match the simplified engine API.
package mobile

import (
        "wlt-core/engine"
        "wlt-core/internal/mitm"
)

// DnsResult holds the result of a DNS check.
type DnsResult struct {
        Block    bool
        Response []byte
        Reason   string
}

// CheckResult holds the result of a non-DNS check.
type CheckResult struct {
        Block  bool
        Reason string
}

// Engine is the mobile-facing wrapper.
type Engine struct {
        eng *engine.Engine
}

// NewEngine creates a new engine.
func NewEngine() *Engine {
        return &Engine{eng: engine.New()}
}

// ShouldBlock checks if a domain should be blocked.
func (e *Engine) ShouldBlock(domain string) bool {
        return e.eng.ShouldBlock(domain)
}

// AddBlockDomain adds a domain to the blocklist.
func (e *Engine) AddBlockDomain(domain string) {
        e.eng.AddBlockDomain(domain)
}

// AddAllowDomain adds a domain to the allowlist.
func (e *Engine) AddAllowDomain(domain string) {
        e.eng.AddAllowDomain(domain)
}

// AddDenyDomain adds a user-forced block.
func (e *Engine) AddDenyDomain(domain string) {
        e.eng.AddDenyDomain(domain)
}

// BlocklistSize returns the number of blocklist rules.
func (e *Engine) BlocklistSize() int {
        return e.eng.BlocklistSize()
}

// AllowlistSize returns the number of allowlist rules.
func (e *Engine) AllowlistSize() int {
        return e.eng.AllowlistSize()
}

// TotalBlocked returns the total blocked count.
func (e *Engine) TotalBlocked() int64 {
        return e.eng.TotalBlocked()
}

// TotalAllowed returns the total allowed count.
func (e *Engine) TotalAllowed() int64 {
        return e.eng.TotalAllowed()
}

// StatsJSON returns stats as JSON string.
func (e *Engine) StatsJSON() string {
        return `{"totalBlocked":` + string(itoa(e.eng.TotalBlocked())) +
                `,"totalAllowed":` + string(itoa(e.eng.TotalAllowed())) +
                `,"blocklistSize":` + string(itoa(int64(e.eng.BlocklistSize()))) +
                `,"allowlistSize":` + string(itoa(int64(e.eng.AllowlistSize()))) + `}`
}

// CAExport exports the MITM CA certificate for user installation.
// Returns PEM-encoded certificate, or empty string if CA not generated.
func (e *Engine) CAExport() string {
        // CA is generated on demand — this is a placeholder for the mobile binding
        // The actual CA generation happens in the HTTPS proxy service
        return ""
}

// NewCA generates a new local CA for HTTPS MITM (Phase 3).
// Returns the PEM-encoded CA certificate for user installation.
func NewCA() string {
        ca, err := mitm.NewCA()
        if err != nil {
                return ""
        }
        return string(ca.CAPEM())
}

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
