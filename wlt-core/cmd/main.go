// Command main is an end-to-end smoke test for the WLT core engine. It
// builds an Engine, loads the bundled blocklists (if available), runs a
// batch of sample queries, and prints the resulting stats.
//
// Run with: go run ./cmd
package main

import (
	"fmt"
	"os"

	"github.com/wlt/adblocker"
)

func main() {
	eng, err := adblocker.NewEngine()
	if err != nil {
		fmt.Fprintf(os.Stderr, "NewEngine: %v\n", err)
		os.Exit(1)
	}

	// Try to load bundled blocklists from ./assets/blocklists (best effort).
	_ = eng.LoadDefaultBlocklists("./assets/blocklists")

	// Seed a few rules so the demo is non-trivial even without assets.
	eng.AddBlockDomain("ads.example.com")
	eng.AddBlockDomain("*.tracker.evil.net")
	eng.AddAllowDomain("banking.example.com")
	eng.AddDenyDomain("explicit-block.example.com")
	eng.AddCNAMECloakTarget("real-tracker.evil.net")

	samples := []struct {
		domain string
	}{
		{"ads.example.com"},
		{"sub.ads.example.com"},
		{"sub.tracker.evil.net"},
		{"banking.example.com"},
		{"explicit-block.example.com"},
		{"pagead2.googlesyndication.com"}, // game SDK
		{"clean.example.org"},              // no match
	}

	fmt.Println("=== WLT Core E2E Smoke Test ===")
	for _, s := range samples {
		res := eng.CheckDNS(s.domain)
		decision := decisionString(res.Decision)
		fmt.Printf("%-40s -> %-10s layer=%d sdk=%s reason=%q\n",
			s.domain, decision, res.Layer, res.SDK, res.Reason)
	}

	fmt.Println("\n=== Stats ===")
	fmt.Println(eng.StatsJSON())

	fmt.Println("\n=== Forensics (last 5) ===")
	fmt.Println(eng.ForensicsRecent(5))

	fmt.Println("\n=== Recommended Fixes ===")
	fmt.Println(eng.RecommendFixes())
}

func decisionString(d int) string {
	switch d {
	case 0:
		return "ALLOW"
	case 1:
		return "BLOCK"
	case 2:
		return "NULLIP"
	case 3:
		return "NXDOMAIN"
	default:
		return fmt.Sprintf("?(%d)", d)
	}
}
