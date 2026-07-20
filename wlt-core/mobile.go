// Package mobile is the gomobile binding layer.
// Exposes the Go engine + HTTPS proxy + CA management to Android.
package mobile

import (
	"wlt-core/engine"
	"wlt-core/internal/mitm"
	"wlt-core/internal/httpsproxy"
	"wlt-core/internal/trie"
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
	eng    *engine.Engine
	ca     *mitm.CertificateAuthority
	proxy  *httpsproxy.Proxy
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

// BlocklistSize returns the number of blocklist rules.
func (e *Engine) BlocklistSize() int {
	return e.eng.BlocklistSize()
}

// AllowlistSize returns the number of allowlist rules.
func (e *Engine) AllowlistSize() int {
	return e.eng.AllowlistSize()
}

// TotalBlocked returns total blocked count.
func (e *Engine) TotalBlocked() int64 {
	return e.eng.TotalBlocked()
}

// TotalAllowed returns total allowed count.
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

// NewCA generates a local CA for HTTPS MITM. Returns PEM-encoded cert.
func NewCA() string {
	ca, err := mitm.NewCA()
	if err != nil {
		return ""
	}
	return string(ca.CAPEM())
}

// StartHttpsProxy starts the local HTTPS interception proxy.
// Returns the port it's listening on, or 0 on failure.
func (e *Engine) StartHttpsProxy() int {
	if e.ca == nil {
		var err error
		e.ca, err = mitm.NewCA()
		if err != nil {
			return 0
		}
	}
	blockTrie := trie.New()
	allowTrie := trie.New()
	e.proxy = httpsproxy.New(e.ca, blockTrie, allowTrie)
	err := e.proxy.Start("127.0.0.1:8443")
	if err != nil {
		return 0
	}
	return 8443
}

// StopHttpsProxy stops the HTTPS proxy.
func (e *Engine) StopHttpsProxy() {
	if e.proxy != nil {
		e.proxy.Stop()
		e.proxy = nil
	}
}

// IsHttpsProxyRunning returns whether the proxy is active.
func (e *Engine) IsHttpsProxyRunning() bool {
	return e.proxy != nil
}

// HttpsProxyStatsJSON returns proxy statistics as JSON.
func (e *Engine) HttpsProxyStatsJSON() string {
	if e.proxy == nil {
		return `{"running":false}`
	}
	s := e.proxy.GetStats()
	return `{"running":true,"connections":` + string(itoa(s.Connections)) +
		`,"requestsInspected":` + string(itoa(s.RequestsInspected)) +
		`,"responsesFiltered":` + string(itoa(s.ResponsesFiltered)) +
		`,"scriptletsInjected":` + string(itoa(s.ScriptletsInjected)) +
		`,"m3uPruned":` + string(itoa(s.M3uPruned)) +
		`,"bytesRelayed":` + string(itoa(s.BytesRelayed)) + `}`
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
