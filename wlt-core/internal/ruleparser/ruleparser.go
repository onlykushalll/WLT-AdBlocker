// Package ruleparser implements a parser for the ABP / uBlock Origin
// filter-rule syntax used by WLT blocklists.
//
// Supported rule forms:
//
//   - `||example.com^`                       → block (network filter)
//   - `@@||example.com^`                     → allow exception
//   - `||example.com^$important`             → block, marked important
//   - `||example.com^$badfilter`             → disable matching block rule
//   - `||example.com^$domain=a.com,b.com`    → block, only on listed domains
//   - `||example.com^$third-party`           → block, only third-party requests
//   - `0.0.0.0 example.com`                  → hosts file (block)
//   - `example.com`                          → bare domain (block)
//   - `! comment` / `# comment`              → nil (skip)
//   - `example.com##.selector`               → cosmetic (REJECT — not
//     applicable at the VPN layer; the caller can collect these for the
//     HTTPS proxy cosmetic engine instead).
//   - `example.com##+js(scriptlet, args)`    → scriptlet (REJECT for VPN;
//     pass to HTTPS proxy scriptlet engine).
//
// "REJECT" means Parse returns a Rule with Type == TypeReject so the caller
// knows the rule was understood but is not applicable to the current layer.
package ruleparser

import (
	"errors"
	"strings"
)

// RuleType is the kind of rule produced by Parse.
type RuleType int

const (
	TypeUnknown RuleType = iota
	// TypeBlock is a network-blocking rule (deny the request).
	TypeBlock
	// TypeAllow is an exception rule (@@ prefix) — allow the request.
	TypeAllow
	// TypeHosts is a hosts-file block rule ("0.0.0.0 domain").
	TypeHosts
	// TypeBare is a bare-domain block rule (no ABP decorators).
	TypeBare
	// TypeCosmetic is a CSS cosmetic rule ("##.selector"). Not applicable
	// at the VPN layer.
	TypeCosmetic
	// TypeScriptlet is a uBlock scriptlet rule ("##+js(...)"). Not
	// applicable at the VPN layer.
	TypeScriptlet
	// TypeReject means the rule was understood but is rejected (e.g. cosmetic
	// at the VPN layer, or a comment).
	TypeReject
)

// Rule is a parsed filter rule.
type Rule struct {
	// Raw is the original input line (trimmed).
	Raw string

	// Type is the rule type.
	Type RuleType

	// Domain is the primary domain targeted by the rule (without any ABP
	// decorators). For cosmetic/scriptlet rules this is the host that
	// scopes the rule ("" if no host).
	Domain string

	// IsAllow is true for @@ exception rules.
	IsAllow bool
	// IsImportant is true for $important modifier.
	IsImportant bool
	// IsBadfilter is true for $badfilter modifier.
	IsBadfilter bool

	// SourceDomains is the list of domains from $domain=... (empty if
	// absent). Domains prefixed with "~" are exclusions.
	SourceDomains []string

	// ThirdParty is true if $third-party modifier is present.
	ThirdParty bool

	// CosmeticSelector is the CSS selector for cosmetic rules.
	CosmeticSelector string
	// ScriptletName is the scriptlet name (without args) for scriptlet rules.
	ScriptletName string
	// ScriptletArgs is the raw args string (between the parens) for scriptlet rules.
	ScriptletArgs string

	// HostsIP is the IP address from a hosts-file rule (e.g. "0.0.0.0").
	HostsIP string
}

// Parse parses one filter-list line into a Rule. Returns nil (with nil
// error) for comments and blank lines. Returns an error only for malformed
// rules that we cannot interpret.
func Parse(line string) (*Rule, error) {
	line = strings.TrimSpace(line)
	if line == "" {
		return nil, nil
	}

	// Comments: lines starting with ! or [ (ABP header) or # but NOT
	// followed by # (## = cosmetic). The preprocessor !#if directives are
	// handled by the preparser package; here we just treat them as
	// comments.
	if strings.HasPrefix(line, "!") {
		return nil, nil
	}
	if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
		return nil, nil
	}
	// Plain "# comment" (one # at the start, NOT ##).
	if strings.HasPrefix(line, "#") && !strings.HasPrefix(line, "##") && !strings.HasPrefix(line, "#@#") {
		// Could still be a hosts-file comment with a leading "#" but
		// ABP uses "!" so this is fine.
		return nil, nil
	}

	// Cosmetic rules: host##selector or host#@#selector (the latter is a
	// cosmetic exception). The separator "##" or "#@#" may appear without
	// a host prefix (just "##.selector" for a global cosmetic).
	if idx := strings.Index(line, "##"); idx >= 0 {
		host := ""
		if idx > 0 {
			host = line[:idx]
		}
		rest := line[idx+2:]
		if strings.HasPrefix(rest, "+js(") && strings.HasSuffix(rest, ")") {
			args := rest[len("+js(") : len(rest)-1]
			name := args
			if comma := strings.Index(args, ","); comma >= 0 {
				name = args[:comma]
			}
			return &Rule{
				Raw:           line,
				Type:          TypeScriptlet,
				Domain:        host,
				ScriptletName: strings.TrimSpace(name),
				ScriptletArgs: args,
			}, nil
		}
		return &Rule{
			Raw:              line,
			Type:             TypeCosmetic,
			Domain:           host,
			CosmeticSelector: rest,
		}, nil
	}
	// Cosmetic exception: host#@#selector.
	if idx := strings.Index(line, "#@#"); idx >= 0 {
		host := ""
		if idx > 0 {
			host = line[:idx]
		}
		return &Rule{
			Raw:              line,
			Type:             TypeCosmetic,
			Domain:           host,
			CosmeticSelector: line[idx+3:],
			IsAllow:          true,
		}, nil
	}

	// Network rules: maybe @@ prefix, maybe || prefix.
	r := &Rule{Raw: line, Type: TypeBlock}
	body := line
	if strings.HasPrefix(body, "@@") {
		r.IsAllow = true
		r.Type = TypeAllow
		body = body[2:]
	}

	// Split body into domain part + $options.
	var options string
	if dollar := strings.Index(body, "$"); dollar >= 0 {
		options = body[dollar+1:]
		body = body[:dollar]
	}

	// Strip ABP decorators: leading "||" and trailing "^".
	body = strings.TrimPrefix(body, "||")
	body = strings.TrimSuffix(body, "^")
	body = strings.TrimSpace(body)
	if body == "" {
		return nil, errors.New("ruleparser: empty domain")
	}
	r.Domain = body

	// Parse options.
	if options != "" {
		if err := parseOptions(r, options); err != nil {
			return nil, err
		}
	}

	// If the rule has no ABP decorators (no "||" prefix, no "$"), treat as
	// a bare domain or a hosts-file entry.
	if !strings.HasPrefix(line, "||") && !strings.HasPrefix(line, "@@||") && options == "" {
		// Check for hosts-file form: "0.0.0.0 domain" or "127.0.0.1 domain".
		parts := strings.Fields(line)
		if len(parts) == 2 && isIP(parts[0]) {
			return &Rule{
				Raw:     line,
				Type:    TypeHosts,
				Domain:  parts[1],
				HostsIP: parts[0],
			}, nil
		}
		// Bare domain (no decorator, no options).
		r.Type = TypeBare
	}

	return r, nil
}

// parseOptions parses the comma-separated $options string and populates the
// Rule. Returns an error for malformed options.
func parseOptions(r *Rule, options string) error {
	for _, opt := range strings.Split(options, ",") {
		opt = strings.TrimSpace(opt)
		switch {
		case opt == "important":
			r.IsImportant = true
		case opt == "badfilter":
			r.IsBadfilter = true
		case opt == "third-party" || opt == "3p":
			r.ThirdParty = true
		case opt == "~third-party" || opt == "~3p":
			r.ThirdParty = false
		case strings.HasPrefix(opt, "domain="):
			list := opt[len("domain="):]
			r.SourceDomains = strings.Split(list, "|")
			for i, d := range r.SourceDomains {
				r.SourceDomains[i] = strings.TrimSpace(d)
			}
		case strings.HasPrefix(opt, "scriptlet=") || strings.HasPrefix(opt, "redirect-rule=") ||
			strings.HasPrefix(opt, "rewrite=") || strings.HasPrefix(opt, "replace=") ||
			strings.HasPrefix(opt, "removeparam=") || strings.HasPrefix(opt, "header=") ||
			strings.HasPrefix(opt, "method=") || strings.HasPrefix(opt, "ctype=") ||
			strings.HasPrefix(opt, "permissions=") || strings.HasPrefix(opt, "popup"):
			// Acknowledged but otherwise ignored — these options don't
			// affect the basic block/allow decision at the VPN layer.
		case opt == "document" || opt == "image" || opt == "stylesheet" ||
			opt == "script" || opt == "xhr" || opt == "frame" || opt == "subdocument" ||
			opt == "object" || opt == "media" || opt == "other" || opt == "font" ||
			opt == "websocket" || opt == "ping" || opt == "generichide" || opt == "specifichide":
			// Content-type modifiers — ignored at the VPN layer.
		case strings.HasPrefix(opt, "~"):
			// Negated content-type modifier — ignored.
		default:
			// Unknown option: ignore silently (we'd rather accept the
			// rule with a dropped option than reject the whole list).
		}
	}
	return nil
}

// isIP returns true if s looks like an IPv4 address (used to detect hosts-
// file rules). We deliberately don't require strict RFC validity — the
// goal is just to distinguish "0.0.0.0 domain" from "domain".
func isIP(s string) bool {
	if s == "" {
		return false
	}
	parts := strings.Split(s, ".")
	if len(parts) != 4 {
		return false
	}
	for _, p := range parts {
		if p == "" || len(p) > 3 {
			return false
		}
		for _, r := range p {
			if r < '0' || r > '9' {
				return false
			}
		}
	}
	return true
}
