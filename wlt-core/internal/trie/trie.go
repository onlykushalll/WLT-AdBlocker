// Package trie implements a reversed-label domain trie with wildcard support.
//
// Inspired by NetGuard's DomainTrie and HostShield's trie implementation.
// Domains are stored reversed (com.example.ads) so that suffix matching
// (the common case for adblocking — block *.example.com) is a prefix walk.
//
// Supports:
//   - Exact domain match: "ads.example.com"
//   - Wildcard suffix: "*.example.com" matches "ads.example.com" and "x.ads.example.com"
//   - Label-boundary matching: "example.com" matches "sub.example.com" but NOT "notexample.com"
package trie

import (
        "strings"
        "sync"
)

// Trie is a reversed-label domain trie. Safe for concurrent use.
type Trie struct {
        mu   sync.RWMutex
        root *node
        size int
}

type node struct {
        children map[string]*node
        // terminal indicates this node represents a blocked domain (or wildcard endpoint)
        terminal bool
        // matchChildren means a wildcard rule ends here; any descendant is blocked.
        matchChildren bool
}

func newNode() *node {
        return &node{children: make(map[string]*node)}
}

// New returns an empty trie.
func New() *Trie {
        return &Trie{root: newNode()}
}

// Size returns the number of inserted rules.
func (t *Trie) Size() int {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.size
}

// Insert adds a domain rule to the trie.
// Domain is normalized: lowercased, trimmed of leading/trailing dots.
// If domain starts with "*." it's treated as a wildcard suffix rule.
func (t *Trie) Insert(domain string) {
        d := normalize(domain)
        if d == "" {
                return
        }
        wildcard := false
        if strings.HasPrefix(d, "*.") {
                wildcard = true
                d = d[2:]
        }
        labels := splitLabels(d)
        if len(labels) == 0 {
                return
        }

        t.mu.Lock()
        defer t.mu.Unlock()
        cur := t.root
        for i := len(labels) - 1; i >= 0; i-- {
                label := labels[i]
                child, ok := cur.children[label]
                if !ok {
                        child = newNode()
                        cur.children[label] = child
                }
                cur = child
        }
        if wildcard {
                cur.matchChildren = true
        } else {
                cur.terminal = true
        }
        t.size++
}

// Contains reports whether domain (or any of its parent domains) is in the trie.
// Domain is normalized internally. Returns the matched rule kind for forensics.
//
// Examples (trie contains "example.com" and "*.ads.com"):
//   Contains("sub.example.com")      -> true, RuleExact
//   Contains("example.com")          -> true, RuleExact
//   Contains("notexample.com")       -> false
//   Contains("banner.ads.com")       -> true, RuleWildcard
//   Contains("x.y.banner.ads.com")   -> true, RuleWildcard
func (t *Trie) Contains(domain string) (bool, MatchKind) {
        d := normalize(domain)
        if d == "" {
                return false, MatchNone
        }
        labels := splitLabels(d)

        t.mu.RLock()
        defer t.mu.RUnlock()
        cur := t.root
        for i := len(labels) - 1; i >= 0; i-- {
                // A terminal rule at an ancestor node matches this domain (suffix match).
                // e.g., rule "example.com" matches "sub.example.com".
                if cur.terminal {
                        return true, MatchExact
                }
                // A wildcard rule (*.parent) at an ancestor matches all descendants.
                if cur.matchChildren {
                        return true, MatchWildcard
                }
                label := labels[i]
                child, ok := cur.children[label]
                if !ok {
                        return false, MatchNone
                }
                cur = child
        }
        // At the final node — check if it's terminal or a wildcard endpoint.
        if cur.terminal {
                return true, MatchExact
        }
        if cur.matchChildren {
                return true, MatchWildcard
        }
        return false, MatchNone
}

// MatchKind describes which rule pattern matched, used by the forensics engine.
type MatchKind int

const (
        MatchNone MatchKind = iota
        MatchExact
        MatchWildcard
)

func (m MatchKind) String() string {
        switch m {
        case MatchExact:
                return "exact"
        case MatchWildcard:
                return "wildcard"
        default:
                return "none"
        }
}

// normalize lowercases and strips surrounding dots and whitespace.
func normalize(domain string) string {
        d := strings.ToLower(strings.TrimSpace(domain))
        d = strings.Trim(d, ".")
        return d
}

// splitLabels splits "a.b.c" into ["a","b","c"]. Empty labels are skipped.
func splitLabels(d string) []string {
        if d == "" {
                return nil
        }
        parts := strings.Split(d, ".")
        out := parts[:0]
        for _, p := range parts {
                if p != "" {
                        out = append(out, p)
                }
        }
        return out
}

// Delete removes a domain from the trie. Returns true if it was present.
func (t *Trie) Delete(domain string) bool {
        d := normalize(domain)
        if d == "" {
                return false
        }
        labels := splitLabels(d)
        if len(labels) == 0 {
                return false
        }
        t.mu.Lock()
        defer t.mu.Unlock()
        // Walk to the node, collecting path for pruning.
        path := make([]*node, 0, len(labels)+1)
        pathLabels := make([]string, 0, len(labels))
        cur := t.root
        path = append(path, cur)
        for i := len(labels) - 1; i >= 0; i-- {
                label := labels[i]
                child, ok := cur.children[label]
                if !ok {
                        return false
                }
                cur = child
                path = append(path, cur)
                pathLabels = append(pathLabels, label)
        }
        if !cur.terminal && !cur.matchChildren {
                return false
        }
        cur.terminal = false
        cur.matchChildren = false
        // Prune empty branches back up.
        for i := len(path) - 1; i >= 1; i-- {
                node := path[i]
                if len(node.children) == 0 && !node.terminal && !node.matchChildren {
                        delete(path[i-1].children, pathLabels[i-1])
                } else {
                        break
                }
        }
        t.size--
        return true
}
