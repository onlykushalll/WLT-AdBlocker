package httpsproxy

import (
	"strings"
	"testing"

	"github.com/wlt/adblocker/internal/mitm"
)

func TestProxyNew(t *testing.T) {
	ca, err := mitm.NewCA()
	if err != nil {
		t.Fatalf("mitm.NewCA: %v", err)
	}
	p := New(ca)
	if p == nil {
		t.Fatal("nil proxy")
	}
	if p.CosmeticEngine() == nil {
		t.Error("cosmetic engine nil")
	}
	if p.ScriptletEngine() == nil {
		t.Error("scriptlet engine nil")
	}
}

func TestProxyStartStop(t *testing.T) {
	ca, _ := mitm.NewCA()
	p := New(ca)
	if err := p.Start("127.0.0.1:0"); err != nil {
		t.Fatalf("Start: %v", err)
	}
	defer p.Stop()
	if !p.IsRunning() {
		t.Error("not running after Start")
	}
	if err := p.Stop(); err != nil {
		t.Errorf("Stop: %v", err)
	}
	if p.IsRunning() {
		t.Error("running after Stop")
	}
}

func TestStripTrackingParams(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		{"utm_source=foo&keep=1", "keep=1"},
		{"fbclid=abc&gclid=def", ""},
		{"path?a=1&utm_medium=email&b=2", "path?a=1&b=2"},
		{"noequals", "noequals"}, // no key=value pair, untouched
		{"keep=1&mc_eid=x&keep2=2", "keep=1&keep2=2"},
		{"msclkid=zzz", ""},
	}
	for _, c := range cases {
		got := stripTrackingParams(c.in)
		if got != c.want {
			t.Errorf("stripTrackingParams(%q)=%q want %q", c.in, got, c.want)
		}
	}
}

func TestIsM3U(t *testing.T) {
	if !isM3U("application/vnd.apple.mpegurl") {
		t.Error("vnd.apple.mpegurl not detected")
	}
	if !isM3U("application/x-mpegurl") {
		t.Error("x-mpegurl not detected")
	}
	if isM3U("text/html") {
		t.Error("text/html should not be m3u")
	}
}

func TestIsHTML(t *testing.T) {
	if !isHTML("text/html; charset=utf-8") {
		t.Error("text/html not detected")
	}
	if !isHTML("application/xhtml+xml") {
		t.Error("xhtml not detected")
	}
	if isHTML("application/json") {
		t.Error("json should not be html")
	}
}

func TestInjectStyleScript(t *testing.T) {
	html := []byte("<html><head><title>X</title></head><body></body></html>")
	inject := []byte("<style>.ad{display:none}</style>")
	out := injectStyleScript(html, inject)
	s := string(out)
	if !strings.Contains(s, "<style>") {
		t.Errorf("style not injected: %s", s)
	}
	headIdx := strings.Index(s, "<head>")
	styleIdx := strings.Index(s, "<style>")
	if headIdx < 0 || styleIdx < 0 || styleIdx < headIdx {
		t.Errorf("style not after head: head=%d style=%d", headIdx, styleIdx)
	}
}

func TestParseSNI(t *testing.T) {
	// Build a minimal ClientHello with SNI "example.com".
	hello := buildClientHelloWithSNI("example.com")
	if got := parseSNI(hello); got != "example.com" {
		t.Errorf("parseSNI=%q want example.com", got)
	}
	// No SNI extension.
	hello2 := buildClientHelloNoSNI()
	if got := parseSNI(hello2); got != "" {
		t.Errorf("expected empty SNI, got %q", got)
	}
}

// StatsSnapshot sanity check.
func TestStatsSnapshot(t *testing.T) {
	ca, _ := mitm.NewCA()
	p := New(ca)
	p.stats.requests.Add(5)
	p.stats.connections.Add(2)
	snap := p.Stats()
	if snap.Requests != 5 || snap.Connections != 2 {
		t.Errorf("snap=%+v", snap)
	}
}

// Helper: build a 5+handshake byte slice containing a minimal ClientHello
// with an SNI extension for the given host. Returns the full TLS record
// (header + handshake).
func buildClientHelloWithSNI(host string) []byte {
	body := make([]byte, 0, 128)
	// legacy_version
	body = append(body, 0x03, 0x03)
	// 32-byte random
	body = append(body, make([]byte, 32)...)
	// session_id len=0
	body = append(body, 0)
	// cipher_suites len=2 + one cipher
	body = append(body, 0, 2, 0x13, 0x01)
	// compression len=1 + null
	body = append(body, 1, 0)
	// extensions length (placeholder)
	extStart := len(body)
	body = append(body, 0, 0)
	// SNI extension
	hostBytes := []byte(host)
	listLen := 1 + 2 + len(hostBytes)        // type(1) + len(2) + host
	extVal := make([]byte, 0, 2+listLen)
	extVal = append(extVal, byte(listLen>>8), byte(listLen)) // list length
	extVal = append(extVal, 0x00)                             // name_type = host_name
	extVal = append(extVal, byte(len(hostBytes)>>8), byte(len(hostBytes)))
	extVal = append(extVal, hostBytes...)
	body = append(body, 0x00, 0x00, byte(len(extVal)>>8), byte(len(extVal)))
	body = append(body, extVal...)
	// Patch extensions length.
	extTotal := len(body) - extStart - 2
	body[extStart] = byte(extTotal >> 8)
	body[extStart+1] = byte(extTotal)

	// Handshake header
	hs := make([]byte, 4+len(body))
	hs[0] = 0x01
	hs[1] = byte(len(body) >> 16)
	hs[2] = byte(len(body) >> 8)
	hs[3] = byte(len(body))
	copy(hs[4:], body)

	// Record header
	rec := make([]byte, 5+len(hs))
	rec[0] = 0x16
	rec[1] = 0x03
	rec[2] = 0x01
	rec[3] = byte(len(hs) >> 8)
	rec[4] = byte(len(hs))
	copy(rec[5:], hs)
	return rec
}

func buildClientHelloNoSNI() []byte {
	body := make([]byte, 0, 64)
	body = append(body, 0x03, 0x03)
	body = append(body, make([]byte, 32)...)
	body = append(body, 0)
	body = append(body, 0, 2, 0x13, 0x01)
	body = append(body, 1, 0)
	body = append(body, 0, 0) // empty extensions
	hs := make([]byte, 4+len(body))
	hs[0] = 0x01
	hs[1] = byte(len(body) >> 16)
	hs[2] = byte(len(body) >> 8)
	hs[3] = byte(len(body))
	copy(hs[4:], body)
	rec := make([]byte, 5+len(hs))
	rec[0] = 0x16
	rec[1] = 0x03
	rec[2] = 0x01
	rec[3] = byte(len(hs) >> 8)
	rec[4] = byte(len(hs))
	copy(rec[5:], hs)
	return rec
}
