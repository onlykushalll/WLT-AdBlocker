// Package mitm implements TLS Man-in-the-Middle interception for Phase 3.
//
// This is the core of HTTPS filtering:
//   1. Generate a local CA certificate (stored on device, never leaves)
//   2. For each intercepted TLS connection, generate a cert for the requested
//      domain signed by our CA
//   3. Complete the TLS handshake with the client using our cert
//   4. Open a real TLS connection to the destination server
//   5. Relay traffic, inspecting HTTP requests/responses
//
// The CA certificate must be installed by the user in Android's system
// certificate store (Settings > Security > Encryption > Install a certificate).
// Apps that pin certificates will refuse this MITM — they need to be in
// the passthrough list.
package mitm

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"math/big"
	"sync"
	"time"
)

// CertificateAuthority holds the local CA key and certificate.
type CertificateAuthority struct {
	mu       sync.RWMutex
	key      *ecdsa.PrivateKey
	cert     *x509.Certificate
	certDER  []byte
	certPEM  []byte
	// Cache of signed certificates (domain -> cert+key)
	certCache map[string]*SignedCert
}

// SignedCert holds a generated certificate for a specific domain.
type SignedCert struct {
	CertDER []byte
	Key     *ecdsa.PrivateKey
	NotAfter time.Time
}

// NewCA generates a new local CA certificate for MITM.
// The private key never leaves the device.
func NewCA() (*CertificateAuthority, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, errors.New("mitm: failed to generate CA key: " + err.Error())
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, errors.New("mitm: failed to generate serial: " + err.Error())
	}

	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			Organization: []string{"WLT-Adblocker"},
			CommonName:   "WLT Local CA",
		},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0), // 10 years
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            1,
	}

	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		return nil, errors.New("mitm: failed to create CA cert: " + err.Error())
	}

	cert, err := x509.ParseCertificate(certDER)
	if err != nil {
		return nil, errors.New("mitm: failed to parse CA cert: " + err.Error())
	}

	certPEM := pem.EncodeToMemory(&pem.Block{
		Type:  "CERTIFICATE",
		Bytes: certDER,
	})

	return &CertificateAuthority{
		key:       key,
		cert:      cert,
		certDER:   certDER,
		certPEM:   certPEM,
		certCache: make(map[string]*SignedCert),
	}, nil
}

// CAPEM returns the CA certificate in PEM format (for export to user).
func (ca *CertificateAuthority) CAPEM() []byte {
	return ca.certPEM
}

// SignCertificate generates a certificate for [domain] signed by the CA.
// Results are cached — repeated calls for the same domain return the cached cert.
func (ca *CertificateAuthority) SignCertificate(domain string) (*SignedCert, error) {
	ca.mu.RLock()
	if sc, ok := ca.certCache[domain]; ok && time.Now().Before(sc.NotAfter) {
		ca.mu.RUnlock()
		return sc, nil
	}
	ca.mu.RUnlock()

	ca.mu.Lock()
	defer ca.mu.Unlock()

	// Double-check after acquiring write lock
	if sc, ok := ca.certCache[domain]; ok && time.Now().Before(sc.NotAfter) {
		return sc, nil
	}

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, errors.New("mitm: failed to generate cert key: " + err.Error())
	}

	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, errors.New("mitm: failed to generate serial: " + err.Error())
	}

	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{
			Organization: []string{"WLT-Adblocker"},
			CommonName:   domain,
		},
		NotBefore:   time.Now().Add(-time.Hour),
		NotAfter:    time.Now().AddDate(1, 0, 0), // 1 year
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		DNSNames:    []string{domain},
	}

	certDER, err := x509.CreateCertificate(rand.Reader, template, ca.cert, &key.PublicKey, ca.key)
	if err != nil {
		return nil, errors.New("mitm: failed to sign cert: " + err.Error())
	}

	sc := &SignedCert{
		CertDER:  certDER,
		Key:      key,
		NotAfter: template.NotAfter,
	}
	ca.certCache[domain] = sc
	return sc, nil
}

// ClearCache removes all cached certificates.
func (ca *CertificateAuthority) ClearCache() {
	ca.mu.Lock()
	defer ca.mu.Unlock()
	ca.certCache = make(map[string]*SignedCert)
}

// CacheSize returns the number of cached certificates.
func (ca *CertificateAuthority) CacheSize() int {
	ca.mu.RLock()
	defer ca.mu.RUnlock()
	return len(ca.certCache)
}
