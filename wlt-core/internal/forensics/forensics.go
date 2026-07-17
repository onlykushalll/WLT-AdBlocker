// Package forensics implements WLT's "Ad Forensics" engine — the feature that
// no other adblocker has. When an ad slips past, WLT explains exactly which
// layer missed it and recommends a one-tap fix.
//
// Each DNS/HTTPS/connection event is recorded with a forensic trace:
//   - Which layers evaluated it (DNS, SNI, HTTPS, Scriptlet)
//   - What each layer decided (block, allow, unknown)
//   - Why a layer would have blocked it (if enabled)
//   - Recommended action to fix future misses
package forensics

import (
	"sync"
	"time"
)

// Layer identifies a blocking layer in the Smart Cascade.
type Layer int

const (
	LayerDNS       Layer = 1
	LayerSNI       Layer = 2
	LayerHTTPS     Layer = 3
	LayerScriptlet Layer = 4
)

func (l Layer) String() string {
	switch l {
	case LayerDNS:
		return "DNS"
	case LayerSNI:
		return "SNI"
	case LayerHTTPS:
		return "HTTPS"
	case LayerScriptlet:
		return "Scriptlet"
	default:
		return "Unknown"
	}
}

// Decision is what a layer decided about a request.
type Decision int

const (
	DecisionBlock      Decision = 1 // layer blocked this request
	DecisionAllow      Decision = 2 // layer explicitly allowed it
	DecisionMiss       Decision = 3 // layer didn't match (ad got through here)
	DecisionNotChecked Decision = 4 // layer wasn't evaluated (disabled or not applicable)
	DecisionNotApplicable Decision = 5 // layer can't apply to this request type
)

func (d Decision) String() string {
	switch d {
	case DecisionBlock:
		return "BLOCKED"
	case DecisionAllow:
		return "ALLOWED"
	case DecisionMiss:
		return "MISSED"
	case DecisionNotChecked:
		return "N/A (disabled)"
	case DecisionNotApplicable:
		return "N/A (not applicable)"
	default:
		return "Unknown"
	}
}

// LayerResult is one layer's evaluation of a single request.
type LayerResult struct {
	Layer       Layer
	Decision    Decision
	Rule        string  // which rule matched (if any)
	Reason      string  // human-readable explanation
	WouldBlock  bool    // if this layer were enabled, would it have blocked?
	FixAction   string  // one-tap fix recommendation if it missed
}

// Trace is the full forensic record for one request.
type Trace struct {
	ID         int64
	Timestamp  time.Time
	Domain     string
	Path       string // URL path (for HTTPS layer)
	IP         string
	Port       int
	PackageUID int
	Package    string
	SDK        string // detected game SDK, if any
	Results    []LayerResult
	FinalBlock bool
}

// Recorder stores forensic traces. Bounded by a ring buffer to limit memory.
type Recorder struct {
	mu      sync.Mutex
	traces  []*Trace
	maxSize int
	nextID  int64
	// missIndex: domain -> count of times it slipped through
	missIndex map[string]int
}

// NewRecorder returns a recorder holding up to maxSize traces.
func NewRecorder(maxSize int) *Recorder {
	if maxSize <= 0 {
		maxSize = 5000
	}
	return &Recorder{
		traces:    make([]*Trace, 0, maxSize),
		maxSize:   maxSize,
		missIndex: make(map[string]int),
	}
}

// Record adds a trace. If the request slipped through (FinalBlock=false but
// a layer said WouldBlock=true), the domain's miss counter is incremented.
func (r *Recorder) Record(t *Trace) {
	r.mu.Lock()
	defer r.mu.Unlock()
	t.ID = r.nextID
	r.nextID++
	if t.Timestamp.IsZero() {
		t.Timestamp = time.Now()
	}
	if len(r.traces) >= r.maxSize {
		// drop oldest
		r.traces = r.traces[1:]
	}
	r.traces = append(r.traces, t)
	// Track misses for the "why did this ad get through" feature.
	if !t.FinalBlock {
		for _, lr := range t.Results {
			if lr.WouldBlock {
				r.missIndex[t.Domain]++
				break
			}
		}
	}
}

// RecentTraces returns the last n traces.
func (r *Recorder) RecentTraces(n int) []*Trace {
	r.mu.Lock()
	defer r.mu.Unlock()
	if n <= 0 || n > len(r.traces) {
		n = len(r.traces)
	}
	start := len(r.traces) - n
	out := make([]*Trace, n)
	copy(out, r.traces[start:])
	return out
}

// TopMissedDomains returns domains that slipped through most often,
// along with the recommended fix (the layer that would have blocked them).
// This powers the "Ad Forensics" dashboard tile: "X ads slipped past today,
// here's why and how to fix it."
func (r *Recorder) TopMissedDomains(n int) []MissSummary {
	r.mu.Lock()
	defer r.mu.Unlock()
	type pair struct {
		domain string
		count  int
		fix    string
		layer  Layer
	}
	var pairs []pair
	for domain, count := range r.missIndex {
		// Find the recommended fix for this domain.
		fix := "Enable more blocking layers"
		layer := LayerDNS
		for i := len(r.traces) - 1; i >= 0; i-- {
			t := r.traces[i]
			if t.Domain == domain {
				for _, lr := range t.Results {
					if lr.WouldBlock {
						fix = lr.FixAction
						layer = lr.Layer
						break
					}
				}
				break
			}
		}
		pairs = append(pairs, pair{domain, count, fix, layer})
	}
	// Sort by count desc (simple insertion sort — n is small)
	for i := 1; i < len(pairs); i++ {
		for j := i; j > 0 && pairs[j].count > pairs[j-1].count; j-- {
			pairs[j], pairs[j-1] = pairs[j-1], pairs[j]
		}
	}
	if n > len(pairs) {
		n = len(pairs)
	}
	out := make([]MissSummary, 0, n)
	for i := 0; i < n; i++ {
		out = append(out, MissSummary{
			Domain:        pairs[i].domain,
			MissCount:     pairs[i].count,
			FixAction:     pairs[i].fix,
			SuggestedLayer: pairs[i].layer,
		})
	}
	return out
}

// MissSummary is a digest entry for the "why ads got through" dashboard.
type MissSummary struct {
	Domain         string
	MissCount      int
	FixAction      string
	SuggestedLayer Layer
}

// Stats returns aggregate counts for the dashboard.
type Stats struct {
	TotalTraces  int
	TotalBlocked int
	TotalMissed  int
	UniqueMissed int
	TopFixLayer  Layer
}

func (r *Recorder) Stats() Stats {
	r.mu.Lock()
	defer r.mu.Unlock()
	s := Stats{TotalTraces: len(r.traces), UniqueMissed: len(r.missIndex)}
	for _, t := range r.traces {
		if t.FinalBlock {
			s.TotalBlocked++
		} else {
			missed := false
			for _, lr := range t.Results {
				if lr.WouldBlock {
					missed = true
					break
				}
			}
			if missed {
				s.TotalMissed++
			}
		}
	}
	// Find top suggested layer.
	layerCounts := map[Layer]int{}
	for domain := range r.missIndex {
		for i := len(r.traces) - 1; i >= 0; i-- {
			t := r.traces[i]
			if t.Domain == domain {
				for _, lr := range t.Results {
					if lr.WouldBlock {
						layerCounts[lr.Layer]++
						break
					}
				}
				break
			}
		}
	}
	max := 0
	for l, c := range layerCounts {
		if c > max {
			max = c
			s.TopFixLayer = l
		}
	}
	return s
}

// Clear resets the recorder (e.g., user tapped "clear logs").
func (r *Recorder) Clear() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.traces = r.traces[:0]
	r.missIndex = make(map[string]int)
	r.nextID = 0
}
