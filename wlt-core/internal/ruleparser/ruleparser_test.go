package ruleparser

import "testing"

func TestParseBlockDomain(t *testing.T) {
	r := Parse("||example.com^")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if r.Type != RuleBlock {
		t.Error("should be block")
	}
	if r.Domain != "example.com" {
		t.Errorf("domain = %s, want example.com", r.Domain)
	}
}

func TestParseAllowDomain(t *testing.T) {
	r := Parse("@@||safe.example.com^")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if r.Type != RuleAllow {
		t.Error("should be allow")
	}
	if r.Domain != "safe.example.com" {
		t.Errorf("domain = %s", r.Domain)
	}
}

func TestParseImportant(t *testing.T) {
	r := Parse("||ads.com^$important")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if !r.Important {
		t.Error("important should be true")
	}
}

func TestParseBadFilter(t *testing.T) {
	r := Parse("||ads.com^$badfilter")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if r.Type != RuleBadFilter {
		t.Error("should be badfilter")
	}
}

func TestParseThirdParty(t *testing.T) {
	r := Parse("||ads.com^$third-party")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if !r.ThirdParty {
		t.Error("third-party should be true")
	}
}

func TestParseDomainModifier(t *testing.T) {
	r := Parse("||ads.com^$domain=foo.com|bar.com")
	if !r.Valid {
		t.Fatal("should be valid")
	}
	if len(r.Domains) != 2 {
		t.Fatalf("domains = %v, want 2", r.Domains)
	}
	if r.Domains[0] != "foo.com" || r.Domains[1] != "bar.com" {
		t.Errorf("domains = %v", r.Domains)
	}
}

func TestParseHostsFormat(t *testing.T) {
	tests := []string{
		"0.0.0.0 ads.example.com",
		"127.0.0.1 ads.example.com",
	}
	for _, line := range tests {
		r := Parse(line)
		if !r.Valid {
			t.Errorf("hosts format not parsed: %s", line)
		}
		if r.Domain != "ads.example.com" {
			t.Errorf("domain = %s, want ads.example.com", r.Domain)
		}
	}
}

func TestParseBareDomain(t *testing.T) {
	r := Parse("ads.example.com")
	if !r.Valid {
		t.Fatal("bare domain should be valid")
	}
	if r.Domain != "ads.example.com" {
		t.Errorf("domain = %s", r.Domain)
	}
}

func TestParseComment(t *testing.T) {
	tests := []string{
		"! this is a comment",
		"# this is a comment",
		"",
		"   ",
	}
	for _, line := range tests {
		r := Parse(line)
		if r.Valid {
			t.Errorf("comment should not be valid: %s", line)
		}
	}
}

func TestParseCosmeticRejected(t *testing.T) {
	r := Parse("example.com##.ad-banner")
	if r.Valid {
		t.Error("cosmetic rule should not be valid")
	}
}

func TestParseScriptletRejected(t *testing.T) {
	r := Parse("example.com##+js(no-fetch-if, /ads/)")
	if r.Valid {
		t.Error("scriptlet rule should not be valid")
	}
}

func TestParseMulti(t *testing.T) {
	text := `! comment
||ads.com^
||tracker.com^$important
@@||safe.com^
0.0.0.0 hosts.example.com
example.com##.ad
bare-domain.com`

	rules := ParseMulti(text)
	// Should get 5 valid rules (comment and cosmetic rejected)
	if len(rules) != 5 {
		t.Errorf("got %d rules, want 5", len(rules))
	}
}

func TestIsBlock(t *testing.T) {
	r := Parse("||ads.com^")
	if !r.IsBlock() {
		t.Error("should be block")
	}
	if r.IsAllow() {
		t.Error("should not be allow")
	}
}

func TestIsAllow(t *testing.T) {
	r := Parse("@@||safe.com^")
	if !r.IsAllow() {
		t.Error("should be allow")
	}
	if r.IsBlock() {
		t.Error("should not be block")
	}
}
