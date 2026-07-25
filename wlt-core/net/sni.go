// Package sni implements TLS ClientHello SNI extraction (Phase 2 of WLT's
// Smart Cascade) plus an IP blocklist with proper CIDR support for
// hardcoded ad-server IPs.
//
// SNI extraction works on raw TCP segments: it parses the TLS record
// header, the handshake header, and the ClientHello to find the
// server_name extension. Split TCP segments are handled by reporting
// ErrTruncated so the caller can wait for more bytes.
package sni

import (
	"errors"
	"net"
	"strings"
	"sync"
)

// Sentinel errors returned by ExtractSNI.
var (
	ErrNotTLS         = errors.New("sni: not a TLS record")
	ErrTruncated      = errors.New("sni: truncated ClientHello, need more data")
	ErrNoSNI          = errors.New("sni: ClientHello has no SNI extension")
	ErrMalformedHello = errors.New("sni: malformed ClientHello")
)

// TLS content type and handshake type constants.
const (
	contentTypeHandshake = 0x16
	handshakeTypeClient  = 0x01
	extensionServerName  = 0x0000
)

// ExtractSNI parses a TLS ClientHello from data and returns the SNI
// hostname. If data does not contain a complete ClientHello it returns
// ErrTruncated so the caller can buffer more bytes.
func ExtractSNI(data []byte) (string, error) {
	// TLS record header: 1 byte content type, 2 bytes version, 2 bytes length.
	if len(data) < 5 {
		return "", ErrTruncated
	}
	if data[0] != contentTypeHandshake {
		return "", ErrNotTLS
	}
	recLen := int(data[3])<<8 | int(data[4])
	if len(data) < 5+recLen {
		return "", ErrTruncated
	}
	body := data[5 : 5+recLen]
	if len(body) < 4 {
		return "", ErrMalformedHello
	}
	// Handshake header: 1 byte type, 3 bytes length.
	if body[0] != handshakeTypeClient {
		return "", ErrNotTLS
	}
	hsLen := int(body[1])<<16 | int(body[2])<<8 | int(body[3])
	if len(body) < 4+hsLen {
		return "", ErrTruncated
	}
	hello := body[4 : 4+hsLen]
	if len(hello) < 2+32+1 {
		return "", ErrMalformedHello
	}
	// ClientHello: 2 bytes version, 32 bytes random, 1 byte session_id
	// length + session_id, 2 bytes cipher_suites length + suites, 1 byte
	// compression_methods length + methods, 2 bytes extensions length +
	// extensions.
	off := 0
	off += 2 // version
	off += 32 // random
	if off >= len(hello) {
		return "", ErrMalformedHello
	}
	sidLen := int(hello[off])
	off++
	if off+sidLen > len(hello) {
		return "", ErrMalformedHello
	}
	off += sidLen
	if off+2 > len(hello) {
		return "", ErrMalformedHello
	}
	csLen := int(hello[off])<<8 | int(hello[off+1])
	off += 2
	if off+csLen > len(hello) {
		return "", ErrMalformedHello
	}
	off += csLen
	if off+1 > len(hello) {
		return "", ErrMalformedHello
	}
	cmLen := int(hello[off])
	off++
	if off+cmLen > len(hello) {
		return "", ErrMalformedHello
	}
	off += cmLen
	if off+2 > len(hello) {
		// No extensions present at all.
		return "", ErrNoSNI
	}
	extTotal := int(hello[off])<<8 | int(hello[off+1])
	off += 2
	if off+extTotal > len(hello) {
		return "", ErrTruncated
	}
	ext := hello[off : off+extTotal]
	for i := 0; i+4 <= len(ext); {
		etype := int(ext[i])<<8 | int(ext[i+1])
		elen := int(ext[i+2])<<8 | int(ext[i+3])
		i += 4
		if i+elen > len(ext) {
			return "", ErrMalformedHello
		}
		if etype == extensionServerName {
			name, err := parseSNIExtension(ext[i : i+elen])
			if err != nil {
				return "", err
			}
			if name == "" {
				return "", ErrNoSNI
			}
			return name, nil
		}
		i += elen
	}
	return "", ErrNoSNI
}

// parseSNIExtension parses the SNI extension body (RFC 6066 section 3).
func parseSNIExtension(body []byte) (string, error) {
	if len(body) < 2 {
		return "", ErrMalformedHello
	}
	listLen := int(body[0])<<8 | int(body[1])
	if 2+listLen > len(body) {
		return "", ErrMalformedHello
	}
	list := body[2 : 2+listLen]
	for i := 0; i+3 <= len(list); {
		nameType := list[i]
		nameLen := int(list[i+1])<<8 | int(list[i+2])
		i += 3
		if i+nameLen > len(list) {
			return "", ErrMalformedHello
		}
		// name_type 0 = host_name; we ignore other types.
		if nameType == 0 {
			return string(list[i : i+nameLen]), nil
		}
		i += nameLen
	}
	return "", nil
}

// IPBlocklist is a thread-safe IP blocklist with CIDR support. Used to
// match hardcoded ad-server IPs that some game SDKs embed.
type IPBlocklist struct {
	mu      sync.RWMutex
	cidrs   []*net.IPNet
	exact   map[string]bool
}

// NewIPBlocklist returns an empty IPBlocklist.
func NewIPBlocklist() *IPBlocklist {
	return &IPBlocklist{exact: make(map[string]bool)}
}

// AddIP parses s as either a CIDR (e.g. "1.2.3.0/24") or a single IP and
// adds it to the blocklist.
func (b *IPBlocklist) AddIP(s string) error {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil
	}
	if strings.Contains(s, "/") {
		_, ipnet, err := net.ParseCIDR(s)
		if err != nil {
			return err
		}
		b.mu.Lock()
		b.cidrs = append(b.cidrs, ipnet)
		b.mu.Unlock()
		return nil
	}
	ip := net.ParseIP(s)
	if ip == nil {
		return errors.New("sni: invalid IP: " + s)
	}
	b.mu.Lock()
	b.exact[ip.String()] = true
	b.mu.Unlock()
	return nil
}

// Contains reports whether ip (string form) is in the blocklist.
func (b *IPBlocklist) Contains(ip string) bool {
	parsed := net.ParseIP(ip)
	if parsed == nil {
		return false
	}
	b.mu.RLock()
	defer b.mu.RUnlock()
	if b.exact[parsed.String()] {
		return true
	}
	for _, n := range b.cidrs {
		if n.Contains(parsed) {
			return true
		}
	}
	return false
}

// Size returns the total number of entries (CIDRs + exact IPs).
func (b *IPBlocklist) Size() int {
	b.mu.RLock()
	defer b.mu.RUnlock()
	return len(b.cidrs) + len(b.exact)
}

// LoadDefault loads a small curated set of well-known ad-server IPs and
// CIDRs used by major game ad SDKs. In production these would be loaded
// from a bundled asset file (wlt-game-ips.txt).
func (b *IPBlocklist) LoadDefault() {
	defaults := []string{
		// Google ad-serving ranges.
		"142.250.0.0/15",
		"172.217.0.0/16",
		// Facebook / Meta.
		"31.13.0.0/16",
		// Unity Ads.
		"23.235.32.0/20",
		// CloudFront-backed ad networks.
		"13.32.0.0/16",
		"54.230.0.0/16",
		// AppLovin.
		"72.52.4.0/24",
		// AdColony.
		"173.205.0.0/16",
	}
	for _, c := range defaults {
		_ = b.AddIP(c)
	}
}
