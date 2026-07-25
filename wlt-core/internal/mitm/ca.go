// Package mitm implements the WLT Phase 3 HTTPS MITM Certificate Authority.
//
// The CA is an ECDSA P-256 key pair plus a self-signed root certificate with
// CA:TRUE basic constraints. Per-domain leaf certificates are signed on
// demand (and cached) so the HTTPS proxy can intercept TLS connections
// transparently. The CA cert is exported as PEM so the Android installer
// can drop it into the system/user trust store.
//
// Security notes:
//   - The ECDSA P-256 curve is used (NIST P-256 / prime256v1). It's the
//     modern default for TLS and supported on every Android API level we
//     target (21+).
//   - Leaf certs are valid for 1 year, signed with SHA-256.
//   - The leaf cache is mutex-protected so concurrent connections to the
//     same host reuse the same *tls.Certificate.
package mitm

import (
        "crypto/ecdsa"
        "crypto/elliptic"
        "crypto/rand"
        "crypto/sha256"
        "crypto/tls"
        "crypto/x509"
        "crypto/x509/pkix"
        "encoding/hex"
        "encoding/pem"
        "errors"
        "fmt"
        "math/big"
        "strings"
        "sync"
        "time"
)

// CA is the WLT MITM certificate authority. The zero value is NOT usable;
// always construct via NewCA.
type CA struct {
        mu sync.RWMutex

        // rootKey is the ECDSA P-256 private key for the CA root.
        rootKey *ecdsa.PrivateKey
        // rootCert is the self-signed CA root certificate.
        rootCert *x509.Certificate
        // rootDER is the DER bytes of rootCert (cached for PEM export).
        rootDER []byte

        // serial is the next leaf-cert serial number.
        serial int64

        // cache maps host -> leaf tls.Certificate (signed by the CA).
        cache map[string]*tls.Certificate
}

// NewCA generates a fresh ECDSA P-256 key pair and self-signs a CA root
// certificate with CA:TRUE basic constraints. The cert is valid for 10
// years starting now.
func NewCA() (*CA, error) {
        key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
        if err != nil {
                return nil, fmt.Errorf("mitm: ecdsa generate: %w", err)
        }

        serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
        if err != nil {
                return nil, fmt.Errorf("mitm: serial: %w", err)
        }

        now := time.Now()
        tmpl := &x509.Certificate{
                SerialNumber: serial,
                Subject: pkix.Name{
                        Organization: []string{"WLT-Adblocker"},
                        CommonName:   "WLT-Adblocker Root CA",
                },
                NotBefore:             now.Add(-time.Hour),
                NotAfter:              now.AddDate(10, 0, 0),
                KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign | x509.KeyUsageDigitalSignature,
                BasicConstraintsValid: true,
                IsCA:                  true,
                MaxPathLen:            1,
                MaxPathLenZero:        false,
        }

        der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
        if err != nil {
                return nil, fmt.Errorf("mitm: create root: %w", err)
        }
        cert, err := x509.ParseCertificate(der)
        if err != nil {
                return nil, fmt.Errorf("mitm: parse root: %w", err)
        }

        return &CA{
                rootKey:  key,
                rootCert: cert,
                rootDER:  der,
                serial:   time.Now().UnixNano(),
                cache:    make(map[string]*tls.Certificate),
        }, nil
}

// Sign returns a TLS leaf certificate for host, signed by the CA. The cert
// contains a single DNS SAN equal to host (lowercased, no port) and is
// valid for 1 year. Results are cached so repeat calls for the same host
// return the same *tls.Certificate without re-signing.
func (c *CA) Sign(host string) (*tls.Certificate, error) {
        host = normalizeHost(host)
        if host == "" {
                return nil, errors.New("mitm: empty host")
        }

        c.mu.RLock()
        if leaf, ok := c.cache[host]; ok {
                c.mu.RUnlock()
                return leaf, nil
        }
        c.mu.RUnlock()

        c.mu.Lock()
        defer c.mu.Unlock()
        // Re-check after acquiring the write lock (double-checked locking).
        if leaf, ok := c.cache[host]; ok {
                return leaf, nil
        }

        c.serial++
        serial := big.NewInt(c.serial)
        now := time.Now()

        // SECURITY FIX (C1 from security audit): Generate a FRESH ECDSA P-256
        // keypair for each leaf certificate. Previously the CA root key was
        // reused as the leaf private key, meaning a single compromised TLS
        // session would leak the master CA key and allow forging certs for
        // ANY domain. Now each leaf has its own key — compromising one leaf
        // only affects that one host.
        leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
        if err != nil {
                return nil, fmt.Errorf("mitm: generate leaf key: %w", err)
        }

        tmpl := &x509.Certificate{
                SerialNumber: serial,
                Subject: pkix.Name{
                        Organization: []string{"WLT-Adblocker"},
                        CommonName:   host,
                },
                NotBefore:   now.Add(-time.Hour),
                NotAfter:    now.AddDate(1, 0, 0),
                KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
                ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
                DNSNames:    []string{host},
                BasicConstraintsValid: true,
                IsCA:                  false,
        }

        // Sign the leaf cert with the CA root key, but embed the LEAF's public key.
        leafDER, err := x509.CreateCertificate(rand.Reader, tmpl, c.rootCert, &leafKey.PublicKey, c.rootKey)
        if err != nil {
                return nil, fmt.Errorf("mitm: sign leaf: %w", err)
        }
        leaf := &tls.Certificate{
                Certificate: [][]byte{leafDER, c.rootDER},
                PrivateKey:  leafKey, // leaf's own key, NOT the root key
                Leaf:        mustParse(leafDER),
        }
        c.cache[host] = leaf
        return leaf, nil
}

func mustParse(der []byte) *x509.Certificate {
        c, err := x509.ParseCertificate(der)
        if err != nil {
                return nil
        }
        return c
}

// PEM returns the CA root certificate in PEM form, suitable for writing to
// a .crt file the Android installer can drop into the user trust store.
func (c *CA) PEM() ([]byte, error) {
        c.mu.RLock()
        defer c.mu.RUnlock()
        if len(c.rootDER) == 0 {
                return nil, errors.New("mitm: CA not initialised")
        }
        return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: c.rootDER}), nil
}

// Fingerprint returns the SHA-256 fingerprint of the CA root certificate as
// a lowercase hex string with colon-separated bytes (matches the OpenSSL
// "SHA256 Fingerprint" format).
func (c *CA) Fingerprint() string {
        c.mu.RLock()
        defer c.mu.RUnlock()
        sum := sha256.Sum256(c.rootDER)
        h := hex.EncodeToString(sum[:])
        // "abcdef..." -> "ab:cd:ef:..."
        var b strings.Builder
        for i := 0; i < len(h); i += 2 {
                if i > 0 {
                        b.WriteByte(':')
                }
                b.WriteString(h[i : i+2])
        }
        return b.String()
}

// Regenerate creates a fresh CA key pair and root cert. The leaf cache is
// invalidated; any cached per-domain tls.Certificates are discarded. This
// is the equivalent of "rotate the CA" in the UI.
func (c *CA) Regenerate() error {
        fresh, err := NewCA()
        if err != nil {
                return err
        }
        c.mu.Lock()
        defer c.mu.Unlock()
        c.rootKey = fresh.rootKey
        c.rootCert = fresh.rootCert
        c.rootDER = fresh.rootDER
        c.serial = fresh.serial
        c.cache = make(map[string]*tls.Certificate)
        return nil
}

// RootCert returns the parsed CA root certificate. Read-only.
func (c *CA) RootCert() *x509.Certificate {
        c.mu.RLock()
        defer c.mu.RUnlock()
        return c.rootCert
}

// CacheSize returns the number of leaf certs currently cached. Used by the
// HTTPS proxy stats reporter.
func (c *CA) CacheSize() int {
        c.mu.RLock()
        defer c.mu.RUnlock()
        return len(c.cache)
}

// ClearCache discards all cached leaf certs. The CA root is preserved.
func (c *CA) ClearCache() {
        c.mu.Lock()
        defer c.mu.Unlock()
        c.cache = make(map[string]*tls.Certificate)
}

// normalizeHost lowercases the host and strips any :port suffix.
func normalizeHost(host string) string {
        host = strings.TrimSpace(strings.ToLower(host))
        if i := strings.LastIndex(host, ":"); i > 0 {
                // strip port if it's all digits
                port := host[i+1:]
                isPort := port != ""
                for _, r := range port {
                        if r < '0' || r > '9' {
                                isPort = false
                                break
                        }
                }
                if isPort {
                        host = host[:i]
                }
        }
        // strip surrounding [ ] for IPv6
        host = strings.TrimPrefix(host, "[")
        host = strings.TrimSuffix(host, "]")
        return host
}
