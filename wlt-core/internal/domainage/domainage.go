// Package domainage implements a domain-age RDAP checker used to flag
// suspicious newly-registered domains (NextDNS technique).
//
// The Checker queries RDAP (Registration Data Access Protocol) for the
// domain's creation date, caches the result for the lifetime of the
// process, and returns:
//
//   - IsSuspicious(domain) = true if the domain is < 30 days old
//   - SuspicionScore(domain) = 0.0..1.0 based on age (1.0 if < 7d, 0.5 if
//     7-30d, 0.2 if 30-90d, 0.0 if older)
//
// Failures (RDAP unreachable, malformed response, etc.) are treated as
// suspicious=true (fail-safe) so attackers can't evade the check by
// returning malformed RDAP.
package domainage

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

// RDAPBase is the default RDAP bootstrap endpoint.
const RDAPBase = "https://rdap.org/domain"

// SuspiciousThreshold is the cutoff (30 days) below which a domain is
// flagged as suspicious.
const SuspiciousThreshold = 30 * 24 * time.Hour

// Checker queries RDAP for domain creation dates and caches the results.
type Checker struct {
	HTTP    *http.Client
	BaseURL string

	mu    sync.Mutex
	cache map[string]cacheEntry
}

type cacheEntry struct {
	createdAt time.Time
	err       error
	queriedAt time.Time
}

// New returns a Checker configured with sensible defaults.
func New() *Checker {
	return &Checker{
		HTTP:    &http.Client{Timeout: 6 * time.Second},
		BaseURL: RDAPBase,
		cache:   make(map[string]cacheEntry),
	}
}

// IsSuspicious returns true if the given domain was registered less than
// SuspiciousThreshold days ago, or if the RDAP query failed (fail-safe).
func (c *Checker) IsSuspicious(domain string) bool {
	created, err := c.creationDate(domain)
	if err != nil {
		return true // fail-safe
	}
	age := time.Since(created)
	return age < SuspiciousThreshold
}

// SuspicionScore returns a 0.0-1.0 confidence score:
//   - age < 7d:   1.0
//   - age < 30d:  0.5
//   - age < 90d:  0.2
//   - age >= 90d: 0.0
//
// On RDAP failure the score is 1.0 (fail-safe).
func (c *Checker) SuspicionScore(domain string) float64 {
	created, err := c.creationDate(domain)
	if err != nil {
		return 1.0
	}
	age := time.Since(created)
	switch {
	case age < 7*24*time.Hour:
		return 1.0
	case age < 30*24*time.Hour:
		return 0.5
	case age < 90*24*time.Hour:
		return 0.2
	default:
		return 0.0
	}
}

// creationDate returns the domain's creation date from the cache or by
// querying RDAP. The result (or error) is cached for the lifetime of the
// process.
func (c *Checker) creationDate(domain string) (time.Time, error) {
	domain = strings.ToLower(strings.TrimSpace(domain))
	domain = strings.TrimSuffix(domain, ".")
	if domain == "" {
		return time.Time{}, errors.New("domainage: empty domain")
	}
	c.mu.Lock()
	if entry, ok := c.cache[domain]; ok {
		c.mu.Unlock()
		return entry.createdAt, entry.err
	}
	c.mu.Unlock()

	created, err := c.queryRDAP(domain)

	c.mu.Lock()
	c.cache[domain] = cacheEntry{createdAt: created, err: err, queriedAt: time.Now()}
	c.mu.Unlock()
	return created, err
}

// queryRDAP queries the RDAP server for the given domain and parses the
// creation date from the "events" array. The creation event is the one
// with eventAction="registration".
func (c *Checker) queryRDAP(domain string) (time.Time, error) {
	url := c.BaseURL + "/" + domain
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return time.Time{}, err
	}
	req.Header.Set("Accept", "application/rdap+json")
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return time.Time{}, fmt.Errorf("domainage: HTTP: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return time.Time{}, fmt.Errorf("domainage: HTTP %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return time.Time{}, fmt.Errorf("domainage: read: %w", err)
	}
	return parseRDAPCreation(body)
}

// parseRDAPCreation extracts the registration event date from an RDAP
// response body.
func parseRDAPCreation(body []byte) (time.Time, error) {
	var doc struct {
		Events []struct {
			EventAction string `json:"eventAction"`
			EventDate   string `json:"eventDate"`
		} `json:"events"`
	}
	if err := json.Unmarshal(body, &doc); err != nil {
		return time.Time{}, fmt.Errorf("domainage: parse: %w", err)
	}
	for _, ev := range doc.Events {
		if strings.EqualFold(ev.EventAction, "registration") {
			t, err := time.Parse(time.RFC3339, ev.EventDate)
			if err != nil {
				return time.Time{}, fmt.Errorf("domainage: parse date %q: %w", ev.EventDate, err)
			}
			return t, nil
		}
	}
	return time.Time{}, errors.New("domainage: no registration event")
}

// ClearCache empties the per-process cache.
func (c *Checker) ClearCache() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cache = make(map[string]cacheEntry)
}

// CacheSize returns the number of cached entries.
func (c *Checker) CacheSize() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.cache)
}
