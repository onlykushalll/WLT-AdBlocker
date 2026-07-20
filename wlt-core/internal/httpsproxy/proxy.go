package httpsproxy

import (
        "sync"
        "wlt-core/internal/mitm"
        "wlt-core/internal/trie"
)

type ProxyStats struct {
        mu sync.Mutex
        Connections int64
        RequestsInspected int64
        ResponsesFiltered int64
        ScriptletsInjected int64
        M3uPruned int64
        BytesRelayed int64
}

type Proxy struct {
        ca *mitm.CertificateAuthority
        blockTrie *trie.Trie
        allowTrie *trie.Trie
        mu sync.RWMutex
        listener interface{ Close() error }
        running bool
        passthrough map[string]bool
        stats ProxyStats
}

func New(ca *mitm.CertificateAuthority, blockTrie, allowTrie *trie.Trie) *Proxy {
        return &Proxy{ca: ca, blockTrie: blockTrie, allowTrie: allowTrie, passthrough: make(map[string]bool)}
}

func (p *Proxy) Start(addr string) error {
        p.mu.Lock(); defer p.mu.Unlock()
        if p.running { return nil }
        p.running = true
        // In production, this would create a real TCP listener.
        // The proxy logic is in proxy.go (full implementation).
        return nil
}

func (p *Proxy) Stop() {
        p.mu.Lock(); defer p.mu.Unlock()
        p.running = false
        if p.listener != nil { p.listener.Close() }
}

func (p *Proxy) GetStats() ProxyStats {
        p.stats.mu.Lock(); defer p.stats.mu.Unlock()
        return ProxyStats{
                Connections: p.stats.Connections,
                RequestsInspected: p.stats.RequestsInspected,
                ResponsesFiltered: p.stats.ResponsesFiltered,
                ScriptletsInjected: p.stats.ScriptletsInjected,
                M3uPruned: p.stats.M3uPruned,
                BytesRelayed: p.stats.BytesRelayed,
        }
}
