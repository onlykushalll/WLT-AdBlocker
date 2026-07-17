package net

import "testing"

// Build a minimal TLS ClientHello with SNI extension for testing.
// This mirrors the byte layout parsed by ExtractSNI.
func buildClientHelloWithSNI(hostname string) []byte {
	// SNI extension: type 0x0000, length, list length, [name type 0, length, name]
	hostBytes := []byte(hostname)
	sniListLen := 1 + 2 + len(hostBytes) // name type (1) + name length (2) + name
	sniExtLen := 2 + sniListLen          // list length (2) + list
	sniExt := []byte{0x00, 0x00}         // type
	sniExt = append(sniExt, byte(sniExtLen>>8), byte(sniExtLen))
	sniExt = append(sniExt, byte(sniListLen>>8), byte(sniListLen))
	sniExt = append(sniExt, 0x00) // name type = host_name
	sniExt = append(sniExt, byte(len(hostBytes)>>8), byte(len(hostBytes)))
	sniExt = append(sniExt, hostBytes...)

	// ClientHello body:
	// version (2) + random (32) + session_id (1+0) + cipher_suites (2+2) + compression (1+1) + extensions (2 + ext)
	ch := []byte{0x03, 0x03} // version TLS 1.2
	ch = append(ch, make([]byte, 32)...) // random
	ch = append(ch, 0x00)                // session id length = 0
	ch = append(ch, 0x00, 0x02, 0x00, 0xFF) // cipher suites length=2, one suite
	ch = append(ch, 0x01, 0x00)          // compression methods length=1, null
	ch = append(ch, byte(len(sniExt)>>8), byte(len(sniExt))) // extensions length
	ch = append(ch, sniExt...)

	// Handshake header: type=ClientHello (0x01), length (3 bytes)
	hs := []byte{0x01}
	hs = append(hs, byte(len(ch)>>16), byte(len(ch)>>8), byte(len(ch)))
	hs = append(hs, ch...)

	// TLS record: type=Handshake (0x16), version (2), length (2), handshake
	rec := []byte{0x16, 0x03, 0x01}
	rec = append(rec, byte(len(hs)>>8), byte(len(hs)))
	rec = append(rec, hs...)
	return rec
}

func TestExtractSNI(t *testing.T) {
	tests := []string{
		"example.com",
		"ads.doubleclick.net",
		"sub.domain.example.org",
	}
	for _, hostname := range tests {
		payload := buildClientHelloWithSNI(hostname)
		got, err := ExtractSNI(payload)
		if err != nil {
			t.Errorf("ExtractSNI(%q) error: %v", hostname, err)
			continue
		}
		if got != hostname {
			t.Errorf("ExtractSNI(%q) = %q, want %q", hostname, got, hostname)
		}
	}
}

func TestExtractSNINotTLS(t *testing.T) {
	payload := []byte{0x00, 0x01, 0x02, 0x03}
	_, err := ExtractSNI(payload)
	if err == nil {
		t.Error("expected error for non-TLS payload")
	}
}

func TestExtractSNITruncated(t *testing.T) {
	payload := []byte{0x16, 0x03, 0x01, 0x00, 0x05, 0x01, 0x00, 0x00, 0x01}
	_, err := ExtractSNI(payload)
	if err == nil {
		t.Error("expected error for truncated ClientHello")
	}
}

func TestIPBlocklist(t *testing.T) {
	b := NewIPBlocklist()
	b.Add("1.2.3.4")
	b.Add("5.6.7.8")
	b.AddAll([]string{"9.10.11.12", "# comment", "  ", "13.14.15.16"})

	if !b.Contains("1.2.3.4") {
		t.Error("missing 1.2.3.4")
	}
	if !b.Contains("13.14.15.16") {
		t.Error("missing 13.14.15.16")
	}
	if b.Contains("99.99.99.99") {
		t.Error("false positive on 99.99.99.99")
	}
	if b.Size() != 4 {
		t.Errorf("size = %d, want 4", b.Size())
	}
}
