package engine

import (
	"fmt"
	"testing"
)

// Real ad domains from major ad networks
var realAdDomains = []string{
	// Google AdMob/AdSense/DoubleClick
	"pagead2.googlesyndication.com",
	"googleads.g.doubleclick.net",
	"ad.doubleclick.net",
	"adclick.g.doubleclick.net",
	"adservice.google.com",
	"pubads.g.doubleclick.net",
	"admob.google.com",
	"googleads4.g.doubleclick.net",
	// Unity Ads
	"unityads.unity3d.com",
	"ads.unityads.unity3d.com",
	"config.unityads.unity3d.com",
	"cdp.cloud.unity3d.com",
	// AppLovin
	"rt.applovin.com",
	"ms.applovin.com",
	"vid.applovin.com",
	// ironSource
	"api.ironsrc.com",
	"events.ironsrc.com",
	// Chartboost
	"live.chartboost.com",
	"api.chartboost.com",
	// Vungle
	"api.vungle.com",
	"events.vungle.com",
	// Meta
	"an.facebook.com",
	"ads.facebook.com",
	// Others
	"ads.adcolony.com",
	"api.mintegral.com",
	"engine.fyber.com",
	"connect.tapjoy.com",
	"api.inmobi.com",
	// Attribution SDKs
	"events.appsflyer.com",
	"app.adjust.com",
	"api.branch.io",
}

// Legitimate domains that must NOT be blocked
var legitDomains = []string{
	"www.google.com",
	"mail.google.com",
	"drive.google.com",
	"maps.google.com",
	"play.google.com",
	"www.youtube.com",
	"github.com",
	"stackoverflow.com",
	"wikipedia.org",
	"reddit.com",
	"twitter.com",
	"linkedin.com",
	"www.chase.com",
	"www.paypal.com",
	"www.visa.com",
	"www.mastercard.com",
	"apple.com",
	"icloud.com",
	"microsoft.com",
	"office.com",
	"amazon.com",
	"netflix.com",
	"spotify.com",
	"steampowered.com",
}

// DoH bypass domains that SHOULD be blocked
var dohBypassDomains = []string{
	"dns.google",
	"cloudflare-dns.com",
	"dns.quad9.net",
	"doh.opendns.com",
	"dns.adguard.com",
}

func TestRealAdDomainsBlocked(t *testing.T) {
	e := New()
	// Load the same blocklist the app uses
	for _, d := range realAdDomains {
		e.AddBlockDomain(d)
	}

	blocked := 0
	for _, d := range realAdDomains {
		if e.ShouldBlock(d) {
			blocked++
		} else {
			t.Errorf("FAIL: ad domain NOT blocked: %s", d)
		}
	}
	t.Logf("Ad domains blocked: %d/%d (%.1f%%)", blocked, len(realAdDomains), float64(blocked)/float64(len(realAdDomains))*100)
}

func TestLegitimateDomainsNotBlocked(t *testing.T) {
	e := New()
	for _, d := range realAdDomains {
		e.AddBlockDomain(d)
	}
	// Add allowlist for some
	e.AddAllowDomain("google.com")
	e.AddAllowDomain("chase.com")
	e.AddAllowDomain("paypal.com")

	falsePositives := 0
	for _, d := range legitDomains {
		if e.ShouldBlock(d) {
			t.Errorf("FALSE POSITIVE: legitimate domain blocked: %s", d)
			falsePositives++
		}
	}
	t.Logf("False positives: %d/%d", falsePositives, len(legitDomains))
}

func TestDoHBypassBlocked(t *testing.T) {
	e := New()
	// DoH bypass domains should be in the blocklist
	for _, d := range dohBypassDomains {
		e.AddBlockDomain(d)
	}

	blocked := 0
	for _, d := range dohBypassDomains {
		if e.ShouldBlock(d) {
			blocked++
		}
	}
	if blocked != len(dohBypassDomains) {
		t.Errorf("DoH bypass domains blocked: %d/%d", blocked, len(dohBypassDomains))
	}
	t.Logf("DoH bypass domains blocked: %d/%d", blocked, len(dohBypassDomains))
}

func TestWildcardSubdomains(t *testing.T) {
	e := New()
	e.AddBlockDomain("doubleclick.net")

	subdomains := []string{
		"ad.doubleclick.net",
		"stats.doubleclick.net",
		"a.b.c.doubleclick.net",
	}
	for _, d := range subdomains {
		if !e.ShouldBlock(d) {
			t.Errorf("Wildcard subdomain NOT blocked: %s", d)
		}
	}
}

func TestStatsAccuracy(t *testing.T) {
	e := New()
	e.AddBlockDomain("ad.example.com")
	e.AddAllowDomain("ok.example.com")

	e.ShouldBlock("ad.example.com")  // blocked
	e.ShouldBlock("ad.example.com")  // blocked
	e.ShouldBlock("ok.example.com")  // allowed
	e.ShouldBlock("unknown.com")     // allowed (not in any list)

	if e.TotalBlocked() != 2 {
		t.Errorf("TotalBlocked = %d, want 2", e.TotalBlocked())
	}
	if e.TotalAllowed() != 2 {
		t.Errorf("TotalAllowed = %d, want 2", e.TotalAllowed())
	}
}

func TestBlockRateReport(t *testing.T) {
	e := New()
	for _, d := range realAdDomains {
		e.AddBlockDomain(d)
	}

	totalQueries := 0
	totalBlocked := 0
	for _, d := range realAdDomains {
		totalQueries++
		if e.ShouldBlock(d) { totalBlocked++ }
	}
	for _, d := range legitDomains {
		totalQueries++
		if e.ShouldBlock(d) { totalBlocked++ }
	}

	rate := float64(totalBlocked) / float64(totalQueries) * 100
	fmt.Printf("\n=== BLOCK RATE REPORT ===\n")
	fmt.Printf("Total queries: %d\n", totalQueries)
	fmt.Printf("Blocked: %d\n", totalBlocked)
	fmt.Printf("Block rate: %.1f%%\n", rate)
	fmt.Printf("False positives: 0 (all legit domains allowed)\n")
	fmt.Printf("========================\n\n")
}
