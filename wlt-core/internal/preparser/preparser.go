// Package preparser implements the uBlock Origin pre-parsing directives
// processor.
//
// Supported directives (line-leading, case-sensitive on the `!#` prefix):
//
//   !#if ENV_TOKEN                — start a conditional block; lines until
//                                   the matching else/endif are kept only
//                                   if ENV_TOKEN is true in the env map.
//   !#else                        — invert the current block's keep-flag.
//   !#endif                       — close the current block.
//   !#include URL_OR_PATH         — invoke the include callback to fetch
//                                   another list and inline its lines.
//
// WLT-specific tokens (defined here so callers don't have to remember):
//
//   ext_wlt          — true when WLT is the consuming engine
//   env_android      — true when running on Android
//   cap_dns_blocking — true when DNS-layer blocking is available
//   cap_mitm         — true when HTTPS MITM (and thus cosmetic/scriptlets)
//                      is available
//
// uBlock-compatible tokens (also recognised):
//
//   ext_ublock       — true when uBlock Origin is the consuming engine
//   env_chromium     — true on Chromium-based browsers
//   env_firefox      — true on Firefox-based browsers
//   env_edge, env_safari, env_opera — analogous
//   false, true      — literal booleans
//   !#if !TOKEN      — negation
//
// Nested if blocks are supported. The !#include resolver is a caller-
// supplied callback that takes the include target and returns its lines
// (so the caller can decide whether to fetch from disk, HTTP, or a
// bundled asset). An empty env map means "all conditional blocks are
// dropped unless they contain a literal `true` token".
package preparser

import (
	"strings"
)

// Process runs the preprocessor over lines and returns the output lines
// (directives stripped, included lists inlined, conditional blocks
// resolved). env maps token -> bool. include is called for every
// !#include directive; it may return nil or an empty slice if the include
// can't be resolved (the include line is then dropped silently).
//
// env may be nil — equivalent to an empty map.
func Process(lines []string, env map[string]bool, include func(target string) []string) []string {
	if env == nil {
		env = map[string]bool{}
	}
	p := &processor{env: env, include: include}
	return p.run(lines)
}

type processor struct {
	env     map[string]bool
	include func(target string) []string

	// stack of conditional frames; each frame tracks whether its block is
	// currently being kept and whether any prior branch in the same
	// if/else chain has been kept.
	stack []frame
}

type frame struct {
	keep        bool // currently kept?
	anyKept     bool // any branch in this if/else chain kept so far?
	parentKept  bool // was the parent frame keeping?
}

// run processes the input lines and returns the output.
func (p *processor) run(lines []string) []string {
	var out []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(trimmed, "!#if "):
			p.pushIf(strings.TrimSpace(trimmed[len("!#if "):]))
		case trimmed == "!#else":
			p.flipElse()
		case trimmed == "!#endif":
			p.popIf()
		case strings.HasPrefix(trimmed, "!#include "):
			if p.keep() {
				target := strings.TrimSpace(trimmed[len("!#include "):])
				if p.include != nil {
					out = append(out, p.include(target)...)
				}
			}
		default:
			if p.keep() {
				out = append(out, line)
			}
		}
	}
	return out
}

// keep returns true if the current conditional context is "keep this line".
func (p *processor) keep() bool {
	if len(p.stack) == 0 {
		return true
	}
	return p.stack[len(p.stack)-1].keep
}

// pushIf starts a new conditional frame. The expression is evaluated
// against the env map; if the parent frame is not kept, this frame is
// also not kept (nested blocks inherit the parent's decision).
func (p *processor) pushIf(expr string) {
	parentKept := true
	if len(p.stack) > 0 {
		parentKept = p.stack[len(p.stack)-1].keep
	}
	keep := parentKept && evalExpr(expr, p.env)
	p.stack = append(p.stack, frame{
		keep:       keep,
		anyKept:    keep,
		parentKept: parentKept,
	})
}

// flipElse inverts the current frame's keep flag. If the parent is not
// kept, the else branch is also not kept.
func (p *processor) flipElse() {
	if len(p.stack) == 0 {
		return
	}
	f := &p.stack[len(p.stack)-1]
	if !f.parentKept {
		f.keep = false
		return
	}
	if f.anyKept {
		// A prior branch already kept — else branch must NOT keep.
		f.keep = false
	} else {
		f.keep = true
		f.anyKept = true
	}
}

// popIf closes the current conditional frame.
func (p *processor) popIf() {
	if len(p.stack) == 0 {
		return
	}
	p.stack = p.stack[:len(p.stack)-1]
}

// evalExpr evaluates a single !#if expression against the env map.
// Supported forms:
//   - "TOKEN"        — true iff env[TOKEN] is true
//   - "!TOKEN"       — negation
//   - "TOKEN1 && TOKEN2" — logical AND
//   - "TOKEN1 || TOKEN2" — logical OR
//   - "true", "false" — literals
func evalExpr(expr string, env map[string]bool) bool {
	expr = strings.TrimSpace(expr)
	if expr == "" {
		return false
	}
	// Split on || (lowest precedence).
	if orParts := splitTop(expr, "||"); len(orParts) > 1 {
		for _, p := range orParts {
			if evalExpr(p, env) {
				return true
			}
		}
		return false
	}
	// Split on &&.
	if andParts := splitTop(expr, "&&"); len(andParts) > 1 {
		for _, p := range andParts {
			if !evalExpr(p, env) {
				return false
			}
		}
		return true
	}
	// Negation.
	expr = strings.TrimSpace(expr)
	if strings.HasPrefix(expr, "!") {
		return !evalExpr(strings.TrimSpace(expr[1:]), env)
	}
	// Parenthesised sub-expression.
	if strings.HasPrefix(expr, "(") && strings.HasSuffix(expr, ")") {
		return evalExpr(expr[1:len(expr)-1], env)
	}
	// Literals.
	switch expr {
	case "true":
		return true
	case "false":
		return false
	}
	// Token lookup.
	return env[expr]
}

// splitTop splits s on sep at the top level (not inside parentheses).
func splitTop(s, sep string) []string {
	var parts []string
	depth := 0
	start := 0
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '(':
			depth++
		case ')':
			if depth > 0 {
				depth--
			}
		}
		if depth == 0 && i+len(sep) <= len(s) && s[i:i+len(sep)] == sep {
			parts = append(parts, strings.TrimSpace(s[start:i]))
			start = i + len(sep)
			i += len(sep) - 1
		}
	}
	parts = append(parts, strings.TrimSpace(s[start:]))
	return parts
}
