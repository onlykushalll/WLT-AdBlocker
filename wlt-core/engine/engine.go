package engine

import (
	"strings"
	"sync"
	"sync/atomic"
	"wlt-core/internal/gamesdk"
	"wlt-core/internal/trie"
)

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
		gamesdk: gamesdk.New(), stats: &statsCounters{},
	}
}

func (e *Engine) AddBlockDomain(domain string) { e.trie.Insert(normalize(domain)) }
func (e *Engine) AddAllowDomain(domain string) { e.allowlist.Insert(normalize(domain)) }
func (e *Engine) AddDenyDomain(domain string) { e.denylist.Insert(normalize(domain)) }

func (e *Engine) ShouldBlock(domain string) bool {
	d := normalize(domain)
	if d == "" { return false }
	if e.denylist.Contains(d) { atomic.AddInt64(&e.stats.TotalBlocked, 1); return true }
	if e.allowlist.Contains(d) { atomic.AddInt64(&e.stats.TotalAllowed, 1); return false }
	if e.trie.Contains(d) { atomic.AddInt64(&e.stats.TotalBlocked, 1); return true }
	if sdk := e.gamesdk.DetectByDomain(d); sdk != gamesdk.SDKUnknown {
		atomic.AddInt64(&e.stats.TotalBlocked, 1); return true
	}
	atomic.AddInt64(&e.stats.TotalAllowed, 1); return false
}

func (e *Engine) BlocklistSize() int { return e.trie.Size() }
func (e *Engine) AllowlistSize() int { return e.allowlist.Size() }
func (e *Engine) TotalBlocked() int64 { return atomic.LoadInt64(&e.stats.TotalBlocked) }
func (e *Engine) TotalAllowed() int64 { return atomic.LoadInt64(&e.stats.TotalAllowed) }

func normalize(d string) string { return strings.ToLower(strings.Trim(strings.TrimSpace(d), ".")) }
