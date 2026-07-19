package trie

import (
	"strings"
	"sync"
)

type Trie struct {
	mu   sync.RWMutex
	root *node
	size int
}

type node struct {
	children map[string]*node
	terminal bool
	matchChildren bool
}

func newNode() *node { return &node{children: make(map[string]*node)} }
func New() *Trie     { return &Trie{root: newNode()} }
func (t *Trie) Size() int { t.mu.RLock(); defer t.mu.RUnlock(); return t.size }

func (t *Trie) Insert(domain string) {
	d := normalize(domain)
	if d == "" { return }
	wildcard := false
	if strings.HasPrefix(d, "*.") { wildcard = true; d = d[2:] }
	labels := splitLabels(d)
	if len(labels) == 0 { return }
	t.mu.Lock()
	defer t.mu.Unlock()
	cur := t.root
	for i := len(labels) - 1; i >= 0; i-- {
		label := labels[i]
		child, ok := cur.children[label]
		if !ok { child = newNode(); cur.children[label] = child }
		cur = child
	}
	if wildcard { cur.matchChildren = true } else { cur.terminal = true }
	t.size++
}

type MatchKind int
const ( MatchNone MatchKind = iota; MatchExact; MatchWildcard )

func (m MatchKind) String() string {
	switch m { case MatchExact: return "exact"; case MatchWildcard: return "wildcard" }
	return "none"
}

func (t *Trie) Contains(domain string) (bool, MatchKind) {
	d := normalize(domain)
	if d == "" { return false, MatchNone }
	labels := splitLabels(d)
	t.mu.RLock()
	defer t.mu.RUnlock()
	cur := t.root
	for i := len(labels) - 1; i >= 0; i-- {
		if cur.terminal { return true, MatchExact }
		if cur.matchChildren { return true, MatchWildcard }
		label := labels[i]
		child, ok := cur.children[label]
		if !ok { return false, MatchNone }
		cur = child
	}
	if cur.terminal { return true, MatchExact }
	if cur.matchChildren { return true, MatchWildcard }
	return false, MatchNone
}

func normalize(d string) string { return strings.ToLower(strings.Trim(strings.TrimSpace(d), ".")) }
func splitLabels(d string) []string {
	if d == "" { return nil }
	parts := strings.Split(d, ".")
	out := parts[:0]
	for _, p := range parts { if p != "" { out = append(out, p) } }
	return out
}
