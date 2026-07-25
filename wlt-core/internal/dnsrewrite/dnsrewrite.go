// Package dnsrewrite implements an AdGuard-style DNS rewrite engine.
//
// Each Rule maps a domain to one of five rewrite outcomes:
//
//   - NXDOMAIN   — the resolver returns RCODE 3 (name error).
//   - NullIP     — the resolver returns an A record of 0.0.0.0.
//   - REFUSED    — the resolver returns RCODE 5 (refused).
//   - CustomIP   — the resolver returns an A record with the given IP.
//   - CNAME      — the resolver returns a CNAME to the given target.
//
// Matching is suffix-based: a rule for "example.com" matches
// "sub.example.com" and "example.com" itself. This mirrors AdGuard's
// `dnsrewrite` rule modifier.
package dnsrewrite

import (
        "net/netip"
        "strings"
        "sync"
)

// RewriteType is the kind of DNS rewrite a Rule represents.
type RewriteType int

const (
        // NXDOMAIN returns RCODE 3 (name error) — the domain doesn't exist.
        NXDOMAIN RewriteType = iota
        // NullIP returns an A record with address 0.0.0.0 — the client
        // silently fails to connect.
        NullIP
        // REFUSED returns RCODE 5 (refused) — the resolver is unwilling to
        // answer.
        REFUSED
        // CustomIP returns an A record with the given CustomIP address.
        CustomIP
        // CNAME returns a CNAME record pointing to CNAMETarget.
        CNAME
)

// Rule is a single DNS rewrite rule.
type Rule struct {
        Domain      string
        Type        RewriteType
        CustomIP    netip.Addr
        CNAMETarget string
}

// Engine is a suffix-matching DNS rewrite engine.
type Engine struct {
        mu    sync.RWMutex
        rules map[string]Rule // exact-suffix key -> rule (no wildcards; suffix match done at lookup)
}

// New returns an empty Engine.
func New() *Engine {
        return &Engine{rules: make(map[string]Rule)}
}

// AddRule adds a rewrite rule. Domain is lowercased and stripped of any
// trailing dot.
func (e *Engine) AddRule(rule Rule) {
        rule.Domain = normalize(rule.Domain)
        if rule.Domain == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        e.rules[rule.Domain] = rule
}

// RemoveRule removes the rule for the given domain (if any).
func (e *Engine) RemoveRule(domain string) {
        domain = normalize(domain)
        e.mu.Lock()
        defer e.mu.Unlock()
        delete(e.rules, domain)
}

// Rewrite returns the rewrite rule matching domain (suffix match), or nil
// if no rule matches. Longest-suffix-wins: a rule for "ads.example.com"
// takes precedence over a rule for "example.com" when both match.
func (e *Engine) Rewrite(domain string) *Rule {
        domain = normalize(domain)
        if domain == "" {
                return nil
        }
        e.mu.RLock()
        defer e.mu.RUnlock()

        // Walk every suffix of the domain from longest to shortest. The first
        // match wins (longest-suffix-wins).
        labels := strings.Split(domain, ".")
        for i := 0; i < len(labels); i++ {
                suffix := strings.Join(labels[i:], ".")
                if r, ok := e.rules[suffix]; ok {
                        out := r
                        return &out
                }
        }
        return nil
}

// Clear removes all rules.
func (e *Engine) Clear() {
        e.mu.Lock()
        defer e.mu.Unlock()
        e.rules = make(map[string]Rule)
}

// Count returns the number of registered rules.
func (e *Engine) Count() int {
        e.mu.RLock()
        defer e.mu.RUnlock()
        return len(e.rules)
}

// LoadDefaults populates the engine with the WLT default rewrite rules:
//   - 21 ad/tracker domains sinkholed to 0.0.0.0 (NullIP).
//   - 5 DoH bypass domains refused (so apps can't escape the VPN DNS via
//     DoH).
//   - Safe Search DNS rewrites (AdGuard Home technique): force Google,
//     Bing, DuckDuckGo, YouTube, and Pixabay to their safe-search endpoints.
func (e *Engine) LoadDefaults() {
        adDomains := []string{
                "doubleclick.net",
                "googlesyndication.com",
                "googleadservices.com",
                "googletagservices.com",
                "google-analytics.com",
                "adservice.google.com",
                "adsystem.com",
                "adsrvr.org",
                "2mdn.net",
                "amazon-adsystem.com",
                "ads.yahoo.com",
                "ads.tiktok.com",
                "analytics.tiktok.com",
                "events.tiktok.com",
                "ads-sg.tiktok.com",
                "adsnap.com",
                "adcolony.com",
                "applovin.com",
                "chartboost.com",
                "vungle.com",
                "unityads.unity3d.com",
        }
        for _, d := range adDomains {
                e.AddRule(Rule{Domain: d, Type: NullIP})
        }
        dohDomains := []string{
                "dns.google",
                "cloudflare-dns.com",
                "dns.quad9.net",
                "dns.adguard.com",
                "dns.mullvad.net",
        }
        for _, d := range dohDomains {
                e.AddRule(Rule{Domain: d, Type: REFUSED})
        }
        e.LoadSafeSearch()
}

// LoadSafeSearch adds DNS rewrite rules that force search engines and
// YouTube to their "Safe Search" endpoints. This is the AdGuard Home
// technique — by CNAME-redirecting the regular search domain to the
// safe-search variant, all queries automatically get filtered results
// regardless of the user's browser settings.
//
// These rules are CNAME rewrites (the DNS response tells the client to
// look up the safe-search domain instead). The client then resolves the
// safe-search domain normally.
func (e *Engine) LoadSafeSearch() {
        // Google Safe Search: www.google.com → forcesafesearch.google.com
        e.AddRule(Rule{Domain: "www.google.com", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        // Google Safe Search for country variants
        e.AddRule(Rule{Domain: "www.google.ad", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.ae", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.at", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.be", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.ca", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.ch", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.cl", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.co.in", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.co.jp", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.co.uk", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.de", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.es", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.fr", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.it", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.mx", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.nl", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.pl", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.ru", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})
        e.AddRule(Rule{Domain: "www.google.se", Type: CNAME, CNAMETarget: "forcesafesearch.google.com"})

        // Bing Safe Search
        e.AddRule(Rule{Domain: "www.bing.com", Type: CNAME, CNAMETarget: "strict.bing.com"})

        // DuckDuckGo Safe Search
        e.AddRule(Rule{Domain: "duckduckgo.com", Type: CNAME, CNAMETarget: "safe.duckduckgo.com"})

        // YouTube Restricted Mode (Education version)
        e.AddRule(Rule{Domain: "www.youtube.com", Type: CNAME, CNAMETarget: "restrict.youtube.com"})
        e.AddRule(Rule{Domain: "m.youtube.com", Type: CNAME, CNAMETarget: "restrict.youtube.com"})
        e.AddRule(Rule{Domain: "youtubei.googleapis.com", Type: CNAME, CNAMETarget: "restrict.youtube.com"})

        // Pixabay Safe Search
        e.AddRule(Rule{Domain: "pixabay.com", Type: CNAME, CNAMETarget: "safe.pixabay.com"})
}

func normalize(d string) string {
        d = strings.ToLower(strings.TrimSpace(d))
        d = strings.TrimSuffix(d, ".")
        return d
}
