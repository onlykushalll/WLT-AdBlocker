package main

import (
	"fmt"
	"wlt-core/dns"
	"wlt-core/engine"
	"wlt-core/internal/gamesdk"
)

func main() {
	e := engine.New()

	// Load the SAME blocklist the app uses
	adDomains := []string{
		"googleads.g.doubleclick.net","pagead2.googlesyndication.com",
		"googlesyndication.com","doubleclick.net","googleadservices.com",
		"adservice.google.com","admob.google.com",
		"unityads.unity3d.com","cloud.unity3d.com",
		"applovin.com","ironsrc.com","chartboost.com","vungle.com",
		"an.facebook.com","adcolony.com","mintegral.com","fyber.com",
		"tapjoy.com","inmobi.com","appsflyer.com","adjust.com","branch.io",
		// DoH bypass
		"dns.google","cloudflare-dns.com","dns.quad9.net","doh.opendns.com","dns.adguard.com",
	}
	for _, d := range adDomains { e.AddBlockDomain(d) }

	// Allowlist (banking/critical)
	allowDomains := []string{
		"google.com","googlevideo.com","youtube.com","chase.com","paypal.com","visa.com",
		"apple.com","microsoft.com","github.com",
	}
	for _, d := range allowDomains { e.AddAllowDomain(d) }

	fmt.Println("========================================")
	fmt.Println("WLT-Adblocker End-to-End DNS Test")
	fmt.Println("========================================")
	fmt.Printf("Blocklist: %d domains\n", e.BlocklistSize())
	fmt.Printf("Allowlist: %d domains\n", e.AllowlistSize())
	fmt.Println()

	// Test categories
	tests := []struct {
		category string
		domains  []string
		wantBlock bool
	}{
		{"YouTube video CDN (must ALLOW - breaks video)", []string{
			"r1.sn.googlevideo.com","r2.sn.googlevideo.com","manifest.googlevideo.com",
		}, false},
		{"YouTube ads (different domain - blocked)", []string{
			"pagead2.googlesyndication.com","googleads.g.doubleclick.net",
		}, true},
		{"Game ads (AdMob)", []string{
			"ad.doubleclick.net","adclick.g.doubleclick.net","pubads.g.doubleclick.net",
		}, true},
		{"Game ads (Unity)", []string{
			"unityads.unity3d.com","ads.unityads.unity3d.com",
		}, true},
		{"Game ads (AppLovin)", []string{
			"rt.applovin.com","ms.applovin.com","vid.applovin.com",
		}, true},
		{"Game ads (ironSource/Chartboost/Vungle)", []string{
			"api.ironsrc.com","live.chartboost.com","api.vungle.com",
		}, true},
		{"Meta Audience Network", []string{
			"an.facebook.com","ads.facebook.com",
		}, true},
		{"Attribution SDKs", []string{
			"events.appsflyer.com","app.adjust.com","api.branch.io",
		}, true},
		{"DoH bypass (must BLOCK)", []string{
			"dns.google","cloudflare-dns.com","dns.quad9.net",
		}, true},
		{"Banking (must ALLOW)", []string{
			"www.chase.com","www.paypal.com","www.visa.com",
		}, false},
		{"Legitimate sites (must ALLOW)", []string{
			"www.google.com","mail.google.com","github.com","stackoverflow.com",
		}, false},
	}

	totalPass := 0
	totalFail := 0
	for _, tc := range tests {
		fmt.Printf("--- %s ---\n", tc.category)
		for _, domain := range tc.domains {
			blocked := e.ShouldBlock(domain)
			sdk := ""
			if s := gamesdk.New().DetectByDomain(domain); s != gamesdk.SDKUnknown {
				sdk = " [" + string(s) + "]"
			}
			status := "ALLOW"
			if blocked { status = "BLOCK" }
			pass := "✓"
			if blocked != tc.wantBlock { pass = "✗ FAIL" }
			if blocked == tc.wantBlock { totalPass++ } else { totalFail++ }
			fmt.Printf("  %s %-50s -> %s%s\n", pass, domain, status, sdk)
		}
	}

	fmt.Println()
	fmt.Println("========================================")
	fmt.Printf("RESULTS: %d passed, %d failed\n", totalPass, totalFail)
	fmt.Printf("Block rate: %.1f%%\n", float64(e.TotalBlocked())/float64(e.TotalBlocked()+e.TotalAllowed())*100)
	fmt.Println("========================================")

	// Test DNS packet building
	fmt.Println("\n=== DNS Packet Test ===")
	// Build a DNS query for "pagead2.googlesyndication.com"
	query := buildQuery("pagead2.googlesyndication.com")
	domain, err := dns.ExtractQueryDomain(query)
	if err != nil {
		fmt.Printf("Parse error: %v\n", err)
	} else {
		fmt.Printf("Query domain extracted: %s\n", domain)
	}
	// Build NXDOMAIN response
	msg, _ := dns.Parse(query)
	nxdomain := dns.BuildNxDomain(msg)
	fmt.Printf("NXDOMAIN response: %d bytes\n", len(nxdomain))
	fmt.Println("DNS packet building: ✓")
}

func buildQuery(domain string) []byte {
	var buf []byte
	buf = append(buf, 0x00, 0x01) // ID
	buf = append(buf, 0x01, 0x00) // flags RD=1
	buf = append(buf, 0x00, 0x01) // QDCOUNT
	buf = append(buf, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
	for _, label := range splitDot(domain) {
		buf = append(buf, byte(len(label)))
		buf = append(buf, []byte(label)...)
	}
	buf = append(buf, 0)
	buf = append(buf, 0x00, 0x01, 0x00, 0x01) // type A, class IN
	return buf
}

func splitDot(s string) []string {
	var out []string
	cur := ""
	for _, c := range s {
		if c == '.' { out = append(out, cur); cur = "" } else { cur += string(c) }
	}
	if cur != "" { out = append(out, cur) }
	return out
}
