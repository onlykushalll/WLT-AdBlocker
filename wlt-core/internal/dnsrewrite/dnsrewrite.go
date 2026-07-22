// Package dnsrewrite implements DNS response rewriting — the ability to
// return custom DNS responses instead of just NXDOMAIN/0.0.0.0.
//
// Inspired by AdGuard Home's $dnsrewrite modifier:
//   ||example.com^$dnsrewrite=NOERROR;A;1.2.3.4
//   ||example.com^$dnsrewrite=NOERROR;AAAA;::1
//   ||example.com^$dnsrewrite=NOERROR;CNAME;safe.example.com
//   ||example.com^$dnsrewrite=NXDOMAIN;;
//
// This is more powerful than simple blocking because:
//   1. We can REDIRECT ad domains to a local ad page instead of breaking them
//   2. We can rewrite CNAME chains to prevent cloaking
//   3. We can return custom IPs for specific domains (split-horizon DNS)
//   4. We can return REFUSED instead of NXDOMAIN for different app behavior
package dnsrewrite

import (
	"net"
	"strings"
	"sync"
)

// RewriteType defines what kind of rewrite to perform.
type RewriteType int

const (
	RewriteNXDomain  RewriteType = iota // Return NXDOMAIN
	RewriteNullIP                        // Return 0.0.0.0 or ::
	RewriteRefused                       // Return REFUSED
	RewriteCustomIP                      // Return specific IP
	RewriteCNAME                         // Return CNAME (redirect)
	RewritePassthrough                   // Don't rewrite (allow)
)

// Rule defines a DNS rewrite rule.
type Rule struct {
	Domain    string      // domain to match (suffix match)
	Type      RewriteType // what to do
	IPv4      net.IP      // for RewriteCustomIP (A record)
	IPv6      net.IP      // for RewriteCustomIP (AAAA record)
	CNAMETarget string    // for RewriteCNAME
	Reason    string      // human-readable reason for forensics
}

// Engine holds all rewrite rules.
type Engine struct {
	mu    sync.RWMutex
	rules map[string]*Rule // domain -> rule
}

// New creates an empty rewrite engine.
func New() *Engine {
	return &Engine{rules: make(map[string]*Rule)}
}

// AddRule adds a rewrite rule.
func (e *Engine) AddRule(rule *Rule) {
	e.mu.Lock()
	defer e.mu.Unlock()
	d := strings.ToLower(strings.TrimSpace(rule.Domain))
	rule.Domain = d
	e.rules[d] = rule
}

// AddBlock adds a simple NXDOMAIN block rule.
func (e *Engine) AddBlock(domain string) {
	e.AddRule(&Rule{
		Domain: domain,
		Type:   RewriteNXDomain,
		Reason: "blocklist",
	})
}

// AddNullIP adds a 0.0.0.0 sinkhole rule.
func (e *Engine) AddNullIP(domain string) {
	e.AddRule(&Rule{
		Domain: domain,
		Type:   RewriteNullIP,
		Reason: "sinkhole",
	})
}

// AddRedirect adds a CNAME redirect rule.
func (e *Engine) AddRedirect(domain, target string) {
	e.AddRule(&Rule{
		Domain:      domain,
		Type:        RewriteCNAME,
		CNAMETarget: target,
		Reason:      "redirect",
	})
}

// AddCustomIP adds a custom IP response rule (split-horizon DNS).
func (e *Engine) AddCustomIP(domain string, ipv4, ipv6 net.IP) {
	e.AddRule(&Rule{
		Domain: domain,
		Type:   RewriteCustomIP,
		IPv4:   ipv4,
		IPv6:   ipv6,
		Reason: "custom-ip",
	})
}

// Lookup checks if a domain has a rewrite rule.
// Returns the rule and true if found, nil and false otherwise.
// Performs suffix matching: a rule for "example.com" matches "sub.example.com".
func (e *Engine) Lookup(domain string) (*Rule, bool) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	d := strings.ToLower(strings.TrimSpace(domain))
	d = strings.Trim(d, ".")

	// Check exact match first
	if rule, ok := e.rules[d]; ok {
		return rule, true
	}

	// Check suffix matches
	labels := strings.Split(d, ".")
	for i := 0; i < len(labels)-1; i++ {
		suffix := strings.Join(labels[i:], ".")
		if rule, ok := e.rules[suffix]; ok {
			return rule, true
		}
	}

	return nil, false
}

// RuleCount returns the number of rewrite rules.
func (e *Engine) RuleCount() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return len(e.rules)
}

// LoadDefaults loads common rewrite rules.
func (e *Engine) LoadDefaults() {
	// Redirect common ad domains to 0.0.0.0 (sinkhole)
	adDomains := []string{
		"doubleclick.net",
		"googlesyndication.com",
		"googleadservices.com",
		"adservice.google.com",
		"admob.google.com",
		"googletagservices.com",
		"applovin.com",
		"ironsrc.com",
		"chartboost.com",
		"vungle.com",
		"adcolony.com",
		"mintegral.com",
		"fyber.com",
		"tapjoy.com",
		"inmobi.com",
	}
	for _, d := range adDomains {
		e.AddNullIP(d)
	}

	// Redirect DoH bypass endpoints to NXDOMAIN
	dohDomains := []string{
		"dns.google",
		"cloudflare-dns.com",
		"dns.quad9.net",
		"doh.opendns.com",
		"dns.adguard.com",
	}
	for _, d := range dohDomains {
		e.AddRule(&Rule{
			Domain: d,
			Type:   RewriteRefused,
			Reason: "DoH bypass prevention",
		})
	}
}

// Clear removes all rules.
func (e *Engine) Clear() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.rules = make(map[string]*Rule)
}
