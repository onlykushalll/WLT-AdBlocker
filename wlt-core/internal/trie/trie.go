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
	labels := splitLabels(d)
	if len(labels) == 0 { return }
	t.mu.Lock(); defer t.mu.Unlock()
	cur := t.root
	for i := len(labels) - 1; i >= 0; i-- {
		child, ok := cur.children[labels[i]]
		if !ok { child = newNode(); cur.children[labels[i]] = child }
		cur = child
	}
	cur.terminal = true
	t.size++
}

func (t *Trie) Contains(domain string) bool {
	d := normalize(domain)
	if d == "" { return false }
	labels := splitLabels(d)
	t.mu.RLock(); defer t.mu.RUnlock()
	cur := t.root
	for i := len(labels) - 1; i >= 0; i-- {
		if cur.terminal { return true }
		child, ok := cur.children[labels[i]]
		if !ok { return false }
		cur = child
	}
	return cur.terminal
}

func normalize(d string) string { return strings.ToLower(strings.Trim(strings.TrimSpace(d), ".")) }
func splitLabels(d string) []string {
	if d == "" { return nil }
	parts := strings.Split(d, ".")
	out := parts[:0]
	for _, p := range parts { if p != "" { out = append(out, p) } }
	return out
}
