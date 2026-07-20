package mitm

import (
        "crypto/x509"
        "testing"
)

func TestNewCA(t *testing.T) {
        ca, err := NewCA()
        if err != nil {
                t.Fatalf("NewCA failed: %v", err)
        }
        if ca == nil {
                t.Fatal("CA is nil")
        }
        if len(ca.CAPEM()) == 0 {
                t.Error("CAPEM is empty")
        }
}

func TestCASignCertificate(t *testing.T) {
        ca, err := NewCA()
        if err != nil {
                t.Fatalf("NewCA failed: %v", err)
        }

        sc, err := ca.SignCertificate("example.com")
        if err != nil {
                t.Fatalf("SignCertificate failed: %v", err)
        }
        if sc == nil {
                t.Fatal("SignedCert is nil")
        }
        if len(sc.CertDER) == 0 {
                t.Error("CertDER is empty")
        }

        // Verify the cert is signed by the CA
        cert, err := x509.ParseCertificate(sc.CertDER)
        if err != nil {
                t.Fatalf("ParseCertificate failed: %v", err)
        }
        if cert.Subject.CommonName != "example.com" {
                t.Errorf("CN = %s, want example.com", cert.Subject.CommonName)
        }
        if len(cert.DNSNames) != 1 || cert.DNSNames[0] != "example.com" {
                t.Errorf("DNSNames = %v, want [example.com]", cert.DNSNames)
        }

        // Verify the signature against the CA
        caCert, err := x509.ParseCertificate(ca.certDER)
        if err != nil {
                t.Fatalf("Parse CA cert failed: %v", err)
        }
        pool := x509.NewCertPool()
        pool.AddCert(caCert)
        _, err = cert.Verify(x509.VerifyOptions{
                Roots: pool,
                KeyUsages: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
        })
        if err != nil {
                t.Errorf("Certificate verification failed: %v", err)
        }
}

func TestCACache(t *testing.T) {
        ca, _ := NewCA()

        // First call — should generate
        ca.SignCertificate("cached.com")
        if ca.CacheSize() != 1 {
                t.Errorf("CacheSize = %d, want 1", ca.CacheSize())
        }

        // Second call — should return cached
        ca.SignCertificate("cached.com")
        if ca.CacheSize() != 1 {
                t.Errorf("CacheSize = %d, want 1 (should be cached)", ca.CacheSize())
        }

        // Different domain — should generate new
        ca.SignCertificate("other.com")
        if ca.CacheSize() != 2 {
                t.Errorf("CacheSize = %d, want 2", ca.CacheSize())
        }

        // Clear cache
        ca.ClearCache()
        if ca.CacheSize() != 0 {
                t.Errorf("CacheSize = %d after clear, want 0", ca.CacheSize())
        }
}
