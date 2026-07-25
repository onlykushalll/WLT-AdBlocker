package cosmetic

import (
	"strings"
	"testing"
)

func TestGenerateCSS(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")
	e.AddGenericSelector("#ad-banner")
	e.AddGenericSelector(".promo")
	css := e.GenerateCSS("any.example.com")
	if !strings.Contains(css, ".ad") {
		t.Errorf("missing .ad: %s", css)
	}
	if !strings.Contains(css, "#ad-banner") {
		t.Errorf("missing #ad-banner: %s", css)
	}
	if !strings.Contains(css, "display: none !important") {
		t.Errorf("missing display:none: %s", css)
	}
}

func TestProceduralHasText(t *testing.T) {
	js := CompileProcedural(`div.foo:has-text(/Sponsored/)`)
	if !strings.Contains(js, "RegExp") {
		t.Errorf("has-text regex not compiled: %s", js)
	}
	if !strings.Contains(js, "div.foo") {
		t.Errorf("css selector missing: %s", js)
	}
	js2 := CompileProcedural(`span.bar:has-text("Sponsored")`)
	if !strings.Contains(js2, "indexOf") {
		t.Errorf("has-text string not compiled: %s", js2)
	}
}

func TestLoadDefaults(t *testing.T) {
	e := New()
	e.LoadDefaults()
	css := e.GenerateCSS("www.youtube.com")
	// YouTube-specific
	if !strings.Contains(css, "ytd-ad-slot-renderer") {
		t.Errorf("missing youtube selector: %s", css)
	}
	// Generic
	if !strings.Contains(css, ".advert") {
		t.Errorf("missing generic selector: %s", css)
	}
	// Spotify selectors should NOT appear on YouTube.
	if strings.Contains(css, "ad-leaderboard-container") {
		t.Errorf("spotify selector leaked: %s", css)
	}
	// Verify Spotify host.
	cssSpotify := e.GenerateCSS("open.spotify.com")
	if !strings.Contains(cssSpotify, "ad-leaderboard-container") {
		t.Errorf("missing spotify selector: %s", cssSpotify)
	}
}

func TestSuffixMatching(t *testing.T) {
	e := New()
	e.AddSpecificSelector("youtube.com", ".yt-ad")
	// youtube.com itself matches.
	if !strings.Contains(e.GenerateCSS("youtube.com"), ".yt-ad") {
		t.Error("youtube.com should match suffix youtube.com")
	}
	// www.youtube.com matches.
	if !strings.Contains(e.GenerateCSS("www.youtube.com"), ".yt-ad") {
		t.Error("www.youtube.com should match suffix youtube.com")
	}
	// notyoutube.com does NOT match.
	if strings.Contains(e.GenerateCSS("notyoutube.com"), ".yt-ad") {
		t.Error("notyoutube.com should NOT match suffix youtube.com")
	}
}

func TestGenerateInjectionHTML(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")
	e.AddProceduralFilter("example.com", `div.sponsored:has-text(/Sponsored/)`)
	html := e.GenerateInjectionHTML("example.com")
	if !strings.Contains(html, "<style>") || !strings.Contains(html, "</style>") {
		t.Errorf("missing style wrapper: %s", html)
	}
	if !strings.Contains(html, "<script>") || !strings.Contains(html, "</script>") {
		t.Errorf("missing script wrapper: %s", html)
	}
	// Host without procedural filter should have only style.
	html2 := e.GenerateInjectionHTML("other.com")
	if strings.Contains(html2, "<script>") {
		t.Errorf("unexpected script for other.com: %s", html2)
	}
}

func TestExceptions(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")
	e.AddException("example.com", ".ad")
	css := e.GenerateCSS("example.com")
	if strings.Contains(css, ".ad") {
		t.Errorf("exception not honored: %s", css)
	}
	// On other hosts the .ad selector should still apply.
	css2 := e.GenerateCSS("other.com")
	if !strings.Contains(css2, ".ad") {
		t.Errorf("exception wrongly applied to other.com: %s", css2)
	}
}

func TestAutoClassification(t *testing.T) {
	e := New()
	e.AddGenericSelector(".ad")           // low simple
	e.AddGenericSelector("#ads")          // low simple
	e.AddGenericSelector("[data-ad]")     // high simple
	e.AddGenericSelector(`[id*="ad"]`)    // high simple (no = or space inside? actually has =, so complex)
	e.AddGenericSelector(".ad-container") // low simple
	e.AddGenericSelector(`div.foo`)       // complex

	if !e.lowGenericSimple[".ad"] {
		t.Error(".ad not classified as low simple")
	}
	if !e.lowGenericSimple["#ads"] {
		t.Error("#ads not classified as low simple")
	}
	if !e.lowGenericSimple[".ad-container"] {
		t.Error(".ad-container not classified as low simple")
	}
	if !e.highGenericSimple["[data-ad]"] {
		t.Error("[data-ad] not classified as high simple")
	}
	if !e.highGenericComplex[`div.foo`] {
		t.Error("div.foo not classified as complex")
	}
}
