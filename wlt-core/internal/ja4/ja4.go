// Package ja4 implements TLS ClientHello fingerprinting for ad-SDK detection.
// JA4+ (FoxIO, 2023-2024) identifies ad/tracker SDKs by their TLS stack
// characteristics even when SNI is hidden by ECH (Encrypted Client Hello).
package ja4

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
)

type Fingerprint struct {
	Full       string
	TLSVersion string
	SNI        string
	CipherCount int
	ExtCount    int
	ALPN        string
	CipherHash  string
	ExtHash     string
}

var KnownAdSDKFingerprints = map[string]string{}

func Compute(clientHello []byte) (*Fingerprint, error) {
	if len(clientHello) < 38 {
		return nil, fmt.Errorf("ja4: too short")
	}
	if clientHello[0] != 0x01 {
		return nil, fmt.Errorf("ja4: not ClientHello")
	}

	off := 4 + 2 + 32 // handshake header + version + random

	if off >= len(clientHello) {
		return nil, fmt.Errorf("ja4: truncated at session ID")
	}
	sidLen := int(clientHello[off])
	off += 1 + sidLen

	if off+2 > len(clientHello) {
		return nil, fmt.Errorf("ja4: truncated at cipher suites")
	}
	csLen := int(clientHello[off])<<8 | int(clientHello[off+1])
	off += 2
	if off+csLen > len(clientHello) || csLen%2 != 0 {
		return nil, fmt.Errorf("ja4: invalid cipher suites length")
	}

	var ciphers []uint16
	for i := 0; i < csLen; i += 2 {
		ciphers = append(ciphers, uint16(clientHello[off+i])<<8|uint16(clientHello[off+i+1]))
	}
	off += csLen

	if off+1 > len(clientHello) {
		return nil, fmt.Errorf("ja4: truncated at compression")
	}
	cmLen := int(clientHello[off])
	off += 1 + cmLen

	var extensions []uint16
	var alpn string
	sniPresent := false
	supportedVersion := "t13"

	if off+2 <= len(clientHello) {
		extLen := int(clientHello[off])<<8 | int(clientHello[off+1])
		off += 2
		extEnd := off + extLen
		if extEnd > len(clientHello) {
			extEnd = len(clientHello)
		}
		for off+4 <= extEnd {
			extType := uint16(clientHello[off])<<8 | uint16(clientHello[off+1])
			extDataLen := int(clientHello[off+2])<<8 | int(clientHello[off+3])
			off += 4
			if off+extDataLen > extEnd {
				break
			}
			extensions = append(extensions, extType)
			switch extType {
			case 0x0000:
				sniPresent = true
			case 0x0010:
				alpn = parseALPN(clientHello[off : off+extDataLen])
			case 0x002b:
				if extDataLen >= 3 {
					v := uint16(clientHello[off+1])<<8 | uint16(clientHello[off+2])
					supportedVersion = versionString(v)
				}
			}
			off += extDataLen
		}
	}

	sniChar := "i"
	if sniPresent {
		sniChar = "d"
	}

	sortedCiphers := make([]uint16, len(ciphers))
	copy(sortedCiphers, ciphers)
	sort.Slice(sortedCiphers, func(i, j int) bool { return sortedCiphers[i] < sortedCiphers[j] })

	var filteredExts []uint16
	for _, e := range extensions {
		if e != 0x0000 {
			filteredExts = append(filteredExts, e)
		}
	}

	cipherHash := truncatedHash(cipherBytes(sortedCiphers))
	extHash := truncatedHash(extBytes(filteredExts))

	if alpn == "" {
		alpn = "00"
	}

	full := fmt.Sprintf("%s%s%02d%02d%s_%s_%s",
		supportedVersion, sniChar,
		len(ciphers), len(extensions), alpn,
		cipherHash, extHash)

	return &Fingerprint{
		Full: full, TLSVersion: supportedVersion, SNI: sniChar,
		CipherCount: len(ciphers), ExtCount: len(extensions),
		ALPN: alpn, CipherHash: cipherHash, ExtHash: extHash,
	}, nil
}

func IsKnownAdSDK(fp *Fingerprint) (string, bool) {
	if fp == nil {
		return "", false
	}
	sdk, ok := KnownAdSDKFingerprints[fp.Full]
	return sdk, ok
}

func AddAdSDKFingerprint(ja4hash, sdkName string) {
	KnownAdSDKFingerprints[ja4hash] = sdkName
}

func versionString(v uint16) string {
	switch v {
	case 0x0304:
		return "t13"
	case 0x0303:
		return "t12"
	default:
		return "t13"
	}
}

func parseALPN(data []byte) string {
	if len(data) < 3 {
		return "00"
	}
	off := 2
	if off >= len(data) {
		return "00"
	}
	protoLen := int(data[off])
	off++
	if off+protoLen > len(data) {
		return "00"
	}
	p := string(data[off : off+protoLen])
	if len(p) >= 2 {
		return p[:2]
	}
	return p
}

func cipherBytes(ciphers []uint16) []byte {
	b := make([]byte, len(ciphers)*2)
	for i, c := range ciphers {
		b[i*2] = byte(c >> 8)
		b[i*2+1] = byte(c)
	}
	return b
}

func extBytes(exts []uint16) []byte {
	var b []byte
	for _, e := range exts {
		b = append(b, byte(e>>8), byte(e), ',')
	}
	return b
}

func truncatedHash(data []byte) string {
	h := sha256.Sum256(data)
	return hex.EncodeToString(h[:6])
}

// FingerprintString returns a human-readable description.
func (fp *Fingerprint) String() string {
	return fmt.Sprintf("JA4=%s (TLS %s, SNI=%s, %d ciphers, %d exts, ALPN=%s)",
		fp.Full, fp.TLSVersion, fp.SNI, fp.CipherCount, fp.ExtCount, fp.ALPN)
}

// Import strings to suppress unused import
var _ = strings.ToLower
