package m3uprune

import (
	"regexp"
	"strings"
)

type Pruner struct {
	m3uPattern *regexp.Regexp
	urlPattern *regexp.Regexp
}

func New(m3uPattern, urlPattern string) *Pruner {
	p := &Pruner{}
	if m3uPattern != "" { if re, err := regexp.Compile(m3uPattern); err == nil { p.m3uPattern = re } }
	if urlPattern != "" { if re, err := regexp.Compile(urlPattern); err == nil { p.urlPattern = re } }
	return p
}

func (p *Pruner) ShouldPrune(url string) bool {
	if p.urlPattern == nil { return false }
	return p.urlPattern.MatchString(url)
}

func (p *Pruner) Prune(text string) string {
	if !strings.HasPrefix(strings.TrimSpace(text), "#EXTM3U") { return text }
	if p.m3uPattern == nil { return text }
	lines := strings.Split(text, "\n")
	for i := 0; i < len(lines); i++ {
		if strings.HasPrefix(lines[i], `#EXT-X-CUE:TYPE="SpliceOut"`) {
			lines[i] = ""
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-ASSET") { lines[i+1] = ""; i++ }
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-SCTE35") { lines[i+1] = ""; i++ }
			if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-CUE-IN") { lines[i+1] = ""; i++ }
			continue
		}
		if strings.HasPrefix(lines[i], "#EXTINF") {
			if i+1 < len(lines) && p.m3uPattern.MatchString(lines[i+1]) {
				lines[i] = ""; lines[i+1] = ""; i++
				if i+1 < len(lines) && strings.HasPrefix(lines[i+1], "#EXT-X-DISCONTINUITY") { lines[i+1] = ""; i++ }
			}
		}
	}
	result := make([]string, 0, len(lines))
	for _, l := range lines { if l != "" { result = append(result, l) } }
	return strings.Join(result, "\n")
}
