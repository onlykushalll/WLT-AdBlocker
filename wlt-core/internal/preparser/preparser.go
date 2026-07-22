// Package preparser implements uBlock Origin's pre-parsing directives
// for filter lists: !#include, !#if, !#else, !#endif.
//
// This lets WLT use filter lists that have platform-specific sections
// (e.g., Firefox-only rules, Chromium-only rules) without manually editing them.
//
// WLT-specific tokens:
//   ext_wlt      — true (WLT is the engine)
//   env_android  — true (running on Android)
//   cap_dns_blocking — true (DNS-level blocking supported)
//   cap_mitm     — true if HTTPS MITM is enabled
//   cap_ipv6     — true if IPv6 is supported
//
// Standard uBlock tokens (for compatibility):
//   ext_ublock, ext_abp, env_mobile, env_chromium, env_firefox, false
package preparser

import (
	"strings"
)

// Preprocessor handles !#if / !#include directive processing.
type Preprocessor struct {
	env map[string]bool
}

// New creates a preprocessor with WLT's default environment.
func New(mitmEnabled bool) *Preprocessor {
	return &Preprocessor{
		env: map[string]bool{
			"ext_wlt":            true,
			"env_android":        true,
			"env_mobile":         true,
			"cap_dns_blocking":   true,
			"cap_mitm":          mitmEnabled,
			"cap_ipv6":          true,
			"ext_ublock":        false,
			"ext_abp":           false,
			"ext_ubol":          false,
			"env_chromium":      false,
			"env_firefox":       false,
			"env_edge":          false,
			"env_safari":        false,
			"false":             false,
		},
	}
}

// Process takes raw filter list text and returns the processed text
// with all !#if/!#else/!#endif conditionals resolved and !#include directives
// expanded (if a resolver is provided).
func (p *Preprocessor) Process(text string, includeResolver func(path string) (string, error)) string {
	lines := strings.Split(text, "\n")
	var output []string

	// Stack for nested !#if blocks
	type ifBlock struct {
		condition bool  // evaluated condition
		inElse    bool  // are we in the !#else branch?
		active    bool  // is the current branch active?
		parentActive bool // was the parent block active?
	}
	var stack []ifBlock

	isActive := func() bool {
		if len(stack) == 0 {
			return true
		}
		return stack[len(stack)-1].active
	}

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)

		// !#if directive
		if strings.HasPrefix(trimmed, "!#if ") {
			cond := strings.TrimPrefix(trimmed, "!#if ")
			cond = strings.TrimSpace(cond)
			negated := strings.HasPrefix(cond, "!")
			if negated {
				cond = strings.TrimSpace(cond[1:])
			}
			val := p.env[cond]
			result := val
			if negated {
				result = !val
			}
			parentActive := isActive()
			block := ifBlock{
				condition: result,
				active: parentActive && result,
				parentActive: parentActive,
			}
			stack = append(stack, block)
			continue
		}

		// !#else directive
		if trimmed == "!#else" {
			if len(stack) > 0 {
				block := &stack[len(stack)-1]
				block.inElse = true
				block.active = block.parentActive && !block.condition
			}
			continue
		}

		// !#endif directive
		if trimmed == "!#endif" {
			if len(stack) > 0 {
				stack = stack[:len(stack)-1]
			}
			continue
		}

		// !#include directive
		if strings.HasPrefix(trimmed, "!#include ") && isActive() {
			includePath := strings.TrimSpace(strings.TrimPrefix(trimmed, "!#include "))
			if includeResolver != nil {
				included, err := includeResolver(includePath)
				if err == nil && included != "" {
					// Recursively process the included content
					included = p.Process(included, includeResolver)
					output = append(output, strings.Split(included, "\n")...)
				}
			}
			continue
		}

		// Regular line — include only if active
		if isActive() {
			output = append(output, line)
		}
	}

	return strings.Join(output, "\n")
}

// SetEnv sets an environment variable for conditional evaluation.
func (p *Preprocessor) SetEnv(key string, value bool) {
	p.env[key] = value
}

// GetEnv returns the value of an environment variable.
func (p *Preprocessor) GetEnv(key string) bool {
	return p.env[key]
}
