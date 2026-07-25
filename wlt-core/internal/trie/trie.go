// Package trie implements a reversed-label domain trie with wildcard support
// for efficient O(m) suffix-domain matching. This is the NetGuard/HostShield
// pattern: a rule "example.com" matches "sub.example.com" but NOT vice-versa.
//
// The trie is keyed by domain labels in reverse order (TLD first) so that
// suffix matching collapses to a single walk from the root.
//
// Wildcard rules of the form "*.example.com" match only strict subdomains of
// example.com (NOT example.com itself).
package trie

import (
        "strings"
        "sync"
)

// node is a single trie node. Each node holds up to two independent flags:
//
//   - terminal: a non-wildcard rule was inserted ending at this node. Any
//     domain that walks through (or stops at) a terminal node matches as a
//     suffix rule.
//   - matchChildren: a wildcard "*.parent" rule was inserted ending at this
//     node. Any domain that walks *past* this node (i.e. has at least one
//     additional label below) matches.
type node struct {
        children      map[string]*node
        terminal      bool
        matchChildren bool
}

func newNode() *node {
        return &node{children: make(map[string]*node)}
}

// Trie is a reversed-label domain trie supporting exact, suffix and wildcard
// matching.
//
// SECURITY (H1 fix from security audit): All public methods acquire
// mu (RWMutex) — Insert/Delete take the write lock, Contains/Size take
// the read lock. Without this, concurrent Insert + Contains panics
// ("concurrent map read and map write") on the JNI boundary.
type Trie struct {
        mu   sync.RWMutex
        root *node
        size int
}

// New returns an empty Trie.
func New() *Trie {
        return &Trie{root: newNode()}
}

// Normalize lowercases a domain, strips any trailing dot, and removes a
// leading wildcard prefix ("*.") if present. Empty input returns the empty
// string.
func Normalize(domain string) string {
        domain = strings.TrimSpace(domain)
        domain = strings.ToLower(domain)
        domain = strings.TrimSuffix(domain, ".")
        domain = strings.TrimPrefix(domain, "*.")
        return domain
}

// splitLabels reverses a domain into its labels (TLD first).
// "sub.example.com" -> ["com", "example", "sub"].
func splitLabels(domain string) []string {
        domain = strings.TrimSuffix(domain, ".")
        if domain == "" {
                return nil
        }
        parts := strings.Split(domain, ".")
        for i, j := 0, len(parts)-1; i < j; i, j = i+1, j-1 {
                parts[i], parts[j] = parts[j], parts[i]
        }
        return parts
}

// Insert adds a domain rule to the trie. If domain starts with "*." the
// matchChildren flag is set on the parent node so all strict subdomains
// match (the parent domain itself does NOT match).
func (t *Trie) Insert(domain string) {
        t.mu.Lock()
        defer t.mu.Unlock()
        wildcard := strings.HasPrefix(strings.TrimSpace(domain), "*.")
        domain = Normalize(domain)
        if domain == "" {
                return
        }
        labels := splitLabels(domain)
        if len(labels) == 0 {
                return
        }
        cur := t.root
        for _, label := range labels {
                child, ok := cur.children[label]
                if !ok {
                        child = newNode()
                        cur.children[label] = child
                }
                cur = child
        }
        if wildcard {
                if !cur.matchChildren {
                        cur.matchChildren = true
                        t.size++
                }
        } else {
                if !cur.terminal {
                        cur.terminal = true
                        t.size++
                }
        }
}

// Contains returns true if domain matches any inserted rule. A non-wildcard
// rule "example.com" matches the exact domain AND any subdomain
// ("sub.example.com", "a.b.example.com"). A wildcard rule "*.example.com"
// matches only strict subdomains of example.com (NOT example.com itself).
func (t *Trie) Contains(domain string) bool {
        t.mu.RLock()
        defer t.mu.RUnlock()
        domain = Normalize(domain)
        if domain == "" {
                return false
        }
        labels := splitLabels(domain)
        cur := t.root
        for i, label := range labels {
                // If the current node (parent of the label we're about to descend
                // into) was set by a *.parent rule and we're past the first label,
                // the domain is a strict subdomain of that wildcard rule — match.
                if cur.matchChildren && i > 0 {
                        return true
                }
                child, ok := cur.children[label]
                if !ok {
                        return false
                }
                // If this child is terminal, the rest of the domain is a subdomain
                // of a blocked parent — suffix match.
                if child.terminal {
                        return true
                }
                cur = child
        }
        // Walked the entire domain. Match only if the final node itself is
        // terminal (exact rule). A matchChildren-only node does NOT match the
        // parent domain itself.
        return cur.terminal
}

// Delete removes a domain rule from the trie. If domain starts with "*."
// the wildcard rule is removed; otherwise the non-wildcard rule is removed.
// Returns true if the rule existed and was removed.
func (t *Trie) Delete(domain string) bool {
        t.mu.Lock()
        defer t.mu.Unlock()
        wildcard := strings.HasPrefix(strings.TrimSpace(domain), "*.")
        domain = Normalize(domain)
        if domain == "" {
                return false
        }
        labels := splitLabels(domain)
        if len(labels) == 0 {
                return false
        }
        // Walk to the terminal node, recording the path so we can prune empty
        // ancestors.
        path := make([]*node, 0, len(labels)+1)
        path = append(path, t.root)
        cur := t.root
        for _, label := range labels {
                child, ok := cur.children[label]
                if !ok {
                        return false
                }
                path = append(path, child)
                cur = child
        }
        if wildcard {
                if !cur.matchChildren {
                        return false
                }
                cur.matchChildren = false
        } else {
                if !cur.terminal {
                        return false
                }
                cur.terminal = false
        }
        t.size--

        // Prune empty branches from the leaf upward. A node is removable when
        // it has no children, no terminal flag, and no matchChildren flag.
        for i := len(labels); i > 0; i-- {
                parent := path[i-1]
                child := path[i]
                label := labels[i-1]
                if len(child.children) == 0 && !child.terminal && !child.matchChildren {
                        delete(parent.children, label)
                } else {
                        break
                }
        }
        return true
}

// Size returns the number of unique rules currently stored in the trie.
func (t *Trie) Size() int {
        t.mu.RLock()
        defer t.mu.RUnlock()
        return t.size
}
