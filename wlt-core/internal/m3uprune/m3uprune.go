// Package m3uprune implements HLS playlist ad-segment stripping.
//
// This is the Go port of uBlock Origin's m3u-prune scriptlet.
// It parses M3U8 playlists and removes ad segments marked with:
//   - #EXT-X-CUE:TYPE="SpliceOut" (SCTE-35 ad insertion markers)
//   - #EXTINF segments matching ad URL patterns
//   - #EXT-X-DISCONTINUITY tags around ad segments
//
// Used in Phase 3 (HTTPS filtering) to strip YouTube/browser video ads
// from HLS streams at the proxy level.
package m3uprune

import (
	"regexp"
	"strings"
)

// Pruner holds compiled regex patterns for matching ad segments.
type Pruner struct {
	m3uPattern *regexp.Regexp
	urlPattern *regexp.Regexp
}

// New creates a Pruner with the given patterns.
// m3uPattern matches ad segment URLs within the playlist.
// urlPattern matches the playlist URL itself (to decide whether to prune).
func New(m3uPattern, urlPattern string) *Pruner {
	p := &Pruner{}
	if m3uPattern != "" {
		if re, err := regexp.Compile(m3uPattern); err == nil {
			p.m3uPattern = re
		}
	}
	if urlPattern != "" {
		if re, err := regexp.Compile(urlPattern); err == nil {
			p.urlPattern = re
		}
	}
	return p
}

// ShouldPrune checks if a URL matches the playlist URL pattern.
func (p *Pruner) ShouldPrune(url string) bool {
	if p.urlPattern == nil {
		return false
	}
	return p.urlPattern.MatchString(url)
}

// Prune parses an M3U8 playlist and removes ad segments.
// Returns the pruned playlist text. If the input is not M3U8, returns it unchanged.
func (p *Pruner) Prune(text string) string {
	// Must start with #EXTM3U
	if !strings.HasPrefix(strings.TrimSpace(text), "#EXTM3U") {
		return text
	}
	if p.m3uPattern == nil {
		return text
	}

	lines := strings.Split(text, "\n")
	pruned := 0

	for i := 0; i < len(lines); i++ {
		// Check for SCTE-35 SpliceOut ad markers
		if strings.HasPrefix(lines[i], `#EXT-X-CUE:TYPE="SpliceOut"`) {
			// Remove the cue block: CUE, ASSET, SCTE35, CUE-IN, SCTE35
			lines[i] = ""
			pruned++
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-ASSET") {
				lines[i+1] = ""
				i++
			}
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-SCTE35") {
				lines[i+1] = ""
				i++
			}
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-CUE-IN") {
				lines[i+1] = ""
				i++
			}
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-SCTE35") {
				lines[i+1] = ""
				i++
			}
			continue
		}

		// Check for EXTINF segments with ad URLs
		if strings.HasPrefix(lines[i], "#EXTINF") {
			if i+1 < len(lines) && p.m3uPattern.MatchString(lines[i+1]) {
				// Remove EXTINF + URL + DISCONTINUITY
				lines[i] = ""
				lines[i+1] = ""
				pruned++
				i++
				if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-DISCONTINUITY") {
					lines[i+1] = ""
					i++
				}
			}
		}
	}

	if pruned == 0 {
		return text
	}

	// Rejoin, skipping empty lines
	result := strings.Join(filterEmpty(lines), "\n")
	return result
}

func filterEmpty(lines []string) []string {
	out := make([]string, 0, len(lines))
	for _, l := range lines {
		if l != "" {
			out = append(out, l)
		}
	}
	return out
}
