// Package scriptlets implements a scriptlet injection engine for Phase 3
// (HTTPS filtering). Scriptlets are JavaScript snippets injected into
// web pages to neutralize ads, anti-adblock, and tracking.
//
// Inspired by uBlock Origin's scriptlet library (80+ scriptlets).
// This Go implementation generates the JS that gets injected by the
// HTTPS proxy when it intercepts responses from matching domains.
package scriptlets

import (
	"strings"
	"sync"
)

// Scriptlet is a named JS snippet that gets injected into pages.
type Scriptlet struct {
	Name        string
	Description string
	Domains     []string // domains this scriptlet applies to
	JS          string   // the JavaScript to inject
}

// Engine holds all registered scriptlets and provides domain-based lookup.
type Engine struct {
	mu          sync.RWMutex
	scriptlets  []Scriptlet
	domainIndex map[string][]int // domain -> scriptlet indices
}

// New creates an Engine preloaded with WLT's scriptlet library.
func New() *Engine {
	e := &Engine{
		domainIndex: make(map[string][]int),
	}
	e.loadDefaults()
	return e
}

// GetScriptletsForDomain returns all scriptlets that should be injected
// for the given domain.
func (e *Engine) GetScriptletsForDomain(domain string) []Scriptlet {
	e.mu.RLock()
	defer e.mu.RUnlock()
	d := strings.ToLower(strings.TrimSpace(domain))
	var result []Scriptlet
	// Check exact domain and all parent suffixes
	labels := strings.Split(d, ".")
	for i := 0; i < len(labels)-1; i++ {
		suffix := strings.Join(labels[i:], ".")
		if indices, ok := e.domainIndex[suffix]; ok {
			for _, idx := range indices {
				result = append(result, e.scriptlets[idx])
			}
		}
	}
	return result
}

// GenerateInjectionScript combines all matching scriptlets into one JS block.
func (e *Engine) GenerateInjectionScript(domain string) string {
	scriptlets := e.GetScriptletsForDomain(domain)
	if len(scriptlets) == 0 {
		return ""
	}
	var sb strings.Builder
	sb.WriteString("<script>\n")
	sb.WriteString("// WLT-Adblocker scriptlet injection\n")
	sb.WriteString("(function() {\n")
	for _, s := range scriptlets {
		sb.WriteString("// " + s.Name + ": " + s.Description + "\n")
		sb.WriteString(s.JS)
		sb.WriteString("\n")
	}
	sb.WriteString("})();\n")
	sb.WriteString("</script>\n")
	return sb.String()
}

func (e *Engine) loadDefaults() {
	e.scriptlets = []Scriptlet{
		{
			Name:        "googlesyndication-adsbygoogle",
			Description: "Neutralize AdSense adsbygoogle pushes",
			Domains:     []string{"googlesyndication.com", "googleads.g.doubleclick.net"},
			JS: `self.adsbygoogle = self.adsbygoogle || {
				loaded: true,
				push: function() { /* no-op */ }
			};`,
		},
		{
			Name:        "doubleclick-instream-ad-status",
			Description: "Tell DoubleClick instream ads are already shown",
			Domains:     []string{"doubleclick.net", "ad.doubleclick.net"},
			JS:          `window.google_ad_status = 1;`,
		},
		{
			Name:        "abort-on-property-read",
			Description: "Prevent ad scripts from reading certain properties",
			Domains:     []string{},
			JS: `// Generic: can be parameterized per-site
				// Example: aborts when document.ads is accessed
				Object.defineProperty(document, 'ads', { get: function(){ throw new ReferenceError(); } });`,
		},
		{
			Name:        "prevent-fetch-ads",
			Description: "Block fetch() calls to known ad endpoints",
			Domains:     []string{},
			JS: `const originalFetch = window.fetch;
				window.fetch = function(url, options) {
					if (typeof url === 'string' && /doubleclick|googlesyndication|adservice/.test(url)) {
						return new Promise(function(){ /* never resolves — ad blocked */ });
					}
					return originalFetch.apply(this, arguments);
				};`,
		},
		{
			Name:        "no-xhr-if",
			Description: "Block XMLHttpRequest to ad endpoints",
			Domains:     []string{},
			JS: `const originalOpen = XMLHttpRequest.prototype.open;
				XMLHttpRequest.prototype.open = function(method, url) {
					if (/doubleclick|googlesyndication|adservice/.test(url)) {
						throw new Error('Blocked by WLT');
					}
					return originalOpen.apply(this, arguments);
				};`,
		},
		{
			Name:        "set-constant-adblock-detected",
			Description: "Fake adblock detection status (anti-anti-adblock)",
			Domains:     []string{},
			JS: `// Sites check for adblock — fake the detection
				Object.defineProperty(window, 'adblock', { value: false, writable: false });
				Object.defineProperty(window, 'adblockDetected', { value: false, writable: false });`,
		},
	}
	// Build domain index
	for i, s := range e.scriptlets {
		for _, d := range s.Domains {
			e.domainIndex[d] = append(e.domainIndex[d], i)
		}
	}
}

// AllScriptlets returns the full scriptlet library (for UI/forensics).
func (e *Engine) AllScriptlets() []Scriptlet {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make([]Scriptlet, len(e.scriptlets))
	copy(out, e.scriptlets)
	return out
}
