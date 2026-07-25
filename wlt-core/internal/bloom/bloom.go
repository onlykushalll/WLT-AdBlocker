// Package bloom implements a counting bloom filter with 4-bit counters and
// maphash-derived hashing. It is "suffix-aware": Add inserts every parent
// suffix of a domain so that a single Contains query for any subdomain of a
// blocked parent returns true. Remove decrements the counters (so user rules
// can be un-done at runtime).
//
// The bloom filter is used as a fast negative pre-check in front of the
// slower trie — a Contains==false answer is always accurate, while
// Contains==true is probabilistic and must be confirmed by the trie.
package bloom

import (
        "hash/maphash"
        "strings"
        "sync"
)

const (
        // counterBits is the width of each counter in bits.
        counterBits = 4
        // counterMax is the maximum value of a 4-bit counter.
        counterMax = (1 << counterBits) - 1 // 15
        // numHashes is the number of independent hash functions applied.
        numHashes = 7
)

// Filter is a counting bloom filter with 4-bit counters.
type Filter struct {
        mu       sync.RWMutex
        counters []byte // packed 4-bit counters, 2 per byte
        bits     uint64 // number of counters
        seed     maphash.Seed
        mask     uint64 // bits-1, only when bits is power of 2
        pow2     bool
}

// New returns a Filter sized for the given expected number of insertions
// and the target false-positive rate. m is rounded up to a power of two.
//
// Because Add() expands every domain into all of its parent suffixes
// (typically 3 per domain), the filter internally sizes itself for 3x the
// supplied expectedItems so the realized false-positive rate tracks the
// caller-specified target instead of being inflated by the suffix
// expansion.
func New(expectedItems int, falsePositiveRate float64) *Filter {
        if expectedItems < 1 {
                expectedItems = 1
        }
        if falsePositiveRate <= 0 {
                falsePositiveRate = 0.001
        }
        // Account for suffix expansion: each Add() writes ~len(labels) entries.
        const suffixExpansion = 3
        expanded := expectedItems * suffixExpansion
        // Optimal m: m = -n * ln(p) / (ln(2)^2)
        // Use the standard approximation and round up to power of two for a
        // cheap bitwise modulo.
        m := optimalM(expanded, falsePositiveRate)
        // Round up to power of two.
        bits := uint64(1)
        for bits < m {
                bits <<= 1
        }
        return &Filter{
                counters: make([]byte, (bits+1)/2),
                bits:     bits,
                seed:     maphash.MakeSeed(),
                mask:     bits - 1,
                pow2:     true,
        }
}

func optimalM(n int, p float64) uint64 {
        // m = -n * ln(p) / (ln 2)^2
        const ln2 = 0.6931471805599453
        lnp := 0.0
        if p > 0 {
                // math.Log without importing math (we don't want to bloat deps).
                // Use the standard library after all — it's stdlib.
                lnp = negLog(p)
        }
        m := float64(n) * lnp / (ln2 * ln2)
        if m < 1 {
                m = 1
        }
        return uint64(m)
}

// negLog returns -ln(p) for 0 < p < 1.
func negLog(p float64) float64 {
        // Newton series approximation for -ln(p) around p=1.
        // For small p this converges slowly; fall back to a piecewise table.
        if p <= 0 {
                return 1e9
        }
        if p >= 1 {
                return 0
        }
        // Use math.Log via a hidden import to keep precision.
        return mathNegLog(p)
}

// mathNegLog uses the math package for precision.
func mathNegLog(p float64) float64 {
        // inlined here to keep the public API clean
        if p <= 0 {
                return 1e9
        }
        // compute -ln(p) = ln(1/p)
        return mathLog(1 / p)
}

// mathLog delegates to the math package. We import math in math_helpers.go
// (separate file in same package) to keep the public Filter file dependency
// surface obvious.

// hash returns the i-th hash of s, in range [0, bits).
func (f *Filter) hash(s string, i int) uint64 {
        var h maphash.Hash
        h.SetSeed(f.seed)
        _, _ = h.WriteString(s)
        // Mix the hash index in.
        _, _ = h.WriteString(hashSalt[i])
        x := h.Sum64()
        if f.pow2 {
                return x & f.mask
        }
        return x % f.bits
}

var hashSalt = [numHashes]string{
        "#0", "#1", "#2", "#3", "#4", "#5", "#6",
}

// getCounter returns the counter at index idx.
func (f *Filter) getCounter(idx uint64) uint8 {
        byteIdx := idx / 2
        if idx%2 == 0 {
                return f.counters[byteIdx] & 0x0F
        }
        return (f.counters[byteIdx] >> 4) & 0x0F
}

// setCounter sets the counter at index idx to v (must be 0..15).
func (f *Filter) setCounter(idx uint64, v uint8) {
        byteIdx := idx / 2
        if idx%2 == 0 {
                f.counters[byteIdx] = (f.counters[byteIdx] & 0xF0) | (v & 0x0F)
        } else {
                f.counters[byteIdx] = (f.counters[byteIdx] & 0x0F) | ((v & 0x0F) << 4)
        }
}

// Add inserts s and every parent suffix of s into the filter. This makes
// the filter suffix-aware: a query for "sub.example.com" will return true
// whenever "example.com" (or any other suffix) has been added.
func (f *Filter) Add(s string) {
        s = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(s), "."))
        if s == "" {
                return
        }
        f.mu.Lock()
        defer f.mu.Unlock()
        for _, suffix := range suffixes(s) {
                f.addOnce(suffix)
        }
}

// addOnce inserts a single domain string (no suffix expansion).
func (f *Filter) addOnce(s string) {
        for i := 0; i < numHashes; i++ {
                idx := f.hash(s, i)
                c := f.getCounter(idx)
                if c < counterMax {
                        f.setCounter(idx, c+1)
                }
        }
}

// Remove decrements the counters for s and every parent suffix of s. If any
// counter is already 0, it remains 0 (we never underflow into 15).
func (f *Filter) Remove(s string) {
        s = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(s), "."))
        if s == "" {
                return
        }
        f.mu.Lock()
        defer f.mu.Unlock()
        for _, suffix := range suffixes(s) {
                f.removeOnce(suffix)
        }
}

func (f *Filter) removeOnce(s string) {
        for i := 0; i < numHashes; i++ {
                idx := f.hash(s, i)
                c := f.getCounter(idx)
                if c > 0 {
                        f.setCounter(idx, c-1)
                }
        }
}

// Contains returns true if s MAY be in the filter (probabilistic). Returns
// false if s is definitely not in the filter. Suffix-aware: a query for
// "sub.example.com" returns true if any parent suffix ("example.com", "com")
// has been added.
func (f *Filter) Contains(s string) bool {
        s = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(s), "."))
        if s == "" {
                return false
        }
        f.mu.RLock()
        defer f.mu.RUnlock()
        return f.containsAnySuffix(s)
}

// containsAnySuffix reports whether any suffix of s is present. Caller must
// hold the read lock.
func (f *Filter) containsAnySuffix(s string) bool {
        for _, suffix := range suffixes(s) {
                if f.containsExact(suffix) {
                        return true
                }
        }
        return false
}

// containsExact checks the bloom filter for one specific string.
func (f *Filter) containsExact(s string) bool {
        for i := 0; i < numHashes; i++ {
                if f.getCounter(f.hash(s, i)) == 0 {
                        return false
                }
        }
        return true
}

// suffixes returns every suffix of s, longest first. For "a.b.com" the
// result is ["a.b.com", "b.com", "com"].
func suffixes(s string) []string {
        parts := strings.Split(s, ".")
        out := make([]string, 0, len(parts))
        for i := 0; i < len(parts); i++ {
                out = append(out, strings.Join(parts[i:], "."))
        }
        return out
}

// Bits returns the number of counters in the filter (useful for testing).
func (f *Filter) Bits() uint64 { return f.bits }
