package m3uprune

import (
	"bytes"
	"strings"
	"testing"
)

const adPlaylist = `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:10
#EXT-X-MEDIA-SEQUENCE:1
#EXTINF:10.000,
seg-1.ts
#EXT-X-SCTE35-OUT
#EXTINF:15.000,
ad-1.ts
#EXTINF:15.000,
ad-2.ts
#EXT-X-SCTE35-IN
#EXTINF:10.000,
seg-2.ts
#EXT-X-ENDLIST
`

func TestPruneSpliceOut(t *testing.T) {
	out := Prune([]byte(adPlaylist))
	if bytes.Equal(out, []byte(adPlaylist)) {
		t.Fatal("playlist unchanged")
	}
	s := string(out)
	if strings.Contains(s, "ad-1.ts") || strings.Contains(s, "ad-2.ts") {
		t.Errorf("ad segments not removed:\n%s", s)
	}
	if strings.Contains(s, "SCTE35-OUT") || strings.Contains(s, "SCTE35-IN") {
		t.Errorf("SCTE35 markers not removed:\n%s", s)
	}
	if !strings.Contains(s, "seg-1.ts") || !strings.Contains(s, "seg-2.ts") {
		t.Errorf("content segments removed:\n%s", s)
	}
}

func TestPruneExtinf(t *testing.T) {
	// Playlist with heuristic ad detection (no SCTE-35 markers).
	p := `#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10.000,
seg-1.ts
#EXTINF:15.000,
https://cdn.example.com/ads/ad-1.mp4
#EXTINF:10.000,
seg-2.ts
`
	out := Prune([]byte(p))
	if strings.Contains(string(out), "/ads/") {
		t.Errorf("heuristic ad not removed:\n%s", out)
	}
	if !strings.Contains(string(out), "seg-1.ts") || !strings.Contains(string(out), "seg-2.ts") {
		t.Errorf("content segments removed:\n%s", out)
	}
}

func TestPruneNoAds(t *testing.T) {
	p := `#EXTM3U
#EXTINF:10.000,
seg-1.ts
#EXTINF:10.000,
seg-2.ts
#EXT-X-ENDLIST
`
	out := Prune([]byte(p))
	if !bytes.Equal(out, []byte(p)) {
		t.Errorf("no-ads playlist should be unchanged:\n%s", out)
	}
}

func TestPruneAllAds(t *testing.T) {
	p := `#EXTM3U
#EXT-X-SCTE35-OUT
#EXTINF:15.000,
ad-1.ts
#EXTINF:15.000,
ad-2.ts
#EXT-X-SCTE35-IN
#EXT-X-ENDLIST
`
	out := Prune([]byte(p))
	if strings.Contains(string(out), "ad-1.ts") || strings.Contains(string(out), "ad-2.ts") {
		t.Errorf("ad segments not removed:\n%s", out)
	}
	if !strings.Contains(string(out), "#EXTM3U") {
		t.Errorf("header lost:\n%s", out)
	}
	if !strings.Contains(string(out), "#EXT-X-ENDLIST") {
		t.Errorf("endlist lost:\n%s", out)
	}
}

func TestPruneEmpty(t *testing.T) {
	out := Prune([]byte{})
	if len(out) != 0 {
		t.Errorf("empty input should give empty output, got %q", out)
	}
}

func TestPrunePreservesStructure(t *testing.T) {
	// Playlist with EXT-X-DISCONTINUITY around the ad break; the pruner
	// should collapse the now-adjacent discontinuity lines.
	p := `#EXTM3U
#EXTINF:10.000,
seg-1.ts
#EXT-X-DISCONTINUITY
#EXT-X-SCTE35-OUT
#EXTINF:15.000,
ad-1.ts
#EXT-X-SCTE35-IN
#EXT-X-DISCONTINUITY
#EXTINF:10.000,
seg-2.ts
#EXT-X-ENDLIST
`
	out := Prune([]byte(p))
	s := string(out)
	// The two discontinuity lines should NOT both remain; at most one.
	if strings.Count(s, "DISCONTINUITY") > 1 {
		t.Errorf("adjacent discontinuities not collapsed:\n%s", s)
	}
	if strings.Contains(s, "ad-1.ts") {
		t.Errorf("ad segment not removed:\n%s", s)
	}
	if !strings.Contains(s, "seg-1.ts") || !strings.Contains(s, "seg-2.ts") {
		t.Errorf("content segments removed:\n%s", s)
	}
}
