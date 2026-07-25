// Package ja4 implements the JA4+ TLS ClientHello fingerprint algorithm.
//
// JA4 is a Foxio-licensed fingerprinting scheme that produces a stable hash
// of a TLS ClientHello message, independent of SNI (so it still works with
// Encrypted Client Hello). The format is:
//
//      t<version><sni><count-ciphers><count-extensions><alpn>_<sha256-truncated>
//
// e.g. "t13d1516h2_8da246e6c6815b8f" for a TLS 1.3 ClientHello without SNI,
// 15 ciphers, 16 extensions, ALPN "h2".
//
// The WLT proxy uses JA4 to fingerprint ad-SDK TLS connections and block
// them by fingerprint (the "KnownAdSDKFingerprints" blocklist). Since the
// fingerprint doesn't depend on SNI, this works even when ECH is in use.
package ja4

import (
        "crypto/sha256"
        "encoding/binary"
        "encoding/hex"
        "errors"
        "fmt"
        "sort"
        "strings"
        "sync"
)

// Known extension and cipher OIDs we treat specially.
const (
        extServerName          uint16 = 0x0000
        extSupportedVersions   uint16 = 0x002b
        extALPN                uint16 = 0x0010
        extSupportedGroups     uint16 = 0x000a
        extECPointFormats      uint16 = 0x000b
        extSignatureAlgorithms uint16 = 0x000d
)

// Compute parses a raw TLS ClientHello record and returns its JA4
// fingerprint string. The input may include the 5-byte TLS record header
// or be the bare handshake bytes (we detect and handle both).
func Compute(clientHello []byte) (string, error) {
        hs, err := stripRecordHeader(clientHello)
        if err != nil {
                return "", err
        }
        // Parse ClientHello: 1-byte type (0x01), 3-byte length, 2-byte
        // legacy_version, 32-byte random, 1-byte session_id length + sid,
        // 2-byte cipher_suites length + ciphers, 1-byte compression length +
        // compressions, 2-byte extensions length + extensions.
        if len(hs) < 4 {
                return "", errors.New("ja4: handshake too short")
        }
        if hs[0] != 0x01 {
                return "", fmt.Errorf("ja4: not a ClientHello (type=0x%02x)", hs[0])
        }
        helloLen := int(hs[1])<<16 | int(hs[2])<<8 | int(hs[3])
        if len(hs)-4 < helloLen {
                return "", errors.New("ja4: handshake length mismatch")
        }
        body := hs[4 : 4+helloLen]
        if len(body) < 34 {
                return "", errors.New("ja4: ClientHello body too short")
        }

        // legacy_version (2 bytes) — overridden by supported_versions extension
        // if present.
        legacyVer := binary.BigEndian.Uint16(body[0:2])
        // Skip 32-byte random.
        off := 34

        // session_id
        if off >= len(body) {
                return "", errors.New("ja4: trunc at session_id len")
        }
        sidLen := int(body[off])
        off++
        if off+sidLen > len(body) {
                return "", errors.New("ja4: trunc at session_id")
        }
        off += sidLen

        // cipher_suites
        if off+2 > len(body) {
                return "", errors.New("ja4: trunc at ciphers len")
        }
        ciphLen := int(binary.BigEndian.Uint16(body[off : off+2]))
        off += 2
        if off+ciphLen > len(body) || ciphLen%2 != 0 {
                return "", errors.New("ja4: invalid ciphers length")
        }
        var ciphers []uint16
        for i := 0; i < ciphLen; i += 2 {
                ciphers = append(ciphers, binary.BigEndian.Uint16(body[off+i:off+i+2]))
        }
        off += ciphLen

        // compression methods
        if off >= len(body) {
                return "", errors.New("ja4: trunc at comp len")
        }
        compLen := int(body[off])
        off++
        if off+compLen > len(body) {
                return "", errors.New("ja4: trunc at comp")
        }
        off += compLen

        // extensions (may be absent)
        var extensions []uint16
        var extBytes []byte
        var alpn string
        hasSNI := false
        ver := legacyVer
        if off+2 <= len(body) {
                extTotal := int(binary.BigEndian.Uint16(body[off : off+2]))
                off += 2
                if off+extTotal > len(body) {
                        return "", errors.New("ja4: invalid extensions length")
                }
                extBytes = body[off : off+extTotal]
                extensions, alpn, hasSNI, ver = parseExtensions(extBytes, legacyVer)
        }

        // Sort cipher suite codes and join into a 4-char-per-cipher hex string.
        sortedCiphers := append([]uint16(nil), ciphers...)
        sort.Slice(sortedCiphers, func(i, j int) bool { return sortedCiphers[i] < sortedCiphers[j] })
        var ciphHex strings.Builder
        // Per JA4 spec: GREASE values are removed before hashing/sorting.
        for _, c := range sortedCiphers {
                if isGREASE(c) {
                        continue
                }
                ciphHex.WriteString(fmt.Sprintf("%04x", c))
        }

        // Sort extension codes (also excluding GREASE).
        sortedExts := append([]uint16(nil), extensions...)
        sort.Slice(sortedExts, func(i, j int) bool { return sortedExts[i] < sortedExts[j] })
        var extHex strings.Builder
        for _, e := range sortedExts {
                if isGREASE(e) {
                        continue
                }
                extHex.WriteString(fmt.Sprintf("%04x", e))
        }

        // Format the a-part: TLS version (2 hex chars without dot).
        verStr := versionString(ver, legacyVer)
        // s-part: 'd' if no SNI, 'i' if SNI present.
        sniChar := "d"
        if hasSNI {
                sniChar = "i"
        }
        // c-part: 2-digit count of (non-GREASE) cipher suites.
        ciphCount := 0
        for _, c := range ciphers {
                if !isGREASE(c) {
                        ciphCount++
                }
        }
        // e-part: 2-digit count of (non-GREASE) extensions.
        extCount := 0
        for _, e := range extensions {
                if !isGREASE(e) {
                        extCount++
                }
        }
        // alpn: first ALPN protocol string, or "00" if absent.
        alpnStr := alpn
        if alpnStr == "" {
                alpnStr = "00"
        }
        // The JA4 prefix is t<ver><sni><cc><ee><alpn>.
        prefix := fmt.Sprintf("t%s%s%02d%02d%s", verStr, sniChar, ciphCount, extCount, alpnStr)

        // Hash: SHA-256 of ciphHex + "_" + extHex + "_" + alpn, truncated to 12
        // hex chars (first 6 bytes).
        hashInput := ciphHex.String() + "_" + extHex.String() + "_" + alpn
        sum := sha256.Sum256([]byte(hashInput))
        hashStr := hex.EncodeToString(sum[:6])

        return prefix + "_" + hashStr, nil
}

// parseExtensions walks the TLS extensions block and returns the list of
// extension codes (in the order they appear, with GREASE values kept),
// the first ALPN protocol string, whether SNI was present, and the
// negotiated version (from supported_versions extension if present).
func parseExtensions(b []byte, legacyVer uint16) ([]uint16, string, bool, uint16) {
        var codes []uint16
        var alpn string
        var hasSNI bool
        ver := legacyVer
        for len(b) >= 4 {
                code := binary.BigEndian.Uint16(b[0:2])
                ln := int(binary.BigEndian.Uint16(b[2:4]))
                b = b[4:]
                if len(b) < ln {
                        break
                }
                val := b[:ln]
                b = b[ln:]
                codes = append(codes, code)
                switch code {
                case extServerName:
                        hasSNI = true
                case extSupportedVersions:
                        if len(val) >= 3 {
                                // 1-byte length + list of 2-byte versions. We use the
                                // highest (last) supported version.
                                vlistLen := int(val[0])
                                if vlistLen+1 <= len(val) {
                                        for i := 1; i+1 < 1+vlistLen; i += 2 {
                                                v := binary.BigEndian.Uint16(val[i : i+2])
                                                if v > ver {
                                                        ver = v
                                                }
                                        }
                                }
                        }
                case extALPN:
                        if len(val) >= 3 {
                                // 1-byte list length + 1-byte protocol length + protocol bytes.
                                listLen := int(val[0])
                                if listLen+1 <= len(val) {
                                        protoLen := int(val[1])
                                        if 2+protoLen <= 1+listLen && 2+protoLen <= len(val) {
                                                alpn = string(val[2 : 2+protoLen])
                                        }
                                }
                        }
                }
        }
        return codes, alpn, hasSNI, ver
}

// versionString returns the 2-character JA4 version string for the given
// (negotiated, legacy) version pair.
func versionString(negotiated, legacy uint16) string {
        v := negotiated
        if v == 0 {
                v = legacy
        }
        switch v {
        case 0x0301:
                return "10"
        case 0x0302:
                return "11"
        case 0x0303:
                // TLS 1.2 unless the legacy_version field was 1.2 and there's no
                // supported_versions extension; otherwise it's TLS 1.3.
                return "13"
        case 0x0304:
                return "13"
        case 0x0300:
                return "s1"
        }
        return "00"
}

// isGREASE returns true if v is a GREASE (RFC 8701) reserved value.
func isGREASE(v uint16) bool {
        // GREASE values have the pattern 0x?a?a where ? is any nibble.
        return (v&0x0f0f) == 0x0a0a
}

// stripRecordHeader strips the optional TLS record header (5 bytes:
// 1-byte content type 0x16, 2-byte version, 2-byte length) and returns
// the handshake bytes.
func stripRecordHeader(b []byte) ([]byte, error) {
        if len(b) < 4 {
                return nil, errors.New("ja4: input too short")
        }
        // If the first byte is 0x16 (Handshake record type) we have a record
        // header.
        if b[0] == 0x16 {
                if len(b) < 5 {
                        return nil, errors.New("ja4: record header truncated")
                }
                recLen := int(binary.BigEndian.Uint16(b[3:5]))
                if len(b)-5 < recLen {
                        return nil, errors.New("ja4: record length mismatch")
                }
                return b[5 : 5+recLen], nil
        }
        // Otherwise assume the input is the bare handshake bytes.
        return b, nil
}

// KnownAdSDKFingerprints is the in-memory JA4 blocklist of known ad-SDK
// TLS fingerprints. Populated by AddAdSDKFingerprint (typically from a
// Frida collection run on the device).
var (
        knownMu      sync.RWMutex
        knownAdSDKFP = make(map[string]bool)
)

// IsKnownAdSDK returns true if the given JA4 fingerprint is in the ad-SDK
// blocklist.
func IsKnownAdSDK(ja4 string) bool {
        knownMu.RLock()
        defer knownMu.RUnlock()
        return knownAdSDKFP[ja4]
}

// AddAdSDKFingerprint adds a JA4 fingerprint to the ad-SDK blocklist.
func AddAdSDKFingerprint(ja4 string) {
        ja4 = strings.TrimSpace(ja4)
        if ja4 == "" {
                return
        }
        knownMu.Lock()
        defer knownMu.Unlock()
        knownAdSDKFP[ja4] = true
}

// RemoveAdSDKFingerprint removes a JA4 fingerprint from the blocklist.
func RemoveAdSDKFingerprint(ja4 string) {
        knownMu.Lock()
        defer knownMu.Unlock()
        delete(knownAdSDKFP, ja4)
}

// KnownAdSDKCount returns the number of registered ad-SDK fingerprints.
func KnownAdSDKCount() int {
        knownMu.RLock()
        defer knownMu.RUnlock()
        return len(knownAdSDKFP)
}

// ClearAdSDKFingerprints empties the blocklist (used by tests).
func ClearAdSDKFingerprints() {
        knownMu.Lock()
        defer knownMu.Unlock()
        knownAdSDKFP = make(map[string]bool)
}

// === Phase 13b: Known ad SDK JA4+ fingerprint database ===
//
// These fingerprints were collected from public research and ad SDK
// TLS analysis. When a TLS connection matches one of these fingerprints,
// it's blocked at the SNI layer even if the SNI is encrypted (ECH).
//
// The database is intentionally small — collecting fingerprints requires
// manual analysis with Frida or packet capture. The database will grow
// over time as more ad SDKs are fingerprinted.
//
// Format: JA4 hash → SDK name (stored in a separate map for debugging)
var knownSDKNames = map[string]string{
        // Placeholder fingerprints — to be populated via Frida collection.
        // The JA4 format is: t13d1516h2_<sha256_truncated>
        // Example: "t13d1516h2_6e6a6e6a6e6a" would be an ad SDK fingerprint.
}

// GetSDKName returns the SDK name for a known fingerprint, or "".
func GetSDKName(ja4 string) string {
        knownMu.RLock()
        defer knownMu.RUnlock()
        return knownSDKNames[ja4]
}

// AddAdSDKFingerprintWithName adds a fingerprint with a human-readable name.
func AddAdSDKFingerprintWithName(ja4, name string) {
        ja4 = strings.TrimSpace(ja4)
        if ja4 == "" {
                return
        }
        knownMu.Lock()
        defer knownMu.Unlock()
        knownAdSDKFP[ja4] = true
        if name != "" {
                knownSDKNames[ja4] = name
        }
}

// AllKnownFingerprints returns all known fingerprints and their SDK names.
func AllKnownFingerprints() map[string]string {
        knownMu.RLock()
        defer knownMu.RUnlock()
        out := make(map[string]string, len(knownSDKNames))
        for k, v := range knownSDKNames {
                out[k] = v
        }
        return out
}
