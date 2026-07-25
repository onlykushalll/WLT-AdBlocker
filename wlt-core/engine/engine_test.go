package engine

import (
	"testing"
)

func TestCheckDNSBlock(t *testing.T) {
	e := New()
	e.AddBlockDomain("ads.example.com")
	e.AddBlockDomain("tracker.evil.net")

	res := e.CheckDNS("ads.example.com")
	if res.Decision == DecisionAllow {
		t.Errorf("CheckDNS(ads.example.com) allowed, want blocked")
	}
	if res.Reason == "" {
		t.Errorf("Reason should not be empty on block")
	}
}

func TestCheckDNSBlockWildcard(t *testing.T) {
	e := New()
	e.AddBlockDomain("*.evil.net")
	// Wildcard matches strict subdomain.
	if res := e.CheckDNS("sub.evil.net"); res.Decision == DecisionAllow {
		t.Errorf("CheckDNS(sub.evil.net) allowed, want blocked (wildcard)")
	}
	// Wildcard does NOT match the parent itself.
	if res := e.CheckDNS("evil.net"); res.Decision != DecisionAllow {
		t.Errorf("CheckDNS(evil.net) = %v, want Allow (wildcard does not match parent)", res.Decision)
	}
}

func TestCheckDNSAllow(t *testing.T) {
	e := New()
	e.AddAllowDomain("banking.example.com")
	if res := e.CheckDNS("banking.example.com"); res.Decision != DecisionAllow {
		t.Errorf("CheckDNS(banking.example.com) = %v, want Allow", res.Decision)
	}
	if res := e.CheckDNS("sub.banking.example.com"); res.Decision != DecisionAllow {
		t.Errorf("CheckDNS(sub.banking.example.com) = %v, want Allow (suffix)", res.Decision)
	}
}

func TestCheckDNSAllowlistOverridesBlock(t *testing.T) {
	e := New()
	e.AddBlockDomain("example.com")
	e.AddAllowDomain("allow.example.com")
	// allow.example.com is a subdomain of example.com — allowlist wins.
	if res := e.CheckDNS("allow.example.com"); res.Decision != DecisionAllow {
		t.Errorf("CheckDNS(allow.example.com) = %v, want Allow (allowlist overrides blocklist)", res.Decision)
	}
	// Other subdomains still blocked.
	if res := e.CheckDNS("other.example.com"); res.Decision == DecisionAllow {
		t.Errorf("CheckDNS(other.example.com) allowed, want blocked")
	}
}

func TestCheckDNSDenylistOverridesAllow(t *testing.T) {
	e := New()
	e.AddAllowDomain("example.com")
	e.AddDenyDomain("evil.example.com")
	// Denylist wins over allowlist.
	if res := e.CheckDNS("evil.example.com"); res.Decision == DecisionAllow {
		t.Errorf("CheckDNS(evil.example.com) allowed, want blocked (denylist overrides allowlist)")
	}
}

func TestCheckDNSGameSDK(t *testing.T) {
	e := New()
	res := e.CheckDNS("pagead2.googlesyndication.com")
	if res.Decision == DecisionAllow {
		t.Errorf("CheckDNS(AdMob domain) allowed, want blocked by Game SDK")
	}
	if res.SDK != "AdMob" {
		t.Errorf("CheckDNS(AdMob domain) SDK = %q, want AdMob", res.SDK)
	}
}

func TestCheckDNSNoMatch(t *testing.T) {
	e := New()
	res := e.CheckDNS("totally-unknown-domain.example")
	if res.Decision != DecisionAllow {
		t.Errorf("CheckDNS(unknown) = %v, want Allow", res.Decision)
	}
}

func TestStats(t *testing.T) {
	e := New()
	e.AddBlockDomain("blocked.example.com")
	e.AddAllowDomain("allowed.example.com")
	for i := 0; i < 10; i++ {
		e.CheckDNS("blocked.example.com")
	}
	for i := 0; i < 5; i++ {
		e.CheckDNS("allowed.example.com")
	}
	for i := 0; i < 3; i++ {
		e.CheckDNS("unknown.example")
	}
	snap := e.Stats()
	if snap.TotalQueries != 18 {
		t.Errorf("TotalQueries = %d, want 18", snap.TotalQueries)
	}
	if snap.TotalBlocked != 10 {
		t.Errorf("TotalBlocked = %d, want 10", snap.TotalBlocked)
	}
	if snap.TotalAllowed != 8 {
		t.Errorf("TotalAllowed = %d, want 8", snap.TotalAllowed)
	}
	// Layer counter for DNS should equal total queries.
	if snap.Layer[LayerDNS] != 18 {
		t.Errorf("Layer[DNS] = %d, want 18", snap.Layer[LayerDNS])
	}
	// Top blocked should include our blocked domain.
	if snap.TopBlocked["blocked.example.com"] != 10 {
		t.Errorf("TopBlocked[blocked.example.com] = %d, want 10", snap.TopBlocked["blocked.example.com"])
	}
}

func TestLayerToggle(t *testing.T) {
	e := New()
	e.AddBlockDomain("ads.example.com")
	// Disable DNS layer — should allow even blocked domains.
	e.SetLayerEnabled(LayerDNS, false)
	if res := e.CheckDNS("ads.example.com"); res.Decision != DecisionAllow {
		t.Errorf("CheckDNS with DNS layer disabled = %v, want Allow", res.Decision)
	}
	// Re-enable — should block again.
	e.SetLayerEnabled(LayerDNS, true)
	if res := e.CheckDNS("ads.example.com"); res.Decision == DecisionAllow {
		t.Errorf("CheckDNS with DNS layer re-enabled allowed, want blocked")
	}
}

func TestCheckSNI(t *testing.T) {
	e := New()
	e.AddBlockDomain("ads.example.com")
	res := e.CheckSNI("ads.example.com")
	if res.Decision == DecisionAllow {
		t.Errorf("CheckSNI(ads.example.com) allowed, want blocked")
	}
	if res.Layer != LayerSNI {
		t.Errorf("CheckSNI Layer = %d, want %d", res.Layer, LayerSNI)
	}
}

func TestCheckHTTPS(t *testing.T) {
	e := New()
	e.AddBlockDomain("ads.example.com")
	res := e.CheckHTTPS("ads.example.com", "/banner.js")
	if res.Decision == DecisionAllow {
		t.Errorf("CheckHTTPS(ads.example.com) allowed, want blocked")
	}
	if res.Layer != LayerHTTPS {
		t.Errorf("CheckHTTPS Layer = %d, want %d", res.Layer, LayerHTTPS)
	}
}

func TestCNAMECloak(t *testing.T) {
	e := New()
	e.AddCNAMECloakTarget("real-tracker.evil.net")
	// benign.example.com resolves (via CNAME) to real-tracker.evil.net.
	res := e.CheckDNSWithCNAMEs("benign.example.com", []string{"real-tracker.evil.net"})
	if res.Decision == DecisionAllow {
		t.Errorf("CheckDNSWithCNAMEs allowed a cloaked domain, want blocked")
	}
	if res.Reason == "" {
		t.Errorf("Reason should not be empty on CNAME-cloak block")
	}
}
