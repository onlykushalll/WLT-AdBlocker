// Package bloom implements a counting Bloom filter for fast domain membership
// pre-checking. Used as a negative filter before the trie walk: if the bloom
// filter says "definitely not present", we skip the trie lookup entirely.
//
// Inspired by HostShield's bloom + trie + hashset triple-lookup pattern.
//
// We use a counting bloom filter (4-bit counters) so that domains can be
// removed dynamically (user allowlist / denylist changes) without rebuilding.
package bloom

import (
	"encoding/binary"
	"hash/maphash"
	"math"
	"sync"
)

const counterBits = 4
const counterMax = (1 << counterBits) - 1
const countersPerByte = 8 / counterBits

// Filter is a counting bloom filter. Safe for concurrent use.
type Filter struct {
	mu       sync.RWMutex
	counters []byte // packed 4-bit counters
	bits     uint64 // number of 4-bit slots
	hashes   int    // number of hash functions
	seed     maphash.Seed
	n        int // item count
}

// New creates a filter sized for `n` expected items at target false-positive rate `p`.
// Uses optimal k = ceil(-(ln p) / ln(2)) hashes and m = ceil(-n*ln p / (ln2)^2) bits.
func New(n int, p float64) *Filter {
	if n <= 0 {
		n = 1000
	}
	if p <= 0 || p >= 1 {
		p = 0.001 // 0.1% default FP
	}
	m := uint64(math.Ceil(-float64(n) * math.Log(p) / (math.Ln2 * math.Ln2)))
	// round up to multiple of 16 for alignment
	if m%16 != 0 {
		m += 16 - (m % 16)
	}
	k := int(math.Ceil(-math.Log(p) / math.Ln2))
	if k < 1 {
		k = 1
	}
	bytes := m / countersPerByte
	if bytes == 0 {
		bytes = 1
	}
	return &Filter{
		counters: make([]byte, bytes),
		bits:     m,
		hashes:   k,
		seed:     maphash.MakeSeed(),
	}
}

// Add inserts an item.
func (f *Filter) Add(item string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	h1, h2 := f.hashPair(item)
	for i := 0; i < f.hashes; i++ {
		idx := (h1 + uint64(i)*h2) % f.bits
		f.incr(idx)
	}
	f.n++
}

// Contains reports whether item MIGHT be in the set. False = definitely not.
func (f *Filter) Contains(item string) bool {
	f.mu.RLock()
	defer f.mu.RUnlock()
	h1, h2 := f.hashPair(item)
	for i := 0; i < f.hashes; i++ {
		idx := (h1 + uint64(i)*h2) % f.bits
		if f.get(idx) == 0 {
			return false
		}
	}
	return true
}

// Remove decrements counters. If the item was present, returns true.
// Note: removing an item that wasn't added can corrupt the filter.
func (f *Filter) Remove(item string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	h1, h2 := f.hashPair(item)
	// Verify first to avoid underflow.
	for i := 0; i < f.hashes; i++ {
		idx := (h1 + uint64(i)*h2) % f.bits
		if f.get(idx) == 0 {
			return false
		}
	}
	for i := 0; i < f.hashes; i++ {
		idx := (h1 + uint64(i)*h2) % f.bits
		f.decr(idx)
	}
	f.n--
	return true
}

// N returns the approximate number of items.
func (f *Filter) N() int {
	f.mu.RLock()
	defer f.mu.RUnlock()
	return f.n
}

// hashPair computes two base hashes using maphash (double hashing scheme).
func (f *Filter) hashPair(item string) (uint64, uint64) {
	var mh maphash.Hash
	mh.SetSeed(f.seed)
	mh.WriteString(item)
	// Mix in length to differentiate "" vs "a" leading to same hash.
	binary.Write(&mh, binary.LittleEndian, uint64(len(item)))
	combined := mh.Sum64()
	h1 := combined
	h2 := combined>>32 | 1 // ensure h2 != 0
	return h1, h2
}

// get returns the 4-bit counter at slot idx.
func (f *Filter) get(idx uint64) byte {
	byteIdx := idx / countersPerByte
	nibble := idx % countersPerByte
	b := f.counters[byteIdx]
	if nibble == 0 {
		return b & 0x0F
	}
	return (b >> 4) & 0x0F
}

// set writes the 4-bit counter at slot idx.
func (f *Filter) set(idx uint64, val byte) {
	if val > counterMax {
		val = counterMax
	}
	byteIdx := idx / countersPerByte
	nibble := idx % countersPerByte
	if nibble == 0 {
		f.counters[byteIdx] = (f.counters[byteIdx] & 0xF0) | val
	} else {
		f.counters[byteIdx] = (f.counters[byteIdx] & 0x0F) | (val << 4)
	}
}

func (f *Filter) incr(idx uint64) {
	v := f.get(idx)
	if v < counterMax {
		f.set(idx, v+1)
	}
}

func (f *Filter) decr(idx uint64) {
	v := f.get(idx)
	if v > 0 {
		f.set(idx, v-1)
	}
}
