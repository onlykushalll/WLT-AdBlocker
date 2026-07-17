package engine

import (
	"testing"
	"wlt-core/dns"
)

func TestCheckDNSBlock(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("ads.example.com")
	e.AddBlockDomain("*.tracker.com")
	e.AddAllowDomain("allow.example.com")

	// Blocked: exact match
	q := buildDNSQuery(t, "ads.example.com")
	block, resp, reason := checkDNS(e, q)
	if !block {
		t.Errorf("expected block for ads.example.com, got allow (%s)", reason)
	}
	if len(resp) == 0 {
		t.Error("expected response packet for blocked query")
	}
	// Verify response is NXDOMAIN
	m, err := dns.Parse(resp)
	if err != nil {
		t.Fatalf("parse response: %v", err)
	}
	if m.Header.RCODE() != dns.RCODENxDomain {
		t.Errorf("response RCODE = %d, want NXDOMAIN", m.Header.RCODE())
	}
}

func TestCheckDNSBlockWildcard(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("*.tracker.com")

	q := buildDNSQuery(t, "sub.tracker.com")
	block, _, _ := checkDNS(e, q)
	if !block {
		t.Error("expected block for sub.tracker.com (wildcard)")
	}
}

func TestCheckDNSAllow(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("ads.example.com")
	e.AddAllowDomain("safe.example.com")

	q := buildDNSQuery(t, "safe.example.com")
	block, _, _ := checkDNS(e, q)
	if block {
		t.Error("expected allow for safe.example.com (allowlist)")
	}
}

func TestCheckDNSAllowlistOverridesBlock(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("both.example.com")
	e.AddAllowDomain("both.example.com")

	q := buildDNSQuery(t, "both.example.com")
	block, _, _ := checkDNS(e, q)
	if block {
		t.Error("allowlist should override blocklist")
	}
}

func TestCheckDNSDenylistOverridesAllow(t *testing.T) {
	e := New(DefaultConfig())
	e.AddAllowDomain("both.example.com")
	e.AddDenyDomain("both.example.com")

	q := buildDNSQuery(t, "both.example.com")
	block, _, _ := checkDNS(e, q)
	if !block {
		t.Error("denylist should override allowlist")
	}
}

func TestCheckDNSGameSDK(t *testing.T) {
	e := New(DefaultConfig())
	// Don't add to blocklist — game SDK detection should still catch it
	q := buildDNSQuery(t, "pagead2.googlesyndication.com")
	block, _, reason := checkDNS(e, q)
	if !block {
		t.Errorf("expected block for AdMob domain, got allow (%s)", reason)
	}
}

func TestCheckDNSNoMatch(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("ads.example.com")

	q := buildDNSQuery(t, "clean.example.org")
	block, _, _ := checkDNS(e, q)
	if block {
		t.Error("expected allow for clean domain")
	}
}

func TestStats(t *testing.T) {
	e := New(DefaultConfig())
	e.AddBlockDomain("blocked.com")

	// 3 queries: 2 blocked, 1 allowed
	checkDNS(e, buildDNSQuery(t, "blocked.com"))
	checkDNS(e, buildDNSQuery(t, "blocked.com"))
	checkDNS(e, buildDNSQuery(t, "allowed.com"))

	s := e.GetStats()
	if s.TotalQueries != 3 {
		t.Errorf("TotalQueries = %d, want 3", s.TotalQueries)
	}
	if s.TotalBlocked != 2 {
		t.Errorf("TotalBlocked = %d, want 2", s.TotalBlocked)
	}
	if s.TotalAllowed != 1 {
		t.Errorf("TotalAllowed = %d, want 1", s.TotalAllowed)
	}
}

func TestLayerToggle(t *testing.T) {
	e := New(DefaultConfig())
	if !e.IsLayerEnabled(1) { // DNS
		t.Error("DNS layer should be enabled by default")
	}
	if e.IsLayerEnabled(2) { // SNI
		t.Error("SNI layer should be disabled by default")
	}
	e.EnableLayer(2, true)
	if !e.IsLayerEnabled(2) {
		t.Error("SNI layer should be enabled after toggle")
	}
}

// checkDNS is a test helper wrapping e.CheckDNS.
func checkDNS(e *Engine, query []byte) (bool, []byte, string) {
	d, resp := e.CheckDNS(query, nil)
	return d.Block, resp, d.Reason
}

// buildDNSQuery builds a DNS query packet for testing.
func buildDNSQuery(t *testing.T, domain string) []byte {
	t.Helper()
	var buf []byte
	buf = appendU16(buf, 0x0001) // ID
	buf = appendU16(buf, 0x0100) // RD=1
	buf = appendU16(buf, 1)      // QDCOUNT
	buf = appendU16(buf, 0)
	buf = appendU16(buf, 0)
	buf = appendU16(buf, 0)
	buf = append(buf, encodeName(domain)...)
	buf = appendU16(buf, 1) // type A
	buf = appendU16(buf, 1) // class IN
	return buf
}

func encodeName(name string) []byte {
	var out []byte
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			label := name[start:i]
			if len(label) > 0 {
				out = append(out, byte(len(label)))
				out = append(out, []byte(label)...)
			}
			start = i + 1
		}
	}
	out = append(out, 0)
	return out
}

func appendU16(buf []byte, v uint16) []byte {
	return append(buf, byte(v>>8), byte(v))
}
