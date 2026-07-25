// Package cosmetic implements the WLT CSS injection engine for the HTTPS
// MITM proxy. The engine accepts generic selectors (low- and high-level),
// per-site specific selectors with suffix matching, exceptions
// (@@||example.com##.selector unhides), and procedural filters (JS-based
// has-text / has / matches-css / remove operators from uBlock).
//
// The engine produces two outputs:
//
//   - GenerateCSS(host) returns a CSS string that hides every matching
//     selector with `display:none!important`. The string is suitable for
//     injection into a <style> tag in the HTML response.
//   - GenerateProceduralJS(host) returns a self-contained <script> body
//     that walks the DOM applying the procedural filters.
//
// GenerateInjectionHTML(host) returns both wrapped in their respective
// tags, suitable for direct insertion into <head>.
package cosmetic

import (
        "fmt"
        "strings"
        "sync"
)

// Engine is the CSS injection + procedural filter engine.
type Engine struct {
        mu sync.RWMutex

        // lowGenericSimple = selectors that are pure class/id (e.g. ".ad", "#ads").
        // These get the fastest CSS injection path: a single CSS rule with
        // many comma-separated selectors.
        lowGenericSimple map[string]bool

        // highGenericSimple = selectors that are attribute-only
        // (e.g. "[data-ad]"). Still fast but slightly slower than class/id.
        highGenericSimple map[string]bool

        // highGenericComplex = selectors that combine class/id/attribute with
        // other selectors (e.g. "div[class*='advert']").
        highGenericComplex map[string]bool

        // specificRules = hostSuffix -> set of selectors. Only applied when
        // the host matches the suffix.
        specificRules map[string]map[string]bool

        // exceptions = hostSuffix -> set of selectors that should NOT be hidden
        // even if a generic or specific rule would hide them.
        exceptions map[string]map[string]bool

        // proceduralRules = hostSuffix -> set of procedural filter JS bodies.
        proceduralRules map[string]map[string]string
}

// New returns an empty Engine.
func New() *Engine {
        return &Engine{
                lowGenericSimple:   make(map[string]bool),
                highGenericSimple:  make(map[string]bool),
                highGenericComplex: make(map[string]bool),
                specificRules:      make(map[string]map[string]bool),
                exceptions:         make(map[string]map[string]bool),
                proceduralRules:    make(map[string]map[string]string),
        }
}

// AddGenericSelector adds a CSS selector to the generic ruleset. The
// selector is auto-classified:
//   - selectors matching `^(\.[\w-]+|#[\w-]+)$` (single class or id) go
//     to lowGenericSimple.
//   - selectors matching `^\[.+\]$` (single attribute) go to
//     highGenericSimple.
//   - everything else goes to highGenericComplex.
func (e *Engine) AddGenericSelector(sel string) {
        sel = strings.TrimSpace(sel)
        if sel == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        switch classify(sel) {
        case clsLowSimple:
                e.lowGenericSimple[sel] = true
        case clsHighSimple:
                e.highGenericSimple[sel] = true
        default:
                e.highGenericComplex[sel] = true
        }
}

// AddSpecificSelector adds a CSS selector that should only apply on hosts
// whose domain matches hostSuffix (suffix match: "youtube.com" matches
// "www.youtube.com" and "youtube.com" itself).
func (e *Engine) AddSpecificSelector(hostSuffix, sel string) {
        hostSuffix = normalizeSuffix(hostSuffix)
        sel = strings.TrimSpace(sel)
        if hostSuffix == "" || sel == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.specificRules[hostSuffix] == nil {
                e.specificRules[hostSuffix] = make(map[string]bool)
        }
        e.specificRules[hostSuffix][sel] = true
}

// AddException records that selector should NOT be hidden on hosts matching
// hostSuffix. This is the @@||example.com##.selector form.
func (e *Engine) AddException(hostSuffix, sel string) {
        hostSuffix = normalizeSuffix(hostSuffix)
        sel = strings.TrimSpace(sel)
        if hostSuffix == "" || sel == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.exceptions[hostSuffix] == nil {
                e.exceptions[hostSuffix] = make(map[string]bool)
        }
        e.exceptions[hostSuffix][sel] = true
}

// AddProceduralFilter registers a JS-based procedural filter for a host
// suffix. The filter argument is a uBlock procedural operator expression
// like "div.foo:has-text(/Sponsored/)" — the caller is responsible for
// translating it to executable JS via CompileProcedural. The compiled JS
// body is stored as a string.
func (e *Engine) AddProceduralFilter(hostSuffix, filter string) {
        hostSuffix = normalizeSuffix(hostSuffix)
        filter = strings.TrimSpace(filter)
        if hostSuffix == "" || filter == "" {
                return
        }
        js := CompileProcedural(filter)
        if js == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        if e.proceduralRules[hostSuffix] == nil {
                e.proceduralRules[hostSuffix] = make(map[string]string)
        }
        // Use the original filter text as the key so duplicate registrations
        // are idempotent.
        e.proceduralRules[hostSuffix][filter] = js
}

type classKind int

const (
        clsLowSimple classKind = iota
        clsHighSimple
        clsComplex
)

// classify inspects a CSS selector and decides which bucket it belongs to.
func classify(sel string) classKind {
        // Strip pseudo-element/class suffixes for classification purposes.
        // e.g. ".ad:has-text(...)" => ".ad" is the simple part, but we treat
        // the whole thing as complex because of the procedural suffix.
        if strings.ContainsAny(sel, ": >+~") {
                return clsComplex
        }
        if len(sel) >= 2 && sel[0] == '.' && isSimpleIdent(sel[1:]) {
                return clsLowSimple
        }
        if len(sel) >= 2 && sel[0] == '#' && isSimpleIdent(sel[1:]) {
                return clsLowSimple
        }
        if len(sel) >= 2 && sel[0] == '[' && sel[len(sel)-1] == ']' && !strings.ContainsAny(sel[1:len(sel)-1], " =") {
                return clsHighSimple
        }
        return clsComplex
}

// isSimpleIdent returns true if s is a non-empty run of [A-Za-z0-9_-] with
// no whitespace, brackets, or operators.
func isSimpleIdent(s string) bool {
        if s == "" {
                return false
        }
        for _, r := range s {
                switch {
                case r >= 'A' && r <= 'Z':
                case r >= 'a' && r <= 'z':
                case r >= '0' && r <= '9':
                case r == '-' || r == '_':
                default:
                        return false
                }
        }
        return true
}

// hostMatchesSuffix returns true if host matches hostSuffix. Matching is
// suffix-based: "youtube.com" matches "youtube.com", "www.youtube.com",
// "m.youtube.com", but NOT "notyoutube.com".
func hostMatchesSuffix(host, suffix string) bool {
        host = strings.ToLower(strings.TrimSpace(host))
        suffix = strings.ToLower(strings.TrimSpace(suffix))
        if host == suffix {
                return true
        }
        return strings.HasSuffix(host, "."+suffix)
}

// normalizeSuffix lowercases the suffix and strips any leading "." or
// "www." prefix.
func normalizeSuffix(s string) string {
        s = strings.ToLower(strings.TrimSpace(s))
        s = strings.TrimPrefix(s, ".")
        return s
}

// GenerateCSS returns the CSS string (without <style> wrapper) for the
// given host. The CSS hides every matching selector with
// `display:none!important`.
func (e *Engine) GenerateCSS(host string) string {
        e.mu.RLock()
        defer e.mu.RUnlock()

        // Collect exceptions applicable to this host.
        except := make(map[string]bool)
        for suffix, set := range e.exceptions {
                if hostMatchesSuffix(host, suffix) {
                        for sel := range set {
                                except[sel] = true
                        }
                }
        }

        var parts []string

        // Generic selectors (low simple + high simple + high complex).
        addSel := func(sel string) {
                if except[sel] {
                        return
                }
                parts = append(parts, sel)
        }
        for sel := range e.lowGenericSimple {
                addSel(sel)
        }
        for sel := range e.highGenericSimple {
                addSel(sel)
        }
        for sel := range e.highGenericComplex {
                addSel(sel)
        }

        // Specific (per-host) selectors.
        for suffix, set := range e.specificRules {
                if hostMatchesSuffix(host, suffix) {
                        for sel := range set {
                                addSel(sel)
                        }
                }
        }

        if len(parts) == 0 {
                return ""
        }
        return strings.Join(parts, ", ") + " { display: none !important; }"
}

// GenerateProceduralJS returns the JS body (without <script> wrapper) for
// the given host, applying every procedural filter whose host suffix
// matches.
func (e *Engine) GenerateProceduralJS(host string) string {
        e.mu.RLock()
        defer e.mu.RUnlock()
        var bodies []string
        for suffix, set := range e.proceduralRules {
                if hostMatchesSuffix(host, suffix) {
                        for _, js := range set {
                                bodies = append(bodies, js)
                        }
                }
        }
        if len(bodies) == 0 {
                return ""
        }
        return "(function(){\n" + strings.Join(bodies, "\n") + "\n})();"
}

// GenerateInjectionHTML returns the combined `<style>` and `<script>` block
// suitable for direct insertion into the HTML <head>. Empty blocks are
// omitted.
func (e *Engine) GenerateInjectionHTML(host string) string {
        var b strings.Builder
        if css := e.GenerateCSS(host); css != "" {
                b.WriteString("<style>")
                b.WriteString(css)
                b.WriteString("</style>")
        }
        if js := e.GenerateProceduralJS(host); js != "" {
                b.WriteString("<script>")
                b.WriteString(js)
                b.WriteString("</script>")
        }
        return b.String()
}

// CompileProcedural translates a uBlock procedural filter expression like
// "div.foo:has-text(/Sponsored/)" into a self-contained JS snippet that
// runs inside the page context. Supported operators:
//
//   - :has-text(/regex/)     — hide element if its text matches the regex
//   - :has-text("string")    — hide element if its text contains string
//   - :has(div.bar)           — hide element if it contains a descendant
//     matching the inner selector
//   - :matches-css(selector)  — hide element if it matches the given CSS
//   - :remove                 — remove the element entirely (not just hide)
//
// The returned JS runs in an IIFE and is safe to inject verbatim.
func CompileProcedural(filter string) string {
        filter = strings.TrimSpace(filter)
        if filter == "" {
                return ""
        }

        // Split selector / operator.
        colon := strings.Index(filter, ":")
        if colon < 0 {
                return ""
        }
        cssSelector := filter[:colon]
        rest := filter[colon+1:]

        switch {
        case strings.HasPrefix(rest, "has-text(") && strings.HasSuffix(rest, ")"):
                arg := rest[len("has-text(") : len(rest)-1]
                return compileHasText(cssSelector, arg)
        case strings.HasPrefix(rest, "has(") && strings.HasSuffix(rest, ")"):
                arg := rest[len("has(") : len(rest)-1]
                return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){
    if (el.querySelector(%q)) el.style.display='none';
  });
})();`, cssSelector, arg)
        case strings.HasPrefix(rest, "matches-css(") && strings.HasSuffix(rest, ")"):
                arg := rest[len("matches-css(") : len(rest)-1]
                return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){
    try { if (el.matches(%q)) el.style.display='none'; } catch(e) {}
  });
})();`, cssSelector, arg)
        case rest == "remove":
                return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){ el.remove(); });
})();`, cssSelector)
        // Phase 12b: :remove-attr(attrname) — remove specific attribute
        case strings.HasPrefix(rest, "remove-attr(") && strings.HasSuffix(rest, ")"):
                attr := rest[len("remove-attr(") : len(rest)-1]
                attr = strings.Trim(attr, "\"'")
                return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){ el.removeAttribute(%q); });
  var obs = new MutationObserver(function() {
    document.querySelectorAll(%q).forEach(function(el){ el.removeAttribute(%q); });
  });
  obs.observe(document.documentElement, {childList: true, subtree: true});
})();`, cssSelector, attr, cssSelector, attr)
        // Phase 12b: :style(css) — apply inline CSS
        case strings.HasPrefix(rest, "style(") && strings.HasSuffix(rest, ")"):
                styleCss := rest[len("style(") : len(rest)-1]
                return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){
    el.style.cssText += '; %s';
  });
})();`, cssSelector, styleCss)
        }
        return ""
}

// compileHasText produces JS for :has-text(/regex/) or :has-text("string").
func compileHasText(cssSelector, arg string) string {
        arg = strings.TrimSpace(arg)
        if arg == "" {
                return ""
        }
        // Regex form: /pattern/ or /pattern/flags
        if len(arg) >= 2 && arg[0] == '/' {
                end := strings.LastIndex(arg, "/")
                if end > 0 {
                        pattern := arg[1:end]
                        flags := arg[end+1:]
                        return fmt.Sprintf(`(function(){
  var re = new RegExp(%s, %q);
  document.querySelectorAll(%q).forEach(function(el){
    if (re.test(el.textContent || "")) el.style.display='none';
  });
})();`, jsString(pattern), flags, cssSelector)
                }
        }
        // Plain string form (strip surrounding quotes if present).
        s := strings.Trim(arg, "\"'")
        return fmt.Sprintf(`(function(){
  document.querySelectorAll(%q).forEach(function(el){
    if ((el.textContent || "").indexOf(%s) !== -1) el.style.display='none';
  });
})();`, cssSelector, jsString(s))
}

// jsString returns a JS string literal for the given Go string (single-line,
// safe enough for our purposes).
func jsString(s string) string {
        var b strings.Builder
        b.WriteByte('"')
        for _, r := range s {
                switch r {
                case '"':
                        b.WriteString(`\"`)
                case '\\':
                        b.WriteString(`\\`)
                case '\n':
                        b.WriteString(`\n`)
                case '\r':
                        b.WriteString(`\r`)
                case '\t':
                        b.WriteString(`\t`)
                default:
                        if r < 0x20 {
                                b.WriteString(fmt.Sprintf(`\u%04x`, r))
                        } else {
                                b.WriteRune(r)
                        }
                }
        }
        b.WriteByte('"')
        return b.String()
}

// LoadDefaults populates the engine with the WLT default selector set:
//   - 22 generic ad-related selectors
//   - 13 YouTube-specific selectors
//   - 6 Spotify-specific selectors
func (e *Engine) LoadDefaults() {
        generic := []string{
                ".ad", ".ads", "#ad", "#ads", ".advert", ".banner-ad",
                `[class*="advert"]`, `[id*="advert"]`, ".ad-container",
                ".ad-wrapper", ".ad-slot", ".ad-banner", ".ad-leaderboard",
                ".ad-rectangle", ".ad-skyscraper", ".promo", ".sponsor",
                ".sponsored", "[data-ad]", "[data-ads]",
                `iframe[src*="doubleclick"]`,
                `iframe[src*="googlesyndication"]`,
        }
        for _, s := range generic {
                e.AddGenericSelector(s)
        }

        youtube := []string{
                "ytd-ad-slot-renderer",
                "ytd-promoted-video-renderer",
                ".ytd-display-ad-renderer",
                "ytd-action-companion-ad-renderer",
                ".video-ads",
                ".ytp-ad-module",
                ".ytp-ad-progress",
                ".ytp-ad-overlay-container",
                `[class*="ytd-ad"]`,
                "ytd-search-pyv-renderer",
                ".ytd-banner-promo-renderer",
                "ytd-banner-promo-renderer",
                ".ytd-mealbar-promo-renderer",
        }
        for _, s := range youtube {
                e.AddSpecificSelector("youtube.com", s)
        }
        // Also cover youtu.be short-link domain.
        for _, s := range youtube {
                e.AddSpecificSelector("youtu.be", s)
        }

        spotify := []string{
                ".ad-leaderboard-container",
                ".ad-slot",
                `[data-testid="ad-slot"]`,
                ".banner-bar",
                ".leaderboard",
                ".ad-container",
                `[class*="advert"]`,
        }
        for _, s := range spotify {
                e.AddSpecificSelector("spotify.com", s)
                e.AddSpecificSelector("scdn.co", s)
        }
}
