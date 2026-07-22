// Package ruleparser implements an ABP/uBlock-compatible network rule parser.
//
// This lets users paste rules from uBlock Origin/AdBlock Plus forums directly
// into WLT's "My Filters" screen. Supported syntax:
//
//   ||example.com^              — block domain
//   @@||example.com^            — allow domain (exception)
//   ||example.com^$important    — override allowlists
//   ||example.com^$badfilter    — disable another matching rule
//   ||example.com^$domain=x.com — only block when source domain is x.com
//   ||example.com^$third-party  — only block third-party requests
//   0.0.0.0 example.com         — hosts file format
//   127.0.0.1 example.com       — hosts file format
//   example.com                 — bare domain
//   ! comment                    — comment
//   # comment                    — comment
//
// Not supported (requires browser DOM/JS — not applicable to VPN):
//   ##selector                  — cosmetic filtering
//   ##+js(name, args)           — scriptlet injection
//   $csp, $replace, $redirect   — response modification
package ruleparser

import (
	"strings"
)

// RuleType defines what kind of rule this is.
type RuleType int

const (
	RuleBlock     RuleType = iota // block the domain
	RuleAllow                     // allow (exception — overrides blocks)
	RuleBadFilter                 // disable another matching rule
)

// ParsedRule represents a parsed network filter rule.
type ParsedRule struct {
	Raw       string   // original rule text
	Domain    string   // domain to block/allow (normalized)
	Type      RuleType // block, allow, or badfilter
	Important bool     // $important modifier
	ThirdParty bool    // $third-party modifier (ignored at VPN layer but parsed)
	Domains   []string // $domain= modifier (source domains — stored but not enforced at DNS layer)
	Valid     bool     // whether this rule was successfully parsed
	Reason    string   // why it was rejected if !Valid
}

// Parse parses a single line of filter text into a ParsedRule.
// Returns a ParsedRule with Valid=false for comments/empty/cosmetic rules.
func Parse(line string) ParsedRule {
	raw := line
	line = strings.TrimSpace(line)

	// Empty
	if line == "" {
		return ParsedRule{Raw: raw, Valid: false, Reason: "empty"}
	}

	// Comments
	if strings.HasPrefix(line, "!") || strings.HasPrefix(line, "#") && !strings.HasPrefix(line, "##") {
		return ParsedRule{Raw: raw, Valid: false, Reason: "comment"}
	}

	// Cosmetic rules (##) — not supported at VPN layer
	if strings.Contains(line, "##") || strings.Contains(line, "#@#") {
		return ParsedRule{Raw: raw, Valid: false, Reason: "cosmetic rule (not supported at VPN layer)"}
	}

	// Scriptlet injection (##+js) — not supported
	if strings.Contains(line, "##+js(") {
		return ParsedRule{Raw: raw, Valid: false, Reason: "scriptlet injection (not supported at VPN layer)"}
	}

	rule := ParsedRule{Raw: raw, Valid: true}
	lineLower := strings.ToLower(line)

	// Check for exception rule (@@)
	if strings.HasPrefix(lineLower, "@@") {
		rule.Type = RuleAllow
		line = line[2:]
		lineLower = lineLower[2:]
	} else {
		rule.Type = RuleBlock
	}

	// Check for hosts file format: "0.0.0.0 domain" or "127.0.0.1 domain"
	if strings.HasPrefix(lineLower, "0.0.0.0 ") || strings.HasPrefix(lineLower, "127.0.0.1 ") {
		parts := strings.Fields(line)
		if len(parts) >= 2 {
			rule.Domain = normalize(parts[1])
			return rule
		}
		return ParsedRule{Raw: raw, Valid: false, Reason: "invalid hosts format"}
	}

	// Check for ABP format: ||domain^
	if strings.HasPrefix(lineLower, "||") {
		line = line[2:]
		lineLower = lineLower[2:]

		// Extract domain (up to ^, /, $, or end)
		domain := line
		for i, c := range line {
			if c == '^' || c == '/' || c == '$' {
				domain = line[:i]
				break
			}
		}
		rule.Domain = normalize(domain)

		// Parse modifiers (after $)
		if idx := strings.Index(line, "$"); idx >= 0 {
			modifiers := line[idx+1:]
			parseModifiers(&rule, modifiers)
		}
		return rule
	}

	// Bare domain (no || prefix, no hosts format)
	// Must look like a domain (contains a dot, no spaces)
	if strings.Contains(line, ".") && !strings.Contains(line, " ") {
		// Strip any modifiers
		if idx := strings.Index(line, "$"); idx >= 0 {
			rule.Domain = normalize(line[:idx])
			parseModifiers(&rule, line[idx+1:])
		} else {
			rule.Domain = normalize(line)
		}
		return rule
	}

	return ParsedRule{Raw: raw, Valid: false, Reason: "unrecognized format"}
}

// parseModifiers parses ABP modifier string (e.g., "important,domain=example.com,third-party")
func parseModifiers(rule *ParsedRule, modifiers string) {
	parts := strings.Split(modifiers, ",")
	for _, part := range parts {
		part = strings.TrimSpace(part)
		switch {
		case part == "important":
			rule.Important = true
		case part == "badfilter":
			rule.Type = RuleBadFilter
		case part == "third-party" || part == "3p":
			rule.ThirdParty = true
		case strings.HasPrefix(part, "domain="):
			domains := strings.TrimPrefix(part, "domain=")
			for _, d := range strings.Split(domains, "|") {
				d = strings.TrimSpace(d)
				if d != "" {
					rule.Domains = append(rule.Domains, normalize(d))
				}
			}
		// Modifiers we recognize but can't enforce at DNS layer:
		case part == "script" || part == "image" || part == "xhr" ||
			part == "frame" || part == "subdocument" || part == "popup" ||
			part == "media" || part == "font" || part == "stylesheet" ||
			part == "websocket" || part == "ping" || part == "webrtc":
			// Request type — stored but not enforced (VPN can't see request type)
		case strings.HasPrefix(part, "removeparam="):
			// URL parameter stripping — not supported at DNS layer
		case strings.HasPrefix(part, "redirect="):
			// Resource redirect — not supported at DNS layer
		case strings.HasPrefix(part, "csp="):
			// CSP modification — not supported at DNS layer
		}
	}
}

// normalize lowercases and trims the domain.
func normalize(d string) string {
	return strings.ToLower(strings.Trim(strings.TrimSpace(d), ".^"))
}

// ParseMulti parses multiple lines and returns only valid network rules.
func ParseMulti(text string) []ParsedRule {
	var rules []ParsedRule
	for _, line := range strings.Split(text, "\n") {
		rule := Parse(line)
		if rule.Valid {
			rules = append(rules, rule)
		}
	}
	return rules
}

// IsBlock returns true if the rule is a block rule.
func (r ParsedRule) IsBlock() bool {
	return r.Valid && r.Type == RuleBlock
}

// IsAllow returns true if the rule is an allow (exception) rule.
func (r ParsedRule) IsAllow() bool {
	return r.Valid && r.Type == RuleAllow
}

// IsBadFilter returns true if the rule disables another matching rule.
func (r ParsedRule) IsBadFilter() bool {
	return r.Valid && r.Type == RuleBadFilter
}
