package forensics

import (
	"strings"
	"testing"
	"time"
)

func TestRecordAndRecent(t *testing.T) {
	e := New(10)
	for i := 0; i < 5; i++ {
		e.Record(Trace{
			Timestamp: time.Now(),
			Domain:    "ads.example.com",
			Layer:     LayerDNS,
			Decision:  DecisionBlock,
			Reason:    "matched blocklist",
			SDK:       "AdMob",
		})
	}
	if got := e.Size(); got != 5 {
		t.Errorf("Size = %d, want 5", got)
	}
	rec := e.Recent(3)
	if len(rec) != 3 {
		t.Errorf("Recent(3) returned %d traces, want 3", len(rec))
	}
	for _, r := range rec {
		if r.Domain != "ads.example.com" {
			t.Errorf("Recent() returned wrong domain: %q", r.Domain)
		}
	}
}

func TestRingBufferOverflow(t *testing.T) {
	e := New(3)
	for i := 0; i < 10; i++ {
		e.Record(Trace{Domain: "d", Reason: "r"})
	}
	if got := e.Size(); got != 3 {
		t.Errorf("Size after overflow = %d, want 3", got)
	}
	rec := e.Recent(3)
	if len(rec) != 3 {
		t.Fatalf("Recent(3) = %d, want 3", len(rec))
	}
}

func TestRecommendFixes(t *testing.T) {
	e := New(100)
	// 5 allows for one domain → should produce a recommendation.
	for i := 0; i < 5; i++ {
		e.Record(Trace{
			Domain:   "sneaky.ads.com",
			Decision: DecisionAllow,
			Reason:   "matched allowlist",
		})
	}
	// 7 blocks on one SDK → SDK leaderboard recommendation.
	for i := 0; i < 7; i++ {
		e.Record(Trace{
			Domain:   "ads.unity3d.com",
			Decision: DecisionBlock,
			SDK:      "Unity",
		})
	}
	recs := e.RecommendFixes()
	if len(recs) == 0 {
		t.Fatalf("RecommendFixes() returned no recommendations")
	}
	foundDomain := false
	foundSDK := false
	for _, r := range recs {
		if strings.Contains(r, "sneaky.ads.com") {
			foundDomain = true
		}
		if strings.Contains(r, "Unity") {
			foundSDK = true
		}
	}
	if !foundDomain {
		t.Errorf("RecommendFixes() did not mention the most-allowed domain: %v", recs)
	}
	if !foundSDK {
		t.Errorf("RecommendFixes() did not mention the top blocked SDK: %v", recs)
	}
}

func TestEmptyRecent(t *testing.T) {
	e := New(10)
	if rec := e.Recent(5); len(rec) != 0 {
		t.Errorf("Recent() on empty engine = %d, want 0", len(rec))
	}
	recs := e.RecommendFixes()
	if len(recs) == 0 {
		t.Errorf("RecommendFixes() on empty engine returned nothing")
	}
}
