// Package forensics implements the WLT Ad Forensics engine: a bounded
// ring buffer that records per-layer decisions and lets the UI answer
// "why did this ad get through?" with concrete trace data plus recommended
// one-tap fixes.
package forensics

import (
	"sync"
	"time"
)

// Decision constants used by Trace.Decision. Mirrors engine.Decision but
// kept as plain ints in this package to avoid an import cycle.
const (
	DecisionAllow    = 0
	DecisionBlock    = 1
	DecisionNullIP   = 2
	DecisionNXDOMAIN = 3
)

// Layer constants used by Trace.Layer. Mirrors engine layer codes.
const (
	LayerDNS     = 0
	LayerSNI     = 1
	LayerHTTPS   = 2
	LayerScript  = 3
	LayerCascade = 4
)

// Trace records a single per-layer decision for one request.
type Trace struct {
	Timestamp time.Time
	Domain    string
	Layer     int
	Decision  int
	Reason    string
	SDK       string
}

// Engine is a bounded ring buffer of forensic Traces plus recommendation
// helpers. It is safe for concurrent use.
type Engine struct {
	mu      sync.RWMutex
	buf     []Trace
	head    int // next write position
	size    int // number of valid entries
	cap     int
	reasons map[string]int // domain -> count of allows (for fix recommendations)
	sdkHits map[string]int // sdk -> count of blocks
}

// New returns an Engine with capacity entries (use 5000 for the production
// default).
func New(capacity int) *Engine {
	if capacity < 1 {
		capacity = 5000
	}
	return &Engine{
		buf:     make([]Trace, capacity),
		cap:     capacity,
		reasons: make(map[string]int),
		sdkHits: make(map[string]int),
	}
}

// Record appends a trace to the ring buffer. If the buffer is full the
// oldest entry is overwritten.
func (e *Engine) Record(t Trace) {
	if t.Timestamp.IsZero() {
		t.Timestamp = time.Now()
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.buf[e.head] = t
	e.head = (e.head + 1) % e.cap
	if e.size < e.cap {
		e.size++
	}
	// Track stats for recommendations. Allow decisions with a non-empty
	// reason (suggesting the allowlist let something through) bump the
	// per-domain allow counter.
	if t.Decision == DecisionAllow && t.Domain != "" {
		e.reasons[t.Domain]++
	}
	if t.Decision == DecisionBlock && t.SDK != "" {
		e.sdkHits[t.SDK]++
	}
}

// Recent returns the n most-recent traces, newest last. If n > size, all
// available traces are returned.
func (e *Engine) Recent(n int) []Trace {
	e.mu.RLock()
	defer e.mu.RUnlock()
	if n <= 0 {
		return nil
	}
	if n > e.size {
		n = e.size
	}
	out := make([]Trace, 0, n)
	// The oldest entry is at (head - size + cap) % cap when buffer is full,
	// or at index 0 when not full.
	start := (e.head - e.size + e.cap) % e.cap
	for i := 0; i < n; i++ {
		idx := (start + i) % e.cap
		out = append(out, e.buf[idx])
	}
	return out
}

// Size returns the number of traces currently stored.
func (e *Engine) Size() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.size
}

// RecommendFixes returns a list of human-readable one-tap fix
// recommendations based on recorded traces. Each entry is a suggestion the
// user can act on from the UI (e.g. add a domain to the blocklist, enable a
// layer they disabled).
func (e *Engine) RecommendFixes() []string {
	e.mu.RLock()
	defer e.mu.RUnlock()
	var out []string

	// Find the most-allowed domain (suggest blocking it).
	var topDomain string
	var topCount int
	for d, c := range e.reasons {
		if c > topCount {
			topCount = c
			topDomain = d
		}
	}
	if topDomain != "" && topCount >= 3 {
		out = append(out, "Domain '"+topDomain+"' was allowed "+itoa(topCount)+
			" times — consider adding it to the denylist.")
	}

	// SDK leaderboard.
	var topSDK string
	var topSDKCount int
	for s, c := range e.sdkHits {
		if c > topSDKCount {
			topSDKCount = c
			topSDK = s
		}
	}
	if topSDK != "" {
		out = append(out, "Game SDK '"+topSDK+"' was blocked "+itoa(topSDKCount)+
			" times — consider enabling the graceful-ad-response mode to prevent crashes.")
	}

	// Generic reminder if buffer is filling up.
	if e.size >= e.cap-100 {
		out = append(out, "Forensic buffer is near capacity — old traces are being overwritten.")
	}

	// Always include at least one entry so the UI has something to render.
	if len(out) == 0 {
		out = append(out, "No forensic issues detected. Smart Cascade is operating normally.")
	}
	return out
}

// itoa is a tiny strconv.Itoa-free helper to keep the package dependency
// surface minimal.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := false
	if n < 0 {
		neg = true
		n = -n
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
