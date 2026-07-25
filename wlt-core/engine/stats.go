package engine

import (
        "sync"
)

// statsCounters tracks per-layer and per-SDK request counts under a
// RWMutex. We deliberately do NOT use atomic.AddInt64 on map indices
// (you cannot take the address of a map index element, which atomics
// require). Instead, all writes grab a write lock briefly; reads use a
// read lock for snapshot consistency.
type statsCounters struct {
        mu            sync.RWMutex
        totalQueries  uint64
        totalBlocked  uint64
        totalAllowed  uint64
        layer         map[int]uint64 // layer code -> count
        decision      map[int]uint64 // decision code -> count
        sdk           map[string]uint64 // sdk name -> block count
        topBlocked    map[string]uint64 // domain -> block count (bounded by caller)
}

func newStatsCounters() *statsCounters {
        return &statsCounters{
                layer:      make(map[int]uint64),
                decision:   make(map[int]uint64),
                sdk:        make(map[string]uint64),
                topBlocked: make(map[string]uint64),
        }
}

// incLayer atomically increments the counter for one layer code.
func (s *statsCounters) incLayer(layer int) {
        s.mu.Lock()
        s.layer[layer]++
        s.mu.Unlock()
}

// incDecision increments the counter for one decision code.
func (s *statsCounters) incDecision(d int) {
        s.mu.Lock()
        s.decision[d]++
        s.mu.Unlock()
}

// incSDK increments the counter for one SDK name (used when a request was
// blocked because of a game SDK match).
func (s *statsCounters) incSDK(name string) {
        if name == "" {
                return
        }
        s.mu.Lock()
        s.sdk[name]++
        s.mu.Unlock()
}

// incTopBlocked increments the per-domain block counter. The caller is
// responsible for bounding the map size if needed.
func (s *statsCounters) incTopBlocked(domain string) {
        if domain == "" {
                return
        }
        s.mu.Lock()
        s.topBlocked[domain]++
        s.mu.Unlock()
}

// incTotals atomically bumps totalQueries and either totalBlocked or
// totalAllowed depending on the decision.
func (s *statsCounters) incTotals(decision int) {
        s.mu.Lock()
        s.totalQueries++
        switch decision {
        case int(DecisionBlock), int(DecisionNullIP), int(DecisionNXDOMAIN):
                s.totalBlocked++
        default:
                s.totalAllowed++
        }
        s.mu.Unlock()
}

// snapshot returns a consistent copy of all counters. The returned maps
// are safe for the caller to iterate without holding any lock.
func (s *statsCounters) snapshot() statsSnapshot {
        s.mu.RLock()
        defer s.mu.RUnlock()
        out := statsSnapshot{
                TotalQueries: s.totalQueries,
                TotalBlocked: s.totalBlocked,
                TotalAllowed: s.totalAllowed,
                Layer:        make(map[int]uint64, len(s.layer)),
                Decision:     make(map[int]uint64, len(s.decision)),
                SDK:          make(map[string]uint64, len(s.sdk)),
                TopBlocked:   make(map[string]uint64, len(s.topBlocked)),
        }
        for k, v := range s.layer {
                out.Layer[k] = v
        }
        for k, v := range s.decision {
                out.Decision[k] = v
        }
        for k, v := range s.sdk {
                out.SDK[k] = v
        }
        for k, v := range s.topBlocked {
                out.TopBlocked[k] = v
        }
        return out
}

// statsSnapshot is a point-in-time copy of statsCounters used for JSON
// serialization and UI display.
type statsSnapshot struct {
        TotalQueries uint64
        TotalBlocked uint64
        TotalAllowed uint64
        Layer        map[int]uint64
        Decision     map[int]uint64
        SDK          map[string]uint64
        TopBlocked   map[string]uint64
}
