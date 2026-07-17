// Package net implements network-layer inspection helpers:
//   - SNI extraction from TLS ClientHello (Layer 2 in the Smart Cascade)
//   - Hardcoded IP blocklist
//
// SNI extraction works WITHOUT full TLS MITM: we inspect the ClientHello
// plaintext (the first TLS record) to read the SNI extension before encryption
// starts. This lets us block by hostname even when the app pins certificates.
//
// TLS record layout (RFC 5246 §6.2.1):
//   ContentType (1) = 0x16 (Handshake)
//   Version (2)
//   Length (2)
//   Handshake header: HandshakeType (1) = 0x01 (ClientHello), Length (3)
//   ClientHello: Version (2), Random (32), SessionID (1+len), CipherSuites (2+len),
//                CompressionMethods (1+len), Extensions (2+len)
//
// SNI extension: ExtensionType (2) = 0x0000, Length (2),
//                ServerNameListLength (2), [ServerNameType (1)=0, Length (2), Name]
package net

import (
	"errors"
	"strings"
	"sync"
)

// ExtractSNI parses a TCP payload and returns the SNI hostname if it's a TLS
// ClientHello with the SNI extension. Returns "" if not present.
//
// `payload` should be the TCP segment containing the start of the TLS handshake.
func ExtractSNI(payload []byte) (string, error) {
	if len(payload) < 5 {
		return "", errors.New("sni: payload too short")
	}
	// TLS record header
	if payload[0] != 0x16 { // ContentType: Handshake
		return "", errors.New("sni: not a TLS handshake record")
	}
	// payload[1:3] = TLS version (ignored, we read ClientHello version)
	recordLen := int(payload[3])<<8 | int(payload[4])
	if 5+recordLen > len(payload) {
		// record may span multiple TCP segments — we still try to parse what we have
	}
	hs := payload[5:]
	if len(hs) < 4 {
		return "", errors.New("sni: handshake header truncated")
	}
	if hs[0] != 0x01 { // HandshakeType: ClientHello
		return "", errors.New("sni: not a ClientHello")
	}
	// hs[1:4] = handshake length (24-bit)
	ch := hs[4:]
	if len(ch) < 2+32+1 {
		return "", errors.New("sni: ClientHello truncated")
	}
	// ch[0:2] = client version, ch[2:34] = random
	off := 2 + 32
	// Session ID
	if off >= len(ch) {
		return "", errors.New("sni: session id truncated")
	}
	sidLen := int(ch[off])
	off++
	if off+sidLen > len(ch) {
		return "", errors.New("sni: session id data truncated")
	}
	off += sidLen
	// Cipher suites
	if off+2 > len(ch) {
		return "", errors.New("sni: cipher suites length truncated")
	}
	csLen := int(ch[off])<<8 | int(ch[off+1])
	off += 2
	if off+csLen > len(ch) {
		return "", errors.New("sni: cipher suites data truncated")
	}
	off += csLen
	// Compression methods
	if off+1 > len(ch) {
		return "", errors.New("sni: compression methods length truncated")
	}
	cmLen := int(ch[off])
	off++
	if off+cmLen > len(ch) {
		return "", errors.New("sni: compression methods data truncated")
	}
	off += cmLen
	// Extensions
	if off+2 > len(ch) {
		return "", nil // no extensions (rare but valid)
	}
	extLen := int(ch[off])<<8 | int(ch[off+1])
	off += 2
	if off+extLen > len(ch) {
		// extensions may be split across segments; parse what we have
		extLen = len(ch) - off
	}
	extEnd := off + extLen
	for off+4 <= extEnd {
		extType := int(ch[off])<<8 | int(ch[off+1])
		extDataLen := int(ch[off+2])<<8 | int(ch[off+3])
		off += 4
		if off+extDataLen > extEnd {
			break
		}
		if extType == 0x0000 { // SNI extension
			return parseSNIExtension(ch[off : off+extDataLen])
		}
		off += extDataLen
	}
	return "", nil
}

// parseSNIExtension parses the ServerNameList inside extension_data.
func parseSNIExtension(data []byte) (string, error) {
	if len(data) < 2 {
		return "", errors.New("sni: extension data too short")
	}
	listLen := int(data[0])<<8 | int(data[1])
	off := 2
	if off+listLen > len(data) {
		listLen = len(data) - off
	}
	end := off + listLen
	for off+3 <= end {
		nameType := data[off]
		nameLen := int(data[off+1])<<8 | int(data[off+2])
		off += 3
		if off+nameLen > end {
			break
		}
		if nameType == 0 { // host_name type
			return strings.ToLower(string(data[off : off+nameLen])), nil
		}
		off += nameLen
	}
	return "", nil
}

// IPBlocklist is a set of hardcoded ad-server IPs that bypass DNS.
// Used to block SDK connections that connect directly to IPs.
type IPBlocklist struct {
	mu  sync.RWMutex
	ips map[string]struct{}
}

// NewIPBlocklist returns an empty blocklist.
func NewIPBlocklist() *IPBlocklist {
	return &IPBlocklist{ips: make(map[string]struct{})}
}

// Add adds an IP to the blocklist.
func (b *IPBlocklist) Add(ip string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.ips[strings.TrimSpace(ip)] = struct{}{}
}

// AddAll adds multiple IPs.
func (b *IPBlocklist) AddAll(ips []string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, ip := range ips {
		ip = strings.TrimSpace(ip)
		if ip != "" && !strings.HasPrefix(ip, "#") {
			b.ips[ip] = struct{}{}
		}
	}
}

// Contains reports whether an IP is blocked.
func (b *IPBlocklist) Contains(ip string) bool {
	b.mu.RLock()
	defer b.mu.RUnlock()
	_, ok := b.ips[ip]
	return ok
}

// Size returns the number of blocked IPs.
func (b *IPBlocklist) Size() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return len(b.ips)
}

// All returns all blocked IPs (for UI / export).
func (b *IPBlocklist) All() []string {
	b.mu.RLock()
	defer b.mu.RUnlock()
	out := make([]string, 0, len(b.ips))
	for ip := range b.ips {
		out = append(out, ip)
	}
	return out
}
