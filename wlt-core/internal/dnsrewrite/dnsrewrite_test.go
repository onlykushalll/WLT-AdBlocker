package dnsrewrite

import (
	"net"
	"testing"
)

func TestLookup(t *testing.T) {
	e := New()
	e.AddBlock("example.com")

	rule, found := e.Lookup("example.com")
	if !found {
		t.Fatal("rule not found for exact match")
	}
	if rule.Type != RewriteNXDomain {
		t.Errorf("type = %v, want NXDomain", rule.Type)
	}
}

func TestSuffixMatch(t *testing.T) {
	e := New()
	e.AddNullIP("doubleclick.net")

	// Subdomain should match
	rule, found := e.Lookup("ads.doubleclick.net")
	if !found {
		t.Fatal("suffix match failed")
	}
	if rule.Type != RewriteNullIP {
		t.Errorf("type = %v, want NullIP", rule.Type)
	}
}

func TestCustomIP(t *testing.T) {
	e := New()
	ip4 := net.ParseIP("192.168.1.1")
	ip6 := net.ParseIP("::1")
	e.AddCustomIP("my.local", ip4, ip6)

	rule, found := e.Lookup("my.local")
	if !found {
		t.Fatal("custom IP rule not found")
	}
	if rule.Type != RewriteCustomIP {
		t.Errorf("type = %v, want CustomIP", rule.Type)
	}
	if !rule.IPv4.Equal(ip4) {
		t.Errorf("IPv4 = %v, want %v", rule.IPv4, ip4)
	}
}

func TestCNAME(t *testing.T) {
	e := New()
	e.AddRedirect("tracker.com", "safe.wlt.local")

	rule, found := e.Lookup("tracker.com")
	if !found {
		t.Fatal("CNAME rule not found")
	}
	if rule.Type != RewriteCNAME {
		t.Errorf("type = %v, want CNAME", rule.Type)
	}
	if rule.CNAMETarget != "safe.wlt.local" {
		t.Errorf("target = %s, want safe.wlt.local", rule.CNAMETarget)
	}
}

func TestLoadDefaults(t *testing.T) {
	e := New()
	e.LoadDefaults()

	if e.RuleCount() < 20 {
		t.Errorf("only %d rules after LoadDefaults, expected 20+", e.RuleCount())
	}

	// Verify ad domain is sinkholed
	rule, found := e.Lookup("ad.doubleclick.net")
	if !found {
		t.Fatal("doubleclick.net not in defaults")
	}
	if rule.Type != RewriteNullIP {
		t.Errorf("doubleclick type = %v, want NullIP", rule.Type)
	}

	// Verify DoH domain is REFUSED
	rule, found = e.Lookup("dns.google")
	if !found {
		t.Fatal("dns.google not in defaults")
	}
	if rule.Type != RewriteRefused {
		t.Errorf("dns.google type = %v, want Refused", rule.Type)
	}
}

func TestNoMatch(t *testing.T) {
	e := New()
	e.AddBlock("example.com")

	_, found := e.Lookup("different.com")
	if found {
		t.Error("should not match unrelated domain")
	}
}

func TestClear(t *testing.T) {
	e := New()
	e.AddBlock("test.com")
	if e.RuleCount() != 1 {
		t.Errorf("count = %d, want 1", e.RuleCount())
	}
	e.Clear()
	if e.RuleCount() != 0 {
		t.Errorf("count after clear = %d, want 0", e.RuleCount())
	}
}
