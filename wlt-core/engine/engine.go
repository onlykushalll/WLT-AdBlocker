package engine

import (
	"strings"
	"sync"
	"sync/atomic"
	"wlt-core/internal/gamesdk"
	"wlt-core/internal/trie"
)

type BlockResponse int
const ( ResponseNXDomain BlockResponse = iota; ResponseNullIP; ResponseRefused )

type Decision struct {
	Block  bool
	Reason string
	Layer  int
	SDK    gamesdk.SDK
}

type Engine struct {
	trie      *trie.Trie
	allowlist *trie.Trie
	denylist  *trie.Trie
	gamesdk   *gamesdk.Engine
	mu        sync.RWMutex
	stats     *statsCounters
}

type statsCounters struct {
	TotalQueries int64
	TotalBlocked int64
	TotalAllowed int64
}

func New() *Engine {
	return &Engine{
		trie: trie.New(), allowlist: trie.New(), denylist: trie.New(),
		gamesdk: gamesdk.New(),
		stats: &statsCounters{},
	}
}

func (e *Engine) AddBlockDomain(domain string) {
	d := normalize(domain)
	if d == "" { return }
	e.trie.Insert(d)
}

func (e *Engine) AddAllowDomain(domain string) { e.allowlist.Insert(normalize(domain)) }
func (e *Engine) AddDenyDomain(domain string) { e.denylist.Insert(normalize(domain)) }

func (e *Engine) ShouldBlock(domain string) bool {
	d := normalize(domain)
	if d == "" { return false }

	// Denylist (user-forced) — overrides everything
	if ok, _ := e.denylist.Contains(d); ok { atomic.AddInt64(&e.stats.TotalBlocked, 1); return true }

	// Allowlist
	if ok, _ := e.allowlist.Contains(d); ok { atomic.AddInt64(&e.stats.TotalAllowed, 1); return false }

	// Trie
	if ok, _ := e.trie.Contains(d); ok { atomic.AddInt64(&e.stats.TotalBlocked, 1); return true }

	// Game SDK
	if sdk := e.gamesdk.DetectByDomain(d); sdk != gamesdk.SDKUnknown {
		atomic.AddInt64(&e.stats.TotalBlocked, 1)
		return true
	}

	atomic.AddInt64(&e.stats.TotalAllowed, 1)
	return false
}

func (e *Engine) BlocklistSize() int { return e.trie.Size() }
func (e *Engine) AllowlistSize() int { return e.allowlist.Size() }
func (e *Engine) TotalBlocked() int64 { return atomic.LoadInt64(&e.stats.TotalBlocked) }
func (e *Engine) TotalAllowed() int64 { return atomic.LoadInt64(&e.stats.TotalAllowed) }

func normalize(d string) string { return strings.ToLower(strings.Trim(strings.TrimSpace(d), ".")) }
