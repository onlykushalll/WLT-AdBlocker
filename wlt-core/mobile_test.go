package adblocker

import (
	"strings"
	"testing"
)

func TestNewEngine(t *testing.T) {
	e, err := NewEngine()
	if err != nil {
		t.Fatalf("NewEngine: %v", err)
	}
	if e == nil {
		t.Fatalf("NewEngine returned nil")
	}
}

func TestShouldBlock(t *testing.T) {
	e, _ := NewEngine()
	e.AddBlockDomain("ads.example.com")
	if !e.ShouldBlock("ads.example.com") {
		t.Errorf("ShouldBlock(ads.example.com) = false, want true")
	}
	if e.ShouldBlock("clean.example.org") {
		t.Errorf("ShouldBlock(clean.example.org) = true, want false")
	}
}

func TestAllowOverridesBlock(t *testing.T) {
	e, _ := NewEngine()
	e.AddBlockDomain("example.com")
	e.AddAllowDomain("allow.example.com")
	if e.ShouldBlock("allow.example.com") {
		t.Errorf("ShouldBlock(allow.example.com) = true, want false (allowlist)")
	}
}

func TestDenyOverridesAllow(t *testing.T) {
	e, _ := NewEngine()
	e.AddAllowDomain("example.com")
	e.AddDenyDomain("evil.example.com")
	if !e.ShouldBlock("evil.example.com") {
		t.Errorf("ShouldBlock(evil.example.com) = false, want true (denylist)")
	}
}

func TestCheckDNSMobile(t *testing.T) {
	e, _ := NewEngine()
	e.AddBlockDomain("ads.example.com")
	res := e.CheckDNS("ads.example.com")
	if res.Decision == 0 { // 0 = allow
		t.Errorf("CheckDNS returned allow for blocked domain")
	}
	if res.Reason == "" {
		t.Errorf("Reason should not be empty")
	}
}

func TestStatsJSON(t *testing.T) {
	e, _ := NewEngine()
	e.AddBlockDomain("ads.example.com")
	e.CheckDNS("ads.example.com")
	e.CheckDNS("clean.example.org")
	stats := e.StatsJSON()
	if !strings.Contains(stats, "total_queries") {
		t.Errorf("StatsJSON missing 'total_queries': %s", stats)
	}
	if !strings.Contains(stats, "block_rate") {
		t.Errorf("StatsJSON missing 'block_rate': %s", stats)
	}
}

func TestStatsJSONDivByZeroGuard(t *testing.T) {
	// When no queries have been issued, StatsJSON must not crash on the
	// block_rate = blocked/total division. totalQueries=0 path returns 0.
	e, _ := NewEngine()
	stats := e.StatsJSON()
	if !strings.Contains(stats, `"block_rate": 0`) {
		t.Errorf("StatsJSON on empty engine missing block_rate=0: %s", stats)
	}
}

func TestGameSDKDetection(t *testing.T) {
	e, _ := NewEngine()
	if name := e.GameSDKName("pagead2.googlesyndication.com"); name != "AdMob" {
		t.Errorf("GameSDKName(AdMob domain) = %q, want AdMob", name)
	}
	if name := e.GameSDKName("clean.example.org"); name != "" {
		t.Errorf("GameSDKName(unknown) = %q, want empty", name)
	}
}

func TestGracefulAdResponse(t *testing.T) {
	e, _ := NewEngine()
	resp := e.GracefulAdResponse("pagead2.googlesyndication.com")
	if !strings.Contains(string(resp), "VAST") {
		t.Errorf("AdMob graceful response should be VAST, got: %s", resp)
	}
	resp = e.GracefulAdResponse("an.facebook.com")
	if !strings.Contains(string(resp), "no_fill") {
		t.Errorf("Meta graceful response should be JSON no_fill, got: %s", resp)
	}
}

func TestCALifecycle(t *testing.T) {
	ca, err := NewCA()
	if err != nil {
		t.Fatalf("NewCA: %v", err)
	}
	if ca.IsRunning() {
		t.Errorf("CA.IsRunning = true before StartHttpsProxy")
	}
	if err := ca.StartHttpsProxy(); err != nil {
		t.Errorf("StartHttpsProxy: %v", err)
	}
	if !ca.IsRunning() {
		t.Errorf("CA.IsRunning = false after StartHttpsProxy")
	}
	ca.StopHttpsProxy()
	if ca.IsRunning() {
		t.Errorf("CA.IsRunning = true after StopHttpsProxy")
	}
}
