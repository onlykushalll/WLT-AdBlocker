package scriptlets

import (
	"strings"
	"testing"
)

func TestGet(t *testing.T) {
	e := New()
	e.LoadDefaults()
	if _, ok := e.Get("adsbygoogle"); !ok {
		t.Error("adsbygoogle missing")
	}
	if _, ok := e.Get("yt-player-intercept"); !ok {
		t.Error("yt-player-intercept missing")
	}
	if _, ok := e.Get("nonexistent"); ok {
		t.Error("nonexistent should not exist")
	}
	// Case-insensitive.
	if _, ok := e.Get("ADSbyGoogle"); !ok {
		t.Error("case-insensitive lookup failed")
	}
}

func TestInject(t *testing.T) {
	e := New()
	e.LoadDefaults()
	html := []byte(`<!DOCTYPE html><html><head><title>YT</title></head><body><video></video></body></html>`)
	out := e.Inject(html, "www.youtube.com")
	if !strings.Contains(string(out), "<script>") {
		t.Error("script tag not injected")
	}
	if !strings.Contains(string(out), "ytInitialPlayerResponse") {
		t.Error("youtube scriptlet not present in injected script")
	}
	// Verify <script> comes after <head>.
	headIdx := strings.Index(string(out), "<head>")
	scriptIdx := strings.Index(string(out), "<script>")
	if headIdx < 0 || scriptIdx < 0 || scriptIdx < headIdx {
		t.Errorf("script tag not after head tag: head=%d script=%d", headIdx, scriptIdx)
	}
}

func TestForDomain(t *testing.T) {
	e := New()
	e.LoadDefaults()
	// YouTube host should get all 5 YouTube scriptlets.
	bodies := e.ForDomain("youtube.com")
	if len(bodies) < 5 {
		t.Errorf("youtube.com should get >=5 scriptlets, got %d", len(bodies))
	}
	// Non-matching host should get 0.
	bodies2 := e.ForDomain("example.com")
	if len(bodies2) != 0 {
		t.Errorf("example.com should get 0 scriptlets, got %d", len(bodies2))
	}
	// youtu.be short-link should also match.
	bodies3 := e.ForDomain("youtu.be")
	if len(bodies3) < 5 {
		t.Errorf("youtu.be should get >=5 scriptlets, got %d", len(bodies3))
	}
}

func TestNoMutation(t *testing.T) {
	e := New()
	e.LoadDefaults()
	html := []byte(`<!DOCTYPE html><html><head></head><body></body></html>`)
	original := make([]byte, len(html))
	copy(original, html)
	_ = e.Inject(html, "youtube.com")
	if string(html) != string(original) {
		t.Error("Inject mutated input")
	}
}

func TestRegister(t *testing.T) {
	e := New()
	e.Register("custom", `(function(){ /* hi */ })();`)
	js, ok := e.Get("custom")
	if !ok {
		t.Fatal("custom scriptlet not found")
	}
	if !strings.Contains(js, "hi") {
		t.Errorf("unexpected js: %s", js)
	}
}

// Count scriptlets registered by LoadDefaults.
func TestLoadDefaultCount(t *testing.T) {
	e := New()
	e.LoadDefaults()
	e.mu.RLock()
	count := len(e.scripts)
	e.mu.RUnlock()
	if count < 49 {
		t.Errorf("expected >=49 scriptlets, got %d", count)
	}
}
