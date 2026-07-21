package sponsorblock

import (
	"testing"
)

func TestGenerateSkipJS(t *testing.T) {
	c := New()
	js := c.GenerateSkipJS()
	if js == "" {
		t.Fatal("empty skip JS")
	}
	// Should contain the API URL
	if !contains(js, "sponsor.ajay.app") {
		t.Error("missing SponsorBlock API URL")
	}
	// Should contain video element query
	if !contains(js, "querySelector('video')") {
		t.Error("missing video element query")
	}
	// Should contain currentTime skip logic
	if !contains(js, "v.currentTime = seg.end") {
		t.Error("missing skip logic")
	}
	// Should extract video ID from URL
	if !contains(js, "URLSearchParams") {
		t.Error("missing video ID extraction")
	}
	// Should handle embed URLs
	if !contains(js, "/embed/") {
		t.Error("missing embed URL handling")
	}
}

func TestNew(t *testing.T) {
	c := New()
	if c == nil {
		t.Fatal("nil client")
	}
	if c.APIURL() != "https://sponsor.ajay.app/api" {
		t.Errorf("API URL = %s, want https://sponsor.ajay.app/api", c.APIURL())
	}
}

func TestSegmentParsing(t *testing.T) {
	// Test that the Segment struct is correctly structured
	s := Segment{
		Start:    10.5,
		End:      30.2,
		Category: "sponsor",
		UUID:     "abc123",
	}
	if s.Start != 10.5 { t.Error("Start mismatch") }
	if s.End != 30.2 { t.Error("End mismatch") }
	if s.Category != "sponsor" { t.Error("Category mismatch") }
}

func contains(s, sub string) bool {
	return len(s) >= len(sub) && (s == sub || len(s) > 0 && containsStr(s, sub))
}

func containsStr(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
