package domainage

import "testing"

func TestNew(t *testing.T) {
	c := New(30)
	if c == nil { t.Fatal("nil checker") }
	if c.maxAgeDays != 30 { t.Errorf("maxAgeDays = %d, want 30", c.maxAgeDays) }
}

func TestNewDefault(t *testing.T) {
	c := New(0)
	if c.maxAgeDays != 30 { t.Errorf("default maxAgeDays = %d, want 30", c.maxAgeDays) }
}

func TestSuspicionScore(t *testing.T) {
	c := New(30)
	// Test with known old domain (google.com should be > 20 years)
	// This test makes a real network call — skip if offline
	info := c.CheckDomain("google.com")
	if info.Error != nil {
		t.Skipf("RDAP query failed (network issue): %v", info.Error)
	}
	if info.AgeDays < 365 {
		t.Errorf("google.com age = %d days, expected > 365", info.AgeDays)
	}
	score := c.SuspicionScore("google.com")
	if score > 0.1 {
		t.Errorf("google.com suspicion = %.2f, expected < 0.1", score)
	}
}

func TestSetMaxAgeDays(t *testing.T) {
	c := New(30)
	c.SetMaxAgeDays(7)
	if c.maxAgeDays != 7 { t.Errorf("maxAgeDays = %d, want 7", c.maxAgeDays) }
}

func TestCacheSize(t *testing.T) {
	c := New(30)
	if c.CacheSize() != 0 { t.Errorf("initial cache = %d, want 0", c.CacheSize()) }
	c.cache["test.com"] = &DomainInfo{Domain: "test.com"}
	if c.CacheSize() != 1 { t.Errorf("cache = %d, want 1", c.CacheSize()) }
	c.ClearCache()
	if c.CacheSize() != 0 { t.Errorf("cache after clear = %d, want 0", c.CacheSize()) }
}

func TestInvalidDomain(t *testing.T) {
	c := New(30)
	info := c.CheckDomain("invalid")
	if info.Error == nil { t.Error("expected error for invalid domain") }
}
