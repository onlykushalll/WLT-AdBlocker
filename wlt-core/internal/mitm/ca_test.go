package mitm

import (
	"crypto/tls"
	"crypto/x509"
	"strings"
	"testing"
)

func TestNewCA(t *testing.T) {
	c, err := NewCA()
	if err != nil {
		t.Fatalf("NewCA: %v", err)
	}
	if c.rootCert == nil || !c.rootCert.IsCA {
		t.Fatal("root cert not IsCA")
	}
	if c.rootKey == nil {
		t.Fatal("nil root key")
	}
	if c.rootKey.Curve.Params().Name != "P-256" {
		t.Fatalf("want P-256, got %s", c.rootKey.Curve.Params().Name)
	}
}

func TestSignAndCache(t *testing.T) {
	c, _ := NewCA()
	leaf, err := c.Sign("www.example.com")
	if err != nil {
		t.Fatalf("Sign: %v", err)
	}
	if leaf == nil || leaf.Leaf == nil {
		t.Fatal("nil leaf")
	}
	if !strings.EqualFold(leaf.Leaf.DNSNames[0], "www.example.com") {
		t.Fatalf("DNSNames[0]=%v", leaf.Leaf.DNSNames)
	}
	// Cache hit on second call.
	if c.CacheSize() != 1 {
		t.Fatalf("CacheSize=%d want 1", c.CacheSize())
	}
	leaf2, _ := c.Sign("www.example.com")
	if leaf2 != leaf {
		t.Fatal("cache returned different pointer")
	}
	// New host should grow the cache.
	_, _ = c.Sign("api.example.com")
	if c.CacheSize() != 2 {
		t.Fatalf("CacheSize=%d want 2", c.CacheSize())
	}
}

func TestPEMAndFingerprint(t *testing.T) {
	c, _ := NewCA()
	pemBytes, err := c.PEM()
	if err != nil {
		t.Fatalf("PEM: %v", err)
	}
	if !strings.Contains(string(pemBytes), "BEGIN CERTIFICATE") {
		t.Fatalf("PEM missing BEGIN: %s", pemBytes)
	}
	// Verify the PEM can be parsed back into a cert.
	block, _ := pemDecode(pemBytes)
	if block == nil {
		t.Fatal("PEM decode failed")
	}
	fp := c.Fingerprint()
	if len(strings.Split(fp, ":")) != 32 {
		t.Fatalf("fingerprint not 32 bytes: %s", fp)
	}
}

// pemDecode is split out so we don't shadow encoding/pem in the test.
func pemDecode(b []byte) (*pemBlock, error) {
	// Use the std library via a wrapper to avoid the import cycle here.
	return parsePEM(b)
}

type pemBlock struct {
	Bytes []byte
}

// parsePEM parses the first PEM block from b. Used only by tests.
func parsePEM(b []byte) (*pemBlock, error) {
	// We use the encoding/pem package indirectly through x509.
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(b) {
		return nil, nil
	}
	return &pemBlock{Bytes: b}, nil
}

func TestRegenerate(t *testing.T) {
	c, _ := NewCA()
	old := c.Fingerprint()
	_, _ = c.Sign("a.com")
	if c.CacheSize() != 1 {
		t.Fatal("expected cache=1")
	}
	if err := c.Regenerate(); err != nil {
		t.Fatalf("Regenerate: %v", err)
	}
	if c.Fingerprint() == old {
		t.Fatal("fingerprint unchanged after Regenerate")
	}
	if c.CacheSize() != 0 {
		t.Fatalf("cache not cleared: %d", c.CacheSize())
	}
}

func TestNormalizeHost(t *testing.T) {
	cases := map[string]string{
		"Example.COM":    "example.com",
		"ex.com:443":     "ex.com",
		"[::1]:443":      "::1",
		"  spaced  ":     "spaced",
		"":               "",
	}
	for in, want := range cases {
		got := normalizeHost(in)
		if got != want {
			t.Errorf("normalizeHost(%q)=%q want %q", in, got, want)
		}
	}
}

func TestSignEmptyHost(t *testing.T) {
	c, _ := NewCA()
	if _, err := c.Sign(""); err == nil {
		t.Fatal("expected error on empty host")
	}
}

// tls import is needed for the Sign return type to compile in this test.
var _ = tls.Certificate{}
