// Package domainage implements domain age checking — blocking domains
// that are too new (recently registered), which catches:
//
//   - DGA (Domain Generation Algorithm) domains registered en masse
//   - AI "phantom squatting" domains (LLM-hallucinated, pre-registered)
//   - Ad network "replica domains" (copies of blocked domains)
//   - Fresh malware/phishing domains
//
// This is the technique NextDNS uses: "block domains newer than N days."
// It's extremely effective against zero-day ad/tracker domains because
// legitimate infrastructure domains are almost always > 1 year old.
//
// Implementation: queries RDAP (Registration Data Access Protocol) for
// domain creation date. RDAP is the modern replacement for WHOIS.
// Example: https://rdap.org/domain/example.com
package domainage

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Checker queries RDAP for domain age.
type Checker struct {
	client    *http.Client
	rdapURL   string
	cache     map[string]*DomainInfo
	maxAgeDays int // domains younger than this are suspicious
}

// DomainInfo holds RDAP response data.
type DomainInfo struct {
	Domain      string
	CreatedDate time.Time
	AgeDays     int
	Registrar   string
	Status      []string
	Error       error
}

// New creates a domain age checker.
// maxAgeDays: domains younger than this are flagged as suspicious (default: 30).
func New(maxAgeDays int) *Checker {
	if maxAgeDays <= 0 {
		maxAgeDays = 30
	}
	return &Checker{
		client:    &http.Client{Timeout: 5 * time.Second},
		rdapURL:   "https://rdap.org/domain",
		cache:     make(map[string]*DomainInfo),
		maxAgeDays: maxAgeDays,
	}
}

// CheckDomain queries RDAP for domain creation date.
// Results are cached for the session.
func (c *Checker) CheckDomain(domain string) *DomainInfo {
	d := strings.ToLower(strings.TrimSpace(domain))
	d = strings.Trim(d, ".")

	// Extract the registrable domain (second-level + TLD)
	labels := strings.Split(d, ".")
	if len(labels) < 2 {
		return &DomainInfo{Domain: d, Error: errors.New("invalid domain")}
	}
	regDomain := strings.Join(labels[len(labels)-2:], ".")

	// Check cache
	if info, ok := c.cache[regDomain]; ok {
		return info
	}

	info := c.queryRDAP(regDomain)
	c.cache[regDomain] = info
	return info
}

// IsSuspicious returns true if the domain is too young or can't be verified.
func (c *Checker) IsSuspicious(domain string) bool {
	info := c.CheckDomain(domain)
	if info.Error != nil {
		// Can't verify age — treat as suspicious (fail safe)
		return true
	}
	return info.AgeDays < c.maxAgeDays
}

// SuspicionScore returns 0.0-1.0 based on domain age.
// Newer domains = higher score (more suspicious).
func (c *Checker) SuspicionScore(domain string) float64 {
	info := c.CheckDomain(domain)
	if info.Error != nil {
		return 0.8 // Can't verify — high suspicion
	}
	if info.AgeDays < 7 {
		return 1.0 // Very new — almost certainly suspicious
	}
	if info.AgeDays < 30 {
		return 0.7 // Suspicious
	}
	if info.AgeDays < 90 {
		return 0.3 // Somewhat new
	}
	if info.AgeDays < 365 {
		return 0.1 // Less than 1 year
	}
	return 0.0 // Old domain — likely legitimate
}

func (c *Checker) queryRDAP(domain string) *DomainInfo {
	url := fmt.Sprintf("%s/%s", c.rdapURL, domain)

	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		return &DomainInfo{Domain: domain, Error: err}
	}
	req.Header.Set("User-Agent", "WLT-Adblocker/0.1")
	req.Header.Set("Accept", "application/rdap+json")

	resp, err := c.client.Do(req)
	if err != nil {
		return &DomainInfo{Domain: domain, Error: err}
	}
	defer resp.Body.Close()

	if resp.StatusCode == 404 {
		return &DomainInfo{Domain: domain, Error: errors.New("domain not found in RDAP")}
	}
	if resp.StatusCode != 200 {
		return &DomainInfo{Domain: domain, Error: fmt.Errorf("RDAP HTTP %d", resp.StatusCode)}
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return &DomainInfo{Domain: domain, Error: err}
	}

	// Parse RDAP response
	var rdap struct {
		Events []struct {
			EventAction string `json:"eventAction"`
			EventDate   string `json:"eventDate"`
		} `json:"events"`
		Entities []struct {
			VcardArray []interface{} `json:"vcardArray"`
		} `json:"entities"`
		Status []string `json:"status"`
	}

	if err := json.Unmarshal(body, &rdap); err != nil {
		return &DomainInfo{Domain: domain, Error: err}
	}

	info := &DomainInfo{
		Domain: domain,
		Status: rdap.Status,
	}

	// Find registration date
	for _, event := range rdap.Events {
		if event.EventAction == "registration" {
			created, err := time.Parse(time.RFC3339, event.EventDate)
			if err == nil {
				info.CreatedDate = created
				info.AgeDays = int(time.Since(created).Hours() / 24)
			}
		}
	}

	if info.CreatedDate.IsZero() {
		info.Error = errors.New("no registration date in RDAP")
	}

	return info
}

// SetMaxAgeDays changes the suspicious threshold.
func (c *Checker) SetMaxAgeDays(days int) {
	c.maxAgeDays = days
}

// CacheSize returns the number of cached domain lookups.
func (c *Checker) CacheSize() int {
	return len(c.cache)
}

// ClearCache clears the domain age cache.
func (c *Checker) ClearCache() {
	c.cache = make(map[string]*DomainInfo)
}
