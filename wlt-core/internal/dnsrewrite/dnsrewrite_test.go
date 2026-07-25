package dnsrewrite

import (
	"net/netip"
	"testing"
)

func TestSuffixMatch(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "example.com", Type: NullIP})
	if r := e.Rewrite("example.com"); r == nil || r.Type != NullIP {
		t.Errorf("exact match failed: %+v", r)
	}
	if r := e.Rewrite("www.example.com"); r == nil || r.Type != NullIP {
		t.Errorf("subdomain match failed: %+v", r)
	}
	if r := e.Rewrite("notexample.com"); r != nil {
		t.Errorf("notexample.com should not match: %+v", r)
	}
}

func TestCustomIP(t *testing.T) {
	e := New()
	ip := netip.MustParseAddr("192.168.1.1")
	e.AddRule(Rule{Domain: "test.com", Type: CustomIP, CustomIP: ip})
	r := e.Rewrite("test.com")
	if r == nil || r.Type != CustomIP {
		t.Fatalf("CustomIP not matched: %+v", r)
	}
	if r.CustomIP.String() != "192.168.1.1" {
		t.Errorf("CustomIP=%s want 192.168.1.1", r.CustomIP)
	}
}

func TestCNAME(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "alias.com", Type: CNAME, CNAMETarget: "real.com"})
	r := e.Rewrite("alias.com")
	if r == nil || r.Type != CNAME || r.CNAMETarget != "real.com" {
		t.Errorf("CNAME not matched: %+v", r)
	}
}

func TestDefaults(t *testing.T) {
	e := New()
	e.LoadDefaults()
	// Total 26 rules (21 ad + 5 DoH).
	if c := e.Count(); c < 26 {
		t.Errorf("expected >=26 rules, got %d", c)
	}
	// An ad domain should be NullIP.
	if r := e.Rewrite("ads.doubleclick.net"); r == nil || r.Type != NullIP {
		t.Errorf("doubleclick.net not NullIP: %+v", r)
	}
	// A DoH domain should be REFUSED.
	if r := e.Rewrite("dns.google"); r == nil || r.Type != REFUSED {
		t.Errorf("dns.google not REFUSED: %+v", r)
	}
}

func TestNoMatch(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "example.com", Type: NullIP})
	if r := e.Rewrite("other.com"); r != nil {
		t.Errorf("expected nil, got %+v", r)
	}
	if r := e.Rewrite(""); r != nil {
		t.Errorf("expected nil for empty, got %+v", r)
	}
}

func TestClear(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "example.com", Type: NullIP})
	e.Clear()
	if e.Count() != 0 {
		t.Errorf("Count=%d want 0", e.Count())
	}
	if r := e.Rewrite("example.com"); r != nil {
		t.Errorf("Rewrite after Clear should be nil: %+v", r)
	}
}

func TestLongestSuffixWins(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "example.com", Type: NullIP})
	e.AddRule(Rule{Domain: "ads.example.com", Type: NXDOMAIN})
	// "ads.example.com" matches both rules; the longest suffix wins.
	r := e.Rewrite("sub.ads.example.com")
	if r == nil || r.Type != NXDOMAIN {
		t.Errorf("expected NXDOMAIN (longest match), got %+v", r)
	}
	// "www.example.com" matches only "example.com" -> NullIP.
	r2 := e.Rewrite("www.example.com")
	if r2 == nil || r2.Type != NullIP {
		t.Errorf("expected NullIP, got %+v", r2)
	}
}

func TestRemoveRule(t *testing.T) {
	e := New()
	e.AddRule(Rule{Domain: "example.com", Type: NullIP})
	e.RemoveRule("example.com")
	if r := e.Rewrite("example.com"); r != nil {
		t.Errorf("rule not removed: %+v", r)
	}
}
