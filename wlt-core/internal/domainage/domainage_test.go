package domainage

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestNewDomain(t *testing.T) {
	c := newTestChecker(time.Now().Add(1 * time.Hour)) // registered 1h ago
	if !c.IsSuspicious("new.com") {
		t.Error("1-hour-old domain should be suspicious")
	}
	if s := c.SuspicionScore("new.com"); s != 1.0 {
		t.Errorf("SuspicionScore=%.2f want 1.0", s)
	}
}

func TestDefault(t *testing.T) {
	// 10-year-old domain — not suspicious.
	c := newTestChecker(time.Now().Add(-10 * 365 * 24 * time.Hour))
	if c.IsSuspicious("old.com") {
		t.Error("10-year-old domain should not be suspicious")
	}
	if s := c.SuspicionScore("old.com"); s != 0.0 {
		t.Errorf("SuspicionScore=%.2f want 0.0", s)
	}
}

func TestSuspicion(t *testing.T) {
	// 15-day-old domain — 0.5 score.
	c := newTestChecker(time.Now().Add(-15 * 24 * time.Hour))
	if !c.IsSuspicious("mid.com") {
		t.Error("15-day-old domain should be suspicious")
	}
	if s := c.SuspicionScore("mid.com"); s != 0.5 {
		t.Errorf("SuspicionScore=%.2f want 0.5", s)
	}
	// 60-day-old domain — 0.2 score.
	c2 := newTestChecker(time.Now().Add(-60 * 24 * time.Hour))
	if c2.IsSuspicious("older.com") {
		t.Error("60-day-old domain should not be suspicious")
	}
	if s := c2.SuspicionScore("older.com"); s != 0.2 {
		t.Errorf("SuspicionScore=%.2f want 0.2", s)
	}
}

func TestCache(t *testing.T) {
	hits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Header().Set("Content-Type", "application/rdap+json")
		w.Write([]byte(`{"events":[{"eventAction":"registration","eventDate":"` + time.Now().Add(-1*time.Hour).Format(time.RFC3339) + `"}]}`))
	}))
	defer srv.Close()
	c := New()
	c.BaseURL = srv.URL
	_ = c.IsSuspicious("cached.com")
	_ = c.IsSuspicious("cached.com")
	_ = c.IsSuspicious("cached.com")
	if hits != 1 {
		t.Errorf("expected 1 HTTP hit, got %d (cache not working)", hits)
	}
	if c.CacheSize() != 1 {
		t.Errorf("CacheSize=%d want 1", c.CacheSize())
	}
	c.ClearCache()
	if c.CacheSize() != 0 {
		t.Errorf("CacheSize after clear=%d want 0", c.CacheSize())
	}
}

func TestInvalid(t *testing.T) {
	// RDAP returns 404 -> fail-safe suspicious=true.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()
	c := New()
	c.BaseURL = srv.URL
	if !c.IsSuspicious("unknown.com") {
		t.Error("RDAP failure should be fail-safe suspicious")
	}
	if s := c.SuspicionScore("unknown.com"); s != 1.0 {
		t.Errorf("SuspicionScore=%.2f want 1.0", s)
	}
}

// TestParseRDAPCreation checks the JSON parser against a sample response.
func TestParseRDAPCreation(t *testing.T) {
	body := []byte(`{
		"events": [
			{"eventAction":"last changed","eventDate":"2024-01-01T00:00:00Z"},
			{"eventAction":"registration","eventDate":"2023-06-15T08:30:00Z"}
		]
	}`)
	t1, err := parseRDAPCreation(body)
	if err != nil {
		t.Fatalf("parseRDAPCreation: %v", err)
	}
	want := "2023-06-15T08:30:00Z"
	if !strings.Contains(t1.Format(time.RFC3339), "2023-06-15") {
		t.Errorf("got %v want contains %s", t1, want)
	}
}

// TestParseRDAPCreationMissing verifies that a body without a registration
// event returns an error.
func TestParseRDAPCreationMissing(t *testing.T) {
	body, _ := json.Marshal(map[string]any{"events": []map[string]string{}})
	if _, err := parseRDAPCreation(body); err == nil {
		t.Error("expected error on missing registration event")
	}
}

func newTestChecker(createdAt time.Time) *Checker {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/rdap+json")
		w.Write([]byte(`{"events":[{"eventAction":"registration","eventDate":"` + createdAt.Format(time.RFC3339) + `"}]}`))
	}))
	c := New()
	c.BaseURL = srv.URL
	return c
}
