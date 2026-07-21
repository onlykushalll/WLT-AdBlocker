package cosmetic

import (
	"strings"
	"testing"
)

func TestGenerateCSS(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")
	e.AddGenericSelector("#banner")
	e.AddSpecificSelector("youtube.com", "ytd-ad-slot-renderer")

	css := e.GenerateCSS("www.youtube.com")
	if css == "" {
		t.Fatal("empty CSS")
	}
	if !strings.Contains(css, ".ad{display:none!important;}") {
		t.Error("missing .ad selector")
	}
	if !strings.Contains(css, "ytd-ad-slot-renderer{display:none!important;}") {
		t.Error("missing youtube-specific selector")
	}
}

func TestProceduralHasText(t *testing.T) {
	e := New()
	e.AddProceduralFilter("example.com", ProceduralFilter{
		Selector: "div",
		Type:     "has-text",
		Arg:      "Sponsored",
		Action:   "hide",
	})

	js := e.GenerateProceduralJS("www.example.com")
	if js == "" {
		t.Fatal("empty JS")
	}
	if !strings.Contains(js, "textContent.includes") {
		t.Error("missing has-text implementation")
	}
	if !strings.Contains(js, "Sponsored") {
		t.Error("missing 'Sponsored' argument")
	}
}

func TestLoadDefaults(t *testing.T) {
	e := New()
	e.LoadDefaults()

	count := e.SelectorCount()
	if count < 20 {
		t.Errorf("only %d selectors after LoadDefaults, expected 20+", count)
	}

	// Verify YouTube selectors loaded
	css := e.GenerateCSS("www.youtube.com")
	if !strings.Contains(css, "ytd-ad-slot-renderer") {
		t.Error("YouTube selector missing")
	}

	// Verify Spotify selectors loaded
	cssSp := e.GenerateCSS("open.spotify.com")
	if !strings.Contains(cssSp, "ad-slot") {
		t.Error("Spotify selector missing")
	}
}

func TestSuffixMatching(t *testing.T) {
	e := New()
	e.AddSpecificSelector("example.com", ".ad-banner")

	// Should match subdomain
	css := e.GenerateCSS("sub.example.com")
	if !strings.Contains(css, ".ad-banner") {
		t.Error("suffix matching failed for sub.example.com")
	}
}

func TestGenerateInjectionHTML(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")
	e.AddProceduralFilter("test.com", ProceduralFilter{
		Selector: "div",
		Type:     "remove",
		Action:   "remove",
	})

	html := e.GenerateInjectionHTML("www.test.com")
	if !strings.Contains(html, "<style>") {
		t.Error("missing <style> tag")
	}
	if !strings.Contains(html, "<script>") {
		t.Error("missing <script> tag")
	}
}
