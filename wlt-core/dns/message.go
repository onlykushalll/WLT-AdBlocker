// Package dns implements an RFC 1035 DNS message parser and three
// response builders used by the WLT VPN service:
//
//   - BuildNXDOMAIN: returns a "name error" (RCODE=3) response so the
//     client app treats the domain as non-existent.
//   - BuildNullIP: returns an A record with address 0.0.0.0 so the client
//     app silently fails to connect.
//   - BuildREFUSED: returns a "refused" (RCODE=5) response so the client
//     app treats the resolver as unwilling to answer.
//
// The parser also exposes ExtractQueryDomain and ExtractCNAMEs helpers
// used by the CNAME-cloaking detection layer.
package dns

import (
        "errors"
        "fmt"
        "strings"
)

// DNS record types we care about.
const (
        TypeA     uint16 = 1
        TypeNS    uint16 = 2
        TypeCNAME uint16 = 5
        TypePTR   uint16 = 12
        TypeMX    uint16 = 15
        TypeTXT   uint16 = 16
        TypeAAAA  uint16 = 28
        TypeSRV   uint16 = 33
        TypeOPT   uint16 = 41
        TypeANY   uint16 = 255

        ClassIN uint16 = 1
)

// Response codes (RCODE).
const (
        RcodeNoError  = 0
        RcodeFormErr  = 1
        RcodeServFail = 2
        RcodeNXDomain = 3
        RcodeNotImpl  = 4
        RcodeRefused  = 5
)

// Header is the 12-byte DNS message header.
type Header struct {
        ID      uint16
        Flags   uint16
        QDCount uint16
        ANCount uint16
        NSCount uint16
        ARCount uint16
}

// Question is a single DNS question section entry.
type Question struct {
        Name  string
        Type  uint16
        Class uint16
}

// ResourceRecord is a single DNS answer/nameserver/additional record.
type ResourceRecord struct {
        Name  string
        Type  uint16
        Class uint16
        TTL   uint32
        Data  []byte
        // For CNAME/A/AAAA records we also expose the parsed text form for
        // convenience.
        Text string
}

// Message is a parsed DNS message.
type Message struct {
        Header     Header
        Questions  []Question
        Answers    []ResourceRecord
        Authority  []ResourceRecord
        Additional []ResourceRecord
        Raw        []byte // the wire data we parsed from
}

// ParseMessage parses a DNS wire-format message.
func ParseMessage(data []byte) (*Message, error) {
        if len(data) < 12 {
                return nil, errors.New("dns: message too short for header")
        }
        m := &Message{Raw: data}
        m.Header.ID = uint16(data[0])<<8 | uint16(data[1])
        m.Header.Flags = uint16(data[2])<<8 | uint16(data[3])
        m.Header.QDCount = uint16(data[4])<<8 | uint16(data[5])
        m.Header.ANCount = uint16(data[6])<<8 | uint16(data[7])
        m.Header.NSCount = uint16(data[8])<<8 | uint16(data[9])
        m.Header.ARCount = uint16(data[10])<<8 | uint16(data[11])

        off := 12
        var err error
        for i := 0; i < int(m.Header.QDCount); i++ {
                var q Question
                q.Name, off, err = readName(data, off)
                if err != nil {
                        return nil, fmt.Errorf("dns: question name: %w", err)
                }
                if off+4 > len(data) {
                        return nil, errors.New("dns: truncated question")
                }
                q.Type = uint16(data[off])<<8 | uint16(data[off+1])
                q.Class = uint16(data[off+2])<<8 | uint16(data[off+3])
                off += 4
                m.Questions = append(m.Questions, q)
        }

        parseRRs := func(n int, dst *[]ResourceRecord) error {
                for i := 0; i < n; i++ {
                        var rr ResourceRecord
                        rr.Name, off, err = readName(data, off)
                        if err != nil {
                                return fmt.Errorf("dns: rr name: %w", err)
                        }
                        if off+10 > len(data) {
                                return errors.New("dns: truncated rr header")
                        }
                        rr.Type = uint16(data[off])<<8 | uint16(data[off+1])
                        rr.Class = uint16(data[off+2])<<8 | uint16(data[off+3])
                        rr.TTL = uint32(data[off+4])<<24 | uint32(data[off+5])<<16 | uint32(data[off+6])<<8 | uint32(data[off+7])
                        rdlen := int(uint16(data[off+8])<<8 | uint16(data[off+9]))
                        off += 10
                        if off+rdlen > len(data) {
                                return errors.New("dns: truncated rr data")
                        }
                        rr.Data = data[off : off+rdlen]
                        rr.Text = rrText(rr, data, off)
                        off += rdlen
                        *dst = append(*dst, rr)
                }
                return nil
        }
        if err := parseRRs(int(m.Header.ANCount), &m.Answers); err != nil {
                return nil, err
        }
        if err := parseRRs(int(m.Header.NSCount), &m.Authority); err != nil {
                return nil, err
        }
        if err := parseRRs(int(m.Header.ARCount), &m.Additional); err != nil {
                return nil, err
        }
        return m, nil
}

// rrText produces a human-readable representation of an RR's rdata for the
// record types we care about (A, AAAA, CNAME, PTR). For other types the
// field is left empty.
func rrText(rr ResourceRecord, data []byte, rdataOff int) string {
        switch rr.Type {
        case TypeA:
                if len(rr.Data) == 4 {
                        return fmt.Sprintf("%d.%d.%d.%d", rr.Data[0], rr.Data[1], rr.Data[2], rr.Data[3])
                }
        case TypeAAAA:
                if len(rr.Data) == 16 {
                        // Compact IPv6 string — just emit joined hex groups.
                        parts := make([]string, 8)
                        for i := 0; i < 8; i++ {
                                parts[i] = fmt.Sprintf("%x", uint16(rr.Data[2*i])<<8|uint16(rr.Data[2*i+1]))
                        }
                        return strings.Join(parts, ":")
                }
        case TypeCNAME, TypePTR:
                // These store a domain name that may use compression pointers
                // relative to the whole message — read it from the original buffer.
                name, _, err := readName(data, rdataOff)
                if err == nil {
                        return name
                }
        }
        return ""
}

// readName reads a DNS-encoded domain name starting at off, following
// compression pointers. Returns the decoded dotted name and the offset of
// the next byte after the name (NOT after following any pointer).
func readName(data []byte, off int) (string, int, error) {
        if off >= len(data) {
                return "", off, errors.New("dns: name offset out of range")
        }
        var labels []string
        originalOff := off
        jumped := false
        nextOff := off
        for {
                if off >= len(data) {
                        return "", originalOff, errors.New("dns: name truncated")
                }
                b := data[off]
                if b == 0 {
                        off++
                        if !jumped {
                                nextOff = off
                        }
                        break
                }
                switch b & 0xC0 {
                case 0x00:
                        // Label of length b.
                        labelLen := int(b)
                        off++
                        if off+labelLen > len(data) {
                                return "", originalOff, errors.New("dns: label out of range")
                        }
                        labels = append(labels, string(data[off:off+labelLen]))
                        off += labelLen
                        if !jumped {
                                nextOff = off
                        }
                case 0xC0:
                        // Compression pointer.
                        if off+1 >= len(data) {
                                return "", originalOff, errors.New("dns: compression pointer truncated")
                        }
                        ptr := int(uint16(b&0x3F)<<8 | uint16(data[off+1]))
                        if !jumped {
                                nextOff = off + 2
                        }
                        off = ptr
                        jumped = true
                default:
                        return "", originalOff, fmt.Errorf("dns: unknown label type 0x%02x", b)
                }
                // Guard against pointer loops.
                if len(labels) > 128 {
                        return "", originalOff, errors.New("dns: name too long (pointer loop?)")
                }
        }
        return strings.Join(labels, "."), nextOff, nil
}

// ExtractQueryDomain returns the QNAME of the first question, or "" if the
// message has no questions or is malformed.
func ExtractQueryDomain(msg *Message) string {
        if msg == nil || len(msg.Questions) == 0 {
                return ""
        }
        return msg.Questions[0].Name
}

// ExtractCNAMEs returns the target of every CNAME record in the answer
// section. Compression pointers are resolved by the parser. Useful for
// CNAME-cloaking detection: a benign-looking domain that CNAMEs to a known
// tracker is itself a tracker.
func ExtractCNAMEs(msg *Message) []string {
        if msg == nil {
                return nil
        }
        var out []string
        for _, rr := range msg.Answers {
                if rr.Type == TypeCNAME && rr.Text != "" {
                        out = append(out, rr.Text)
                }
        }
        return out
}

// BuildNXDOMAIN builds a NXDOMAIN (RCODE=3) response to the given query.
// It echoes the question section verbatim.
func BuildNXDOMAIN(query []byte) []byte {
        return buildSimpleResponse(query, RcodeNXDomain, nil)
}

// BuildNullIP builds a response with a single A record (0.0.0.0) for the
// queried domain. The answer NAME is encoded as a compression pointer
// (0xC00C) pointing at byte offset 12 (the question NAME) — required by
// all compliant DNS clients.
func BuildNullIP(query []byte) []byte {
        answer := make([]byte, 16)
        // Compression pointer to offset 12 (the question NAME).
        answer[0] = 0xC0
        answer[1] = 0x0C
        // Type A, Class IN
        answer[2] = 0x00
        answer[3] = 0x01 // A
        answer[4] = 0x00
        answer[5] = 0x01 // IN
        // TTL = 60 (4 bytes big-endian)
        answer[6] = 0x00
        answer[7] = 0x00
        answer[8] = 0x00
        answer[9] = 0x3C
        // RDLENGTH = 4
        answer[10] = 0x00
        answer[11] = 0x04
        // RDATA = 0.0.0.0
        answer[12] = 0x00
        answer[13] = 0x00
        answer[14] = 0x00
        answer[15] = 0x00
        return buildSimpleResponse(query, RcodeNoError, answer)
}

// BuildNullIPv6 builds a response with a single AAAA record (::) for the
// query's domain. This is the IPv6 equivalent of BuildNullIP — the client
// gets a valid response with an unusable address, preventing retries.
// Phase 6: IPv6 support for DNS64/NAT64 networks.
func BuildNullIPv6(query []byte) []byte {
        // Answer: NAME(2) + TYPE(2) + CLASS(2) + TTL(4) + RDLENGTH(2) + RDATA(16)
        answer := make([]byte, 28)
        // NAME = compression pointer to offset 12 (question name)
        answer[0] = 0xC0
        answer[1] = 0x0C
        // TYPE = AAAA (28)
        answer[2] = 0x00
        answer[3] = 0x1C
        // CLASS = IN (1)
        answer[4] = 0x00
        answer[5] = 0x01
        // TTL = 300 (5 minutes)
        answer[6] = 0x00
        answer[7] = 0x00
        answer[8] = 0x01
        answer[9] = 0x2C
        // RDLENGTH = 16 (IPv6 address)
        answer[10] = 0x00
        answer[11] = 0x10
        // RDATA = :: (all zeros, 16 bytes)
        // Already zero-initialized by make()
        return buildSimpleResponse(query, RcodeNoError, answer)
}

// BuildNODATA builds a NODATA response (NOERROR with empty answer).
// This tells the client the domain exists but has no records of the
// requested type. Phase 6: AdGuard-style NODATA response.
func BuildNODATA(query []byte) []byte {
        return buildSimpleResponse(query, RcodeNoError, nil)
}

// BuildREFUSED builds a REFUSED (RCODE=5) response to the given query.
func BuildREFUSED(query []byte) []byte {
        return buildSimpleResponse(query, RcodeRefused, nil)
}

// buildSimpleResponse constructs a DNS response with the given rcode and an
// optional pre-built answer section. The query's question section is
// copied verbatim into the response.
func buildSimpleResponse(query []byte, rcode int, answer []byte) []byte {
        if len(query) < 12 {
                return nil
        }
        // Determine the length of the question section so we can echo it.
        qdCount := int(uint16(query[4])<<8 | uint16(query[5]))
        off := 12
        for i := 0; i < qdCount; i++ {
                _, next, err := readName(query, off)
                if err != nil {
                        return nil
                }
                off = next
                // Skip type+class (4 bytes).
                off += 4
                if off > len(query) {
                        return nil
                }
        }
        qsection := query[12:off]

        resp := make([]byte, 0, 12+len(qsection)+len(answer))
        // Header: copy ID, set flags.
        id := query[0:2]
        resp = append(resp, id...)
        // Flags: QR=1, Opcode copied, AA=0, TC=0, RD copied, RA=1, Z=0,
        // RCODE = given.
        flags := uint16(query[2])<<8 | uint16(query[3])
        qr := uint16(0x8000)
        opcode := flags & 0x7800
        rd := flags & 0x0100
        respFlags := qr | opcode | rd | 0x0080 | uint16(rcode&0x000F)
        resp = append(resp, byte(respFlags>>8), byte(respFlags))
        // QDCOUNT = original qdCount
        resp = append(resp, byte(qdCount>>8), byte(qdCount))
        // ANCOUNT = len(answer) > 0 ? 1 : 0
        anCount := uint16(0)
        if len(answer) > 0 {
                anCount = 1
        }
        resp = append(resp, byte(anCount>>8), byte(anCount))
        // NSCOUNT = 0, ARCOUNT = 0
        resp = append(resp, 0, 0, 0, 0)
        // Question section.
        resp = append(resp, qsection...)
        // Answer section.
        resp = append(resp, answer...)
        return resp
}
