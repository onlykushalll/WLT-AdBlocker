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
	if !contains(js, "sponsor.ajay.app") {
		t.Error("missing SponsorBlock API URL")
	}
	if !contains(js, "querySelector('video')") {
		t.Error("missing video element query")
	}
	if !contains(js, "currentTime") {
		t.Error("missing skip logic")
	}
	if !contains(js, "URLSearchParams") {
		t.Error("missing video ID extraction")
	}
}

func TestNew(t *testing.T) {
	c := New()
	if c == nil { t.Fatal("nil client") }
	if c.APIURL() != "https://sponsor.ajay.app/api" {
		t.Errorf("API URL = %s", c.APIURL())
	}
}

func contains(s, sub string) bool {
	for i := 0; i <= len(s)-len(sub); i++ {
		if s[i:i+len(sub)] == sub { return true }
	}
	return false
}
