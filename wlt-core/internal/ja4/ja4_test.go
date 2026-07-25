package ja4

import (
	"encoding/binary"
	"strings"
	"testing"
)

// buildClientHello constructs a minimal TLS 1.3 ClientHello for testing.
// It uses the given SNI flag, cipher list, extension list, and ALPN. The
// returned bytes include the 5-byte TLS record header.
func buildClientHello(hasSNI bool, ciphers []uint16, exts []uint16, alpn string) []byte {
	// Build the body (legacy_version + random + session_id + ciphers + comp + exts).
	body := make([]byte, 0, 256)
	// legacy_version = TLS 1.2 (0x0303) — TLS 1.3 uses supported_versions ext
	body = append(body, 0x03, 0x03)
	// 32-byte random
	body = append(body, make([]byte, 32)...)
	// session_id length = 0
	body = append(body, 0)
	// cipher_suites length + ciphers
	clen := len(ciphers) * 2
	body = append(body, byte(clen>>8), byte(clen))
	for _, c := range ciphers {
		body = append(body, byte(c>>8), byte(c))
	}
	// compression methods length + 1 method (null)
	body = append(body, 1, 0)

	// Extensions
	var extBuf []byte
	for _, e := range exts {
		extBuf = append(extBuf, byte(e>>8), byte(e), 0, 0) // 0-length extension
	}
	if hasSNI {
		// SNI extension with one host_name entry "example.com".
		host := []byte("example.com")
		// SNI list length (2 bytes) + 1 byte type + 2 byte length + host
		sniListLen := 1 + 2 + len(host)
		sniVal := []byte{byte(sniListLen >> 8), byte(sniListLen), 0x00, byte(len(host) >> 8), byte(len(host))}
		sniVal = append(sniVal, host...)
		extBuf = append(extBuf, 0x00, 0x00, byte(len(sniVal)>>8), byte(len(sniVal)))
		extBuf = append(extBuf, sniVal...)
	}
	// supported_versions extension (TLS 1.3 = 0x0304)
	svVal := []byte{2, 0x03, 0x04} // 1 byte list length, then 0x0304
	extBuf = append(extBuf, 0x00, 0x2b, byte(len(svVal)>>8), byte(len(svVal)))
	extBuf = append(extBuf, svVal...)
	// ALPN extension
	if alpn != "" {
		alpnBytes := []byte(alpn)
		// 1 byte list length + 1 byte proto length + proto
		listLen := 1 + len(alpnBytes)
		alpnVal := []byte{byte(listLen), byte(len(alpnBytes))}
		alpnVal = append(alpnVal, alpnBytes...)
		extBuf = append(extBuf, 0x00, 0x10, byte(len(alpnVal)>>8), byte(len(alpnVal)))
		extBuf = append(extBuf, alpnVal...)
	}

	// Extensions length prefix
	body = append(body, byte(len(extBuf)>>8), byte(len(extBuf)))
	body = append(body, extBuf...)

	// Handshake header: type=0x01, 3-byte length.
	hs := make([]byte, 4+len(body))
	hs[0] = 0x01
	hsLen := uint32(len(body))
	hs[1] = byte(hsLen >> 16)
	hs[2] = byte(hsLen >> 8)
	hs[3] = byte(hsLen)
	copy(hs[4:], body)

	// Record header: 0x16 (Handshake), 0x0301 (legacy record version), 2-byte length.
	rec := make([]byte, 5+len(hs))
	rec[0] = 0x16
	rec[1] = 0x03
	rec[2] = 0x01
	binary.BigEndian.PutUint16(rec[3:5], uint16(len(hs)))
	copy(rec[5:], hs)
	return rec
}

func TestCompute(t *testing.T) {
	// Build a minimal ClientHello: 1 cipher, 3 extensions, ALPN "h2", no SNI.
	hello := buildClientHello(false, []uint16{0x1301}, []uint16{0x000a, 0x000d}, "h2")
	fp, err := Compute(hello)
	if err != nil {
		t.Fatalf("Compute: %v", err)
	}
	t.Logf("fingerprint: %s", fp)
	// Verify format: t<ver><sni><cc><ee><alpn>_<12 hex chars>
	parts := strings.Split(fp, "_")
	if len(parts) != 2 {
		t.Fatalf("expected prefix_hash, got %s", fp)
	}
	prefix, hash := parts[0], parts[1]
	if len(prefix) < 10 {
		t.Fatalf("prefix too short: %s", prefix)
	}
	if !strings.HasPrefix(prefix, "t") {
		t.Errorf("prefix missing t: %s", prefix)
	}
	if !strings.HasPrefix(prefix, "t13") {
		t.Errorf("expected t13 (TLS 1.3): %s", prefix)
	}
	if prefix[3] != 'd' {
		t.Errorf("expected 'd' for no-SNI, got %c: %s", prefix[3], prefix)
	}
	if len(hash) != 12 {
		t.Errorf("expected 12-char hash, got %d: %s", len(hash), hash)
	}
	if !strings.Contains(prefix, "h2") {
		t.Errorf("expected h2 ALPN: %s", prefix)
	}
}

func TestComputeWithSNI(t *testing.T) {
	hello := buildClientHello(true, []uint16{0x1301}, []uint16{0x000a, 0x000d}, "h2")
	fp, err := Compute(hello)
	if err != nil {
		t.Fatalf("Compute: %v", err)
	}
	if !strings.HasPrefix(fp, "t13i") {
		t.Errorf("expected t13i prefix (SNI present), got %s", fp)
	}
}

func TestKnownAdSDK(t *testing.T) {
	ClearAdSDKFingerprints()
	if IsKnownAdSDK("t13d1516h2_abc123def456") {
		t.Error("should not be known before add")
	}
	AddAdSDKFingerprint("t13d1516h2_abc123def456")
	if !IsKnownAdSDK("t13d1516h2_abc123def456") {
		t.Error("should be known after add")
	}
	if KnownAdSDKCount() != 1 {
		t.Errorf("count=%d want 1", KnownAdSDKCount())
	}
	RemoveAdSDKFingerprint("t13d1516h2_abc123def456")
	if IsKnownAdSDK("t13d1516h2_abc123def456") {
		t.Error("should not be known after remove")
	}
	ClearAdSDKFingerprints()
}

func TestComputeBareHandshake(t *testing.T) {
	// Pass the handshake bytes WITHOUT the 5-byte record header.
	hello := buildClientHello(false, []uint16{0x1301}, []uint16{}, "h2")
	bare := hello[5:]
	fp, err := Compute(bare)
	if err != nil {
		t.Fatalf("Compute bare: %v", err)
	}
	if !strings.HasPrefix(fp, "t13d") {
		t.Errorf("expected t13d prefix: %s", fp)
	}
}

func TestComputeInvalid(t *testing.T) {
	if _, err := Compute([]byte{0x00, 0x01}); err == nil {
		t.Error("expected error on too-short input")
	}
	if _, err := Compute([]byte{0x16, 0x03, 0x01, 0x00, 0x01, 0x02}); err == nil {
		t.Error("expected error on bad handshake type")
	}
}
