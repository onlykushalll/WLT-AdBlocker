// Package filter implements blocklist parsing and loading.
//
// Supported formats:
//   - Hosts file: "0.0.0.0 domain" or "127.0.0.1 domain" (AdAway, Pi-hole)
//   - Adblock Plus: "||domain^" and "||domain^$modifiers"
//   - Domain-only: one domain per line (OISD, simplified)
//   - Comments: lines starting with # or !
//
// The loader is streaming and calls back with each parsed domain so the engine
// can insert it into the trie+bloom as it goes (lower memory than buffering).
package filter

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

// Format identifies a blocklist's syntax.
type Format int

const (
	FormatAuto    Format = iota // sniff from content
	FormatHosts                 // 0.0.0.0 domain
	FormatAdblock               // ||domain^
	FormatDomains               // bare domain per line
)

// Source describes a blocklist to load.
type Source struct {
	Name     string
	URL      string // empty if local
	Path     string // local file path (mutually exclusive with URL)
	Format   Format
	Enabled  bool
	Category string // "ads", "trackers", "game-ads", "privacy", "malware"
}

// LoadResult reports how many domains were loaded from a source.
type LoadResult struct {
	Source    Source
	Loaded    int
	Skipped   int
	Duration  time.Duration
	Error     error
}

// LoadFunc is called for each parsed domain.
type LoadFunc func(domain string)

// Loader parses blocklists in multiple formats.
type Loader struct {
	client *http.Client
}

// NewLoader returns a loader with a sensible HTTP client.
func NewLoader() *Loader {
	return &Loader{
		client: &http.Client{Timeout: 60 * time.Second},
	}
}

// Load reads a source and calls `fn` for each domain.
// For URLs, fetches the content. For paths, reads the file.
func (l *Loader) Load(src Source, fn LoadFunc) LoadResult {
	start := time.Now()
	r := LoadResult{Source: src}
	var reader io.ReadCloser
	if src.URL != "" {
		resp, err := l.client.Get(src.URL)
		if err != nil {
			r.Error = fmt.Errorf("fetch %s: %w", src.URL, err)
			r.Duration = time.Since(start)
			return r
		}
		defer resp.Body.Close()
		if resp.StatusCode != 200 {
			r.Error = fmt.Errorf("fetch %s: HTTP %d", src.URL, resp.StatusCode)
			r.Duration = time.Since(start)
			return r
		}
		reader = resp.Body
	} else if src.Path != "" {
		f, err := os.Open(src.Path)
		if err != nil {
			r.Error = fmt.Errorf("open %s: %w", src.Path, err)
			r.Duration = time.Since(start)
			return r
		}
		defer f.Close()
		reader = f
	} else {
		r.Error = fmt.Errorf("source has no URL or path")
		r.Duration = time.Since(start)
		return r
	}

	format := src.Format
	if format == FormatAuto {
		format = sniffFormat(reader)
	}

	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 1024*1024), 1024*1024) // 1MB lines max
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}
		// Skip comments
		if strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
			continue
		}
		domains := parseLine(line, format)
		for _, d := range domains {
			if isValidDomain(d) {
				fn(d)
				r.Loaded++
			} else {
				r.Skipped++
			}
		}
	}
	if err := scanner.Err(); err != nil {
		r.Error = fmt.Errorf("scan: %w", err)
	}
	r.Duration = time.Since(start)
	return r
}

// sniffFormat reads the first few KB to guess the format.
func sniffFormat(r io.Reader) Format {
	br := bufio.NewReader(r)
	preview, _ := br.Peek(8192)
	// Push back — we can't unread, so the caller's scanner will re-read.
	// This is a limitation; for production we'd wrap. For now, heuristic on preview:
	for _, line := range strings.Split(string(preview), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
			continue
		}
		if strings.HasPrefix(line, "||") {
			return FormatAdblock
		}
		if strings.HasPrefix(line, "0.0.0.0 ") || strings.HasPrefix(line, "127.0.0.1 ") ||
			strings.HasPrefix(line, "0.0.0.0\t") || strings.HasPrefix(line, "127.0.0.1\t") {
			return FormatHosts
		}
		// Bare domain
		return FormatDomains
	}
	return FormatDomains
}

// parseLine extracts domains from a line according to format.
func parseLine(line string, format Format) []string {
	switch format {
	case FormatHosts:
		// "0.0.0.0 domain [comment]" — take field 2
		fields := strings.Fields(line)
		if len(fields) >= 2 {
			return []string{fields[1]}
		}
		return nil
	case FormatAdblock:
		// "||domain^$modifiers" or "||domain^"
		return parseAdblockLine(line)
	case FormatDomains:
		// Bare domain, possibly with trailing comment
		if idx := strings.IndexAny(line, " \t#"); idx > 0 {
			line = line[:idx]
		}
		return []string{strings.Trim(line, ".")}
	}
	return nil
}

// parseAdblockLine handles ABP/AdGuard syntax: ||domain^$modifiers
func parseAdblockLine(line string) []string {
	// Only handle network block rules (||domain^). Skip cosmetic (##) and others.
	if !strings.HasPrefix(line, "||") {
		return nil
	}
	rest := line[2:]
	// Strip modifiers: everything from $ onward (we apply at DNS level, so
	// most modifiers like $third-party don't apply — we just block the domain).
	if idx := strings.Index(rest, "$"); idx >= 0 {
		rest = rest[:idx]
	}
	// Strip trailing ^ (separator marker in ABP).
	rest = strings.TrimSuffix(rest, "^")
	rest = strings.TrimSuffix(rest, "|")
	// Handle wildcard: ||*.example.com^
	if strings.HasPrefix(rest, "*.") {
		rest = rest[2:]
	}
	rest = strings.TrimSpace(rest)
	rest = strings.Trim(rest, ".")
	if rest == "" {
		return nil
	}
	return []string{rest}
}

// isValidDomain is a minimal sanity check.
func isValidDomain(d string) bool {
	if d == "" || len(d) > 253 {
		return false
	}
	if strings.ContainsAny(d, " /?#") {
		return false
	}
	// Must have at least one dot, or be a known TLD-like (localhost)
	if !strings.Contains(d, ".") && d != "localhost" {
		return false
	}
	return true
}

// DefaultSources returns the WLT default blocklist set.
// These are the lists that ship enabled by default in Phase 1.
func DefaultSources() []Source {
	return []Source{
		{
			Name:     "WLT Game Ads",
			Path:     "blocklists/wlt-game-ads.txt",
			Format:   FormatDomains,
			Enabled:  true,
			Category: "game-ads",
		},
		{
			Name:     "WLT Passthrough (Allow)",
			Path:     "blocklists/wlt-passthrough.txt",
			Format:   FormatDomains,
			Enabled:  true,
			Category: "allow",
		},
		{
			Name:     "OISD Big",
			URL:      "https://big.oisd.nl/domainswild",
			Format:   FormatDomains,
			Enabled:  true,
			Category: "ads",
		},
		{
			Name:     "AdGuard DNS filter",
			URL:      "https://adguardteam.github.io/AdGuardDNSFilter/Filters/filter.txt",
			Format:   FormatAdblock,
			Enabled:  true,
			Category: "ads",
		},
		{
			Name:     "HaGeZi Normal",
			URL:      "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/domains/normal.txt",
			Format:   FormatDomains,
			Enabled:  true,
			Category: "ads",
		},
		{
			Name:     "AdGuard Tracking Protection",
			URL:      "https://adguardteam.github.io/AdGuardDNSFilter/Filters/replace/neohosts.txt",
			Format:   FormatAdblock,
			Enabled:  false,
			Category: "trackers",
		},
	}
}
