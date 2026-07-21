// Package cosmetic implements DOM-level cosmetic filtering (CSS injection,
// procedural filters, DOM surveyor) inspired by uBlock Origin's
// cosmetic-filtering.js.
//
// Cosmetic filtering hides ad elements that remain on the page after
// network-level blocking. This runs in the browser context via
// Phase 3 HTTPS MITM scriptlet injection.
//
// Key techniques ported from uBlock:
//   1. CSS selector injection (display:none !important)
//   2. Generic vs specific cosmetic rules
//   3. DOM surveyor (auto-discover hideable elements)
//   4. Procedural filters (:has, :has-text, :xpath)
//   5. DOM collapser (collapse elements whose resources were blocked)
package cosmetic

import (
	"strings"
	"sync"
)

// Engine holds cosmetic filter rules and generates CSS/JS for injection.
type Engine struct {
	mu sync.RWMutex

	// Generic selectors (apply to all sites) — "Low generic" in uBlock
	lowGeneric map[string]bool // simple class/id selectors like ".ad", "#banner"

	// High generic — complex selectors like "div.ad-container"
	highGenericSimple map[string]bool
	highGenericComplex map[string]bool

	// Site-specific selectors: hostname -> []CSS selectors
	specificRules map[string][]string

	// Exceptions (unhide rules): hostname -> []selectors to NOT hide
	exceptions map[string][]string

	// Procedural filters (require JS, not just CSS)
	proceduralRules map[string][]ProceduralFilter
}

// ProceduralFilter is a JS-based cosmetic filter (uBlock procedural cosmetics).
type ProceduralFilter struct {
	Selector string // CSS selector for the target element
	Type     string // "has-text", "has", "matches-css", "xpath", "upward", "remove"
	Arg      string // argument for the filter type
	Action   string // "hide" or "remove"
}

// New creates an empty cosmetic engine.
func New() *Engine {
	return &Engine{
		lowGeneric:         make(map[string]bool),
		highGenericSimple:  make(map[string]bool),
		highGenericComplex: make(map[string]bool),
		specificRules:      make(map[string][]string),
		exceptions:         make(map[string][]string),
		proceduralRules:    make(map[string][]ProceduralFilter),
	}
}

// AddGenericSelector adds a generic CSS selector (applies to all sites).
// e.g., ".ad", "#banner", "div[class*='advert']"
func (e *Engine) AddGenericSelector(selector string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	selector = strings.TrimSpace(selector)
	if selector == "" {
		return
	}
	// Classify: simple (class/id only) vs complex (has additional qualifiers)
	if strings.HasPrefix(selector, ".") || strings.HasPrefix(selector, "#") {
		if strings.ContainsAny(selector, " [>:~+") {
			e.highGenericComplex[selector] = true
		} else {
			e.lowGeneric[selector] = true
		}
	} else {
		e.highGenericComplex[selector] = true
	}
}

// AddSpecificSelector adds a site-specific CSS selector.
// e.g., hostname="youtube.com", selector="ytd-ad-slot-renderer"
func (e *Engine) AddSpecificSelector(hostname, selector string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	hostname = strings.ToLower(strings.TrimSpace(hostname))
	selector = strings.TrimSpace(selector)
	if hostname != "" && selector != "" {
		e.specificRules[hostname] = append(e.specificRules[hostname], selector)
	}
}

// AddException adds an unhide rule (cosmetic exception).
func (e *Engine) AddException(hostname, selector string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	hostname = strings.ToLower(strings.TrimSpace(hostname))
	e.exceptions[hostname] = append(e.exceptions[hostname], selector)
}

// AddProceduralFilter adds a JS-based procedural cosmetic filter.
func (e *Engine) AddProceduralFilter(hostname string, filter ProceduralFilter) {
	e.mu.Lock()
	defer e.mu.Unlock()
	hostname = strings.ToLower(strings.TrimSpace(hostname))
	e.proceduralRules[hostname] = append(e.proceduralRules[hostname], filter)
}

// GenerateCSS generates the CSS to inject for a given hostname.
// Returns CSS text (display:none rules for matching selectors).
func (e *Engine) GenerateCSS(hostname string) string {
	e.mu.RLock()
	defer e.mu.RUnlock()

	var css []string

	// Low generic (always applied)
	for sel := range e.lowGeneric {
		css = append(css, sel+"{display:none!important;}")
	}

	// High generic simple
	for sel := range e.highGenericSimple {
		css = append(css, sel+"{display:none!important;}")
	}

	// High generic complex
	for sel := range e.highGenericComplex {
		css = append(css, sel+"{display:none!important;}")
	}

	// Site-specific
	host := strings.ToLower(strings.TrimSpace(hostname))
	if host != "" {
		labels := strings.Split(host, ".")
		for i := 0; i < len(labels)-1; i++ {
			suffix := strings.Join(labels[i:], ".")
			if selectors, ok := e.specificRules[suffix]; ok {
				for _, sel := range selectors {
					css = append(css, sel+"{display:none!important;}")
				}
			}
		}
	}

	if len(css) == 0 {
		return ""
	}
	return strings.Join(css, "\n")
}

// GenerateProceduralJS generates JS for procedural cosmetic filters.
// These require JavaScript because CSS alone can't do :has-text, :xpath, etc.
func (e *Engine) GenerateProceduralJS(hostname string) string {
	e.mu.RLock()
	defer e.mu.RUnlock()

	host := strings.ToLower(strings.TrimSpace(hostname))
	var filters []ProceduralFilter
	labels := strings.Split(host, ".")
	for i := 0; i < len(labels)-1; i++ {
		suffix := strings.Join(labels[i:], ".")
		if fs, ok := e.proceduralRules[suffix]; ok {
			filters = append(filters, fs...)
		}
	}

	if len(filters) == 0 {
		return ""
	}

	var sb strings.Builder
	sb.WriteString("(function(){\n")
	for _, f := range filters {
		switch f.Type {
		case "has-text":
			sb.WriteString("document.querySelectorAll('" + f.Selector + "').forEach(function(el){")
			sb.WriteString("if(el.textContent.includes('" + f.Arg + "')){")
			if f.Action == "remove" {
				sb.WriteString("el.remove();")
			} else {
				sb.WriteString("el.style.display='none';")
			}
			sb.WriteString("}});\n")
		case "has":
			sb.WriteString("document.querySelectorAll('" + f.Selector + ":has(" + f.Arg + ")').forEach(function(el){")
			if f.Action == "remove" {
				sb.WriteString("el.remove();")
			} else {
				sb.WriteString("el.style.display='none';")
			}
			sb.WriteString("});\n")
		case "remove":
			sb.WriteString("document.querySelectorAll('" + f.Selector + "').forEach(function(el){el.remove();});\n")
		case "matches-css":
			sb.WriteString("document.querySelectorAll('" + f.Selector + "').forEach(function(el){")
			sb.WriteString("var s=getComputedStyle(el);")
			sb.WriteString("if(s.getPropertyValue('" + strings.SplitN(f.Arg, ":", 2)[0] + "')")
			sb.WriteString("==='" + strings.SplitN(f.Arg, ":", 2)[1] + "'){el.style.display='none';}")
			sb.WriteString("});\n")
		}
	}
	sb.WriteString("})();\n")
	return sb.String()
}

// GenerateInjectionHTML generates a complete <style> + <script> block for injection.
func (e *Engine) GenerateInjectionHTML(hostname string) string {
	css := e.GenerateCSS(hostname)
	js := e.GenerateProceduralJS(hostname)
	var sb strings.Builder
	if css != "" {
		sb.WriteString("<style>\n" + css + "\n</style>\n")
	}
	if js != "" {
		sb.WriteString("<script>\n" + js + "\n</script>\n")
	}
	return sb.String()
}

// LoadDefaults loads common cosmetic selectors for major ad networks.
func (e *Engine) LoadDefaults() {
	// Generic ad selectors (from EasyList + uBlock filters)
	generics := []string{
		".ad", ".ads", ".advert", ".advertisement", ".ad-banner", ".ad-container",
		".ad-wrapper", ".ad-slot", ".ad-zone", ".ad-unit", ".ad-card",
		"#ad", "#ads", "#advert", "#advertisement", "#banner-ad", "#google-ad",
		"div[class*='advert']", "div[class*='ad-']", "div[id*='ad-']",
		"div[class*='sponsor']", "div[class*='promo']",
		"iframe[src*='doubleclick']", "iframe[src*='googlesyndication']",
		"ins.adsbygoogle", "div[data-ad]", "div[data-ad-slot]",
		"amp-ad", "ad-block", "ad-banner",
	}
	for _, sel := range generics {
		e.AddGenericSelector(sel)
	}

	// YouTube-specific
	ytSelectors := []string{
		"ytd-ad-slot-renderer", "ytd-promoted-video-renderer",
		"ytd-display-ad-renderer", "ytd-in-feed-ad-layout-renderer",
		".ytp-ad-player-overlay", ".ytp-ad-survey",
		"ytd-banner-promo-renderer", "ytd-statement-banner-renderer",
		"tp-yt-paper-dialog.ytd-display-ad-renderer",
	}
	for _, sel := range ytSelectors {
		e.AddSpecificSelector("youtube.com", sel)
	}

	// Spotify-specific
	spSelectors := []string{
		".ad-leaderboard-container", ".ad-slot-container",
		"[data-testid='ad-slot']", "[data-testid='ad-banner']",
		".ad-banner-container", ".ad-area",
	}
	for _, sel := range spSelectors {
		e.AddSpecificSelector("spotify.com", sel)
	}
}

// SelectorCount returns total number of loaded selectors.
func (e *Engine) SelectorCount() int {
	e.mu.RLock()
	defer e.mu.RUnlock()
	count := len(e.lowGeneric) + len(e.highGenericSimple) + len(e.highGenericComplex)
	for _, sels := range e.specificRules {
		count += len(sels)
	}
	return count
}
