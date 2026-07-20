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

type CertificateAuthority struct {
	mu sync.RWMutex
	key *ecdsa.PrivateKey
	cert *x509.Certificate
	certDER []byte
	certPEM []byte
	certCache map[string]*SignedCert
}

type SignedCert struct {
	CertDER []byte
	Key *ecdsa.PrivateKey
	NotAfter time.Time
}

func NewCA() (*CertificateAuthority, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil { return nil, errors.New("mitm: " + err.Error()) }
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{Organization: []string{"WLT-Adblocker"}, CommonName: "WLT Local CA"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(10, 0, 0),
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true, IsCA: true, MaxPathLen: 1,
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil { return nil, errors.New("mitm: " + err.Error()) }
	cert, _ := x509.ParseCertificate(certDER)
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certDER})
	return &CertificateAuthority{key: key, cert: cert, certDER: certDER, certPEM: certPEM, certCache: make(map[string]*SignedCert)}, nil
}

func (ca *CertificateAuthority) CAPEM() []byte { return ca.certPEM }

func (ca *CertificateAuthority) SignCertificate(domain string) (*SignedCert, error) {
	ca.mu.RLock()
	if sc, ok := ca.certCache[domain]; ok && time.Now().Before(sc.NotAfter) { ca.mu.RUnlock(); return sc, nil }
	ca.mu.RUnlock()
	ca.mu.Lock(); defer ca.mu.Unlock()
	if sc, ok := ca.certCache[domain]; ok && time.Now().Before(sc.NotAfter) { return sc, nil }
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	template := &x509.Certificate{
		SerialNumber: serial,
		Subject: pkix.Name{Organization: []string{"WLT-Adblocker"}, CommonName: domain},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().AddDate(1, 0, 0),
		KeyUsage: x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames: []string{domain},
	}
	certDER, err := x509.CreateCertificate(rand.Reader, template, ca.cert, &key.PublicKey, ca.key)
	if err != nil { return nil, errors.New("mitm: " + err.Error()) }
	sc := &SignedCert{CertDER: certDER, Key: key, NotAfter: template.NotAfter}
	ca.certCache[domain] = sc
	return sc, nil
}

func (ca *CertificateAuthority) CacheSize() int { ca.mu.RLock(); defer ca.mu.RUnlock(); return len(ca.certCache) }
func (ca *CertificateAuthority) ClearCache() { ca.mu.Lock(); defer ca.mu.Unlock(); ca.certCache = make(map[string]*SignedCert) }
