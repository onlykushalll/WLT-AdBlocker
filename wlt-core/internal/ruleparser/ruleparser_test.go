package ruleparser

import "testing"

func TestParseBlock(t *testing.T) {
	r, err := Parse("||example.com^")
	if err != nil {
		t.Fatal(err)
	}
	if r == nil || r.Type != TypeBlock || r.Domain != "example.com" {
		t.Errorf("got %+v", r)
	}
}

func TestParseAllow(t *testing.T) {
	r, err := Parse("@@||example.com^")
	if err != nil {
		t.Fatal(err)
	}
	if r == nil || !r.IsAllow || r.Domain != "example.com" {
		t.Errorf("got %+v", r)
	}
}

func TestParseImportant(t *testing.T) {
	r, _ := Parse("||ads.com^$important")
	if r == nil || !r.IsImportant {
		t.Errorf("expected important: %+v", r)
	}
}

func TestParseBadfilter(t *testing.T) {
	r, _ := Parse("||ads.com^$badfilter")
	if r == nil || !r.IsBadfilter {
		t.Errorf("expected badfilter: %+v", r)
	}
}

func TestParseDomainOption(t *testing.T) {
	r, _ := Parse("||ads.com^$domain=a.com|b.com|~c.com")
	if r == nil {
		t.Fatal("nil rule")
	}
	if len(r.SourceDomains) != 3 {
		t.Fatalf("SourceDomains=%v", r.SourceDomains)
	}
	if r.SourceDomains[0] != "a.com" || r.SourceDomains[1] != "b.com" || r.SourceDomains[2] != "~c.com" {
		t.Errorf("SourceDomains=%v", r.SourceDomains)
	}
}

func TestParseThirdParty(t *testing.T) {
	r, _ := Parse("||ads.com^$third-party")
	if r == nil || !r.ThirdParty {
		t.Errorf("expected third-party: %+v", r)
	}
}

func TestParseHosts(t *testing.T) {
	r, _ := Parse("0.0.0.0 adserver.com")
	if r == nil || r.Type != TypeHosts || r.Domain != "adserver.com" || r.HostsIP != "0.0.0.0" {
		t.Errorf("got %+v", r)
	}
}

func TestParseBare(t *testing.T) {
	r, _ := Parse("bare.example.com")
	if r == nil || r.Type != TypeBare || r.Domain != "bare.example.com" {
		t.Errorf("got %+v", r)
	}
}

func TestParseComment(t *testing.T) {
	for _, c := range []string{"! this is a comment", "[Adblock Plus 2.0]", "# plain hash comment", ""} {
		r, err := Parse(c)
		if err != nil {
			t.Errorf("comment %q returned error: %v", c, err)
		}
		if r != nil {
			t.Errorf("comment %q should return nil, got %+v", c, r)
		}
	}
}

func TestParseCosmetic(t *testing.T) {
	r, _ := Parse("example.com##.ad-banner")
	if r == nil || r.Type != TypeCosmetic || r.Domain != "example.com" || r.CosmeticSelector != ".ad-banner" {
		t.Errorf("got %+v", r)
	}
	// Global cosmetic (no host prefix).
	r2, _ := Parse("##.ads")
	if r2 == nil || r2.Domain != "" || r2.CosmeticSelector != ".ads" {
		t.Errorf("got %+v", r2)
	}
}

func TestParseScriptlet(t *testing.T) {
	r, _ := Parse("youtube.com##+js(yt-speed-up-ads)")
	if r == nil || r.Type != TypeScriptlet || r.ScriptletName != "yt-speed-up-ads" {
		t.Errorf("got %+v", r)
	}
	r2, _ := Parse(`example.com##+js(remove-node-text, .ad, /Sponsored/)`)
	if r2 == nil || r2.ScriptletName != "remove-node-text" {
		t.Errorf("got %+v", r2)
	}
}

func TestParseCosmeticException(t *testing.T) {
	r, _ := Parse("example.com#@#.ads")
	if r == nil || !r.IsAllow || r.CosmeticSelector != ".ads" {
		t.Errorf("got %+v", r)
	}
}

func TestParseEmptyDomain(t *testing.T) {
	r, err := Parse("||^")
	if err == nil {
		t.Errorf("expected error for empty domain, got %+v", r)
	}
}

func TestParseOptionsCombo(t *testing.T) {
	r, _ := Parse("||ads.com^$important,third-party,domain=foo.com|bar.com")
	if r == nil {
		t.Fatal("nil rule")
	}
	if !r.IsImportant || !r.ThirdParty || len(r.SourceDomains) != 2 {
		t.Errorf("missing options: %+v", r)
	}
}
