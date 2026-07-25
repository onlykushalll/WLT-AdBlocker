package bloom

import (
	"fmt"
	"math/rand"
	"testing"
)

func TestAddContains(t *testing.T) {
	f := New(1000, 0.001)
	domains := []string{
		"ads.example.com",
		"tracker.analytics.net",
		"banner.adnetwork.org",
	}
	for _, d := range domains {
		f.Add(d)
	}
	for _, d := range domains {
		if !f.Contains(d) {
			t.Errorf("Contains(%q) = false, want true", d)
		}
	}
	// Suffix-aware: a query for a subdomain of a blocked parent must hit.
	if !f.Contains("sub.ads.example.com") {
		t.Errorf("Contains(sub.ads.example.com) = false, want true (suffix-aware)")
	}
	if !f.Contains("deep.sub.banner.adnetwork.org") {
		t.Errorf("Contains(deep.sub.banner.adnetwork.org) = false, want true")
	}
	// A domain that shares no suffix with anything inserted should be absent.
	if f.Contains("totally-different-domain.xyz") {
		t.Errorf("Contains(totally-different-domain.xyz) = true, want false")
	}
}

func TestRemove(t *testing.T) {
	f := New(1000, 0.001)
	f.Add("example.com")
	if !f.Contains("example.com") {
		t.Fatalf("Contains(example.com) = false after Add")
	}
	f.Remove("example.com")
	if f.Contains("example.com") {
		t.Errorf("Contains(example.com) = true after Remove, want false")
	}
	// Re-adding should work.
	f.Add("example.com")
	if !f.Contains("example.com") {
		t.Errorf("Contains(example.com) = false after re-Add")
	}
}

func TestFalsePositiveRate(t *testing.T) {
	// Insert N known domains, then test M unseen domains and ensure the
	// observed false-positive rate stays under 0.1%.
	const N = 5000
	const M = 50000
	f := New(N, 0.0005) // target 0.05%
	rng := rand.New(rand.NewSource(42))
	inserted := make(map[string]bool, N)
	for len(inserted) < N {
		d := fmt.Sprintf("blocked-%d.example.com", rng.Intn(1<<30))
		if !inserted[d] {
			inserted[d] = true
			f.Add(d)
		}
	}
	fp := 0
	for i := 0; i < M; i++ {
		// Use a different TLD to guarantee no suffix overlap.
		d := fmt.Sprintf("clean-%d.unseen-tld-%d.net", rng.Intn(1<<30), rng.Intn(1<<30))
		if f.Contains(d) {
			fp++
		}
	}
	rate := float64(fp) / float64(M)
	if rate >= 0.001 {
		t.Errorf("false positive rate = %.4f%% (fp=%d), want < 0.1%%", rate*100, fp)
	}
	t.Logf("observed FP rate = %.4f%% (%d / %d)", rate*100, fp, M)
}

func TestSuffixAware(t *testing.T) {
	f := New(100, 0.001)
	f.Add("example.com")
	// All subdomains must report a hit because example.com was inserted.
	subs := []string{
		"a.example.com",
		"b.example.com",
		"x.y.z.example.com",
	}
	for _, s := range subs {
		if !f.Contains(s) {
			t.Errorf("Contains(%q) = false, want true (suffix-aware)", s)
		}
	}
}

func BenchmarkAdd(b *testing.B) {
	f := New(10000, 0.001)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		f.Add(fmt.Sprintf("d%d.example.com", i))
	}
}

func BenchmarkContains(b *testing.B) {
	f := New(10000, 0.001)
	for i := 0; i < 10000; i++ {
		f.Add(fmt.Sprintf("d%d.example.com", i))
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = f.Contains(fmt.Sprintf("d%d.example.com", i%10000))
	}
}
