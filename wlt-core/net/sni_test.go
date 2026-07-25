package sni

import (
        "errors"
        "testing"
)

// buildClientHello constructs a minimal TLS 1.2 ClientHello with the given
// SNI hostname. This is sufficient to exercise ExtractSNI.
func buildClientHello(host string) []byte {
        // Build the SNI extension body first: list_length(2) + name_type(1) +
        // name_length(2) + name.
        sniName := []byte(host)
        listLen := 1 + 2 + len(sniName) // name_type(1) + name_length(2) + name
        body := []byte{byte(listLen >> 8), byte(listLen & 0xFF), 0x00} // list length (2 bytes) + name_type=0
        body = append(body, byte(len(sniName)>>8), byte(len(sniName)&0xFF))
        body = append(body, sniName...)
        // SNI extension: type(2)=0x0000 + length(2) + body.
        sniExt := []byte{0x00, 0x00, byte(len(body) >> 8), byte(len(body) & 0xFF)}
        sniExt = append(sniExt, body...)

        // Extensions length + extensions.
        extLen := len(sniExt)
        exts := []byte{byte(extLen >> 8), byte(extLen)}
        exts = append(exts, sniExt...)

        // ClientHello body: version(2) + random(32) + session_id(1+0) +
        // cipher_suites(2+2) + compression_methods(1+1) + extensions.
        hello := make([]byte, 0, 2+32+1+4+2+len(exts))
        hello = append(hello, 0x03, 0x03) // TLS 1.2
        hello = append(hello, make([]byte, 32)...) // zero random
        hello = append(hello, 0x00) // session_id length 0
        hello = append(hello, 0x00, 0x02, 0x00, 0x01) // cipher_suites: TLS_RSA_WITH_AES_CBC_SHA
        hello = append(hello, 0x01, 0x00) // compression_methods: null
        hello = append(hello, exts...)

        // Handshake header: type=ClientHello, 3-byte length.
        hs := []byte{handshakeTypeClient}
        hs = append(hs, byte(len(hello)>>16), byte(len(hello)>>8), byte(len(hello)))
        hs = append(hs, hello...)

        // TLS record header.
        rec := []byte{contentTypeHandshake, 0x03, 0x03}
        rec = append(rec, byte(len(hs)>>8), byte(len(hs)))
        rec = append(rec, hs...)
        return rec
}

func TestExtractSNI(t *testing.T) {
        cases := []string{
                "example.com",
                "sub.example.com",
                "adserver.doubleclick.net",
        }
        for _, host := range cases {
                data := buildClientHello(host)
                got, err := ExtractSNI(data)
                if err != nil {
                        t.Errorf("ExtractSNI(%q) error: %v", host, err)
                        continue
                }
                if got != host {
                        t.Errorf("ExtractSNI(%q) = %q, want %q", host, got, host)
                }
        }
}

func TestExtractSNINotTLS(t *testing.T) {
        data := []byte{0x00, 0x00, 0x00, 0x00, 0x00}
        if _, err := ExtractSNI(data); !errors.Is(err, ErrNotTLS) {
                t.Errorf("ExtractSNI on non-TLS data = %v, want ErrNotTLS", err)
        }
}

func TestExtractSNITruncated(t *testing.T) {
        full := buildClientHello("example.com")
        // First 5 bytes is the TLS record header. Truncate to 3 bytes.
        if _, err := ExtractSNI(full[:3]); !errors.Is(err, ErrTruncated) {
                t.Errorf("ExtractSNI on 3-byte data = %v, want ErrTruncated", err)
        }
        // Truncate inside the record body (after header but before all bytes).
        if _, err := ExtractSNI(full[:6]); err == nil {
                t.Errorf("ExtractSNI on 6-byte data = nil, want error")
        }
}

func TestIPBlocklist(t *testing.T) {
        b := NewIPBlocklist()
        if err := b.AddIP("1.2.3.4"); err != nil {
                t.Fatalf("AddIP(exact): %v", err)
        }
        if err := b.AddIP("10.0.0.0/8"); err != nil {
                t.Fatalf("AddIP(cidr): %v", err)
        }
        if !b.Contains("1.2.3.4") {
                t.Errorf("Contains(1.2.3.4) = false, want true")
        }
        if !b.Contains("10.1.2.3") {
                t.Errorf("Contains(10.1.2.3) = false, want true (CIDR)")
        }
        if b.Contains("8.8.8.8") {
                t.Errorf("Contains(8.8.8.8) = true, want false")
        }
        if b.Size() != 2 {
                t.Errorf("Size = %d, want 2", b.Size())
        }
}

func TestIPBlocklistLoadDefault(t *testing.T) {
        b := NewIPBlocklist()
        b.LoadDefault()
        // 142.250.x.x is Google ad-serving — should be blocked.
        if !b.Contains("142.250.1.1") {
                t.Errorf("Contains(142.250.1.1) = false after LoadDefault, want true")
        }
        // 8.8.8.8 should not be in the default list.
        if b.Contains("8.8.8.8") {
                t.Errorf("Contains(8.8.8.8) = true, want false")
        }
        if b.Size() == 0 {
                t.Errorf("Size = 0 after LoadDefault, want > 0")
        }
}
