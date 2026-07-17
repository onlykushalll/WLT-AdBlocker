// Package dns implements RFC 1035 DNS message parsing and construction.
//
// Used by the WLT VpnService to intercept DNS queries from apps, decide
// whether to block, and either forward upstream or return NXDOMAIN/0.0.0.0.
//
// Layout reference (RFC 1035 §4.1):
//   Header (12 bytes):
//     ID (2), Flags (2), QDCOUNT (2), ANCOUNT (2), NSCOUNT (2), ARCOUNT (2)
//   Question section: QNAME (variable), QTYPE (2), QCLASS (2)
//   Answer/Authority/Additional sections: NAME, TYPE, CLASS, TTL(4), RDLENGTH(2), RDATA
//
// QNAME is a sequence of labels: [len][label][len][label]...[0]
// Each label length byte's high 2 bits are 0 for normal labels; 11 = compression pointer.
package dns

import (
        "encoding/binary"
        "errors"
        "fmt"
        "strings"
)

// Constants for DNS record types and classes.
const (
        TypeA     uint16 = 1
        TypeNS    uint16 = 2
        TypeCNAME uint16 = 5
        TypeSOA   uint16 = 6
        TypePTR   uint16 = 12
        TypeMX    uint16 = 15
        TypeTXT   uint16 = 16
        TypeAAAA  uint16 = 28
        TypeSRV   uint16 = 33
        TypeOPT   uint16 = 41 // EDNS0
        TypeSVCB  uint16 = 64 // SVCB/HTTPS cloaking detection
        TypeHTTPS uint16 = 65
        TypeANY   uint16 = 255

        ClassIN uint16 = 1
)

// RCODE values (RFC 1035 §6.2.3, RFC 8914 extended).
const (
        RCODENoError  uint8 = 0
        RCODEFormErr  uint8 = 1
        RCODEServFail uint8 = 2
        RCODENxDomain uint8 = 3
        RCODENotImpl  uint8 = 4
        RCODERefused  uint8 = 5
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

// Question is a DNS question section entry.
type Question struct {
        Name  string // decoded, e.g. "example.com"
        Type  uint16
        Class uint16
}

// Answer is a DNS resource record.
type Answer struct {
        Name     string
        Type     uint16
        Class    uint16
        TTL      uint32
        RData    []byte
        // For A records: 4-byte IPv4. For AAAA: 16-byte IPv6. For CNAME: target name.
}

// Message is a parsed DNS message.
type Message struct {
        Header   Header
        Questions []Question
        Answers  []Answer
        // Raw is the original packet bytes (for forwarding).
        Raw []byte
}

// QR flag helpers
func (h Header) IsQuery() bool    { return h.Flags&0x8000 == 0 }
func (h Header) IsResponse() bool { return h.Flags&0x8000 != 0 }
func (h Header) Opcode() uint8    { return uint8((h.Flags>>11)&0xF) }
func (h Header) RCODE() uint8     { return uint8(h.Flags&0xF) }
func (h Header) RD() bool         { return h.Flags&0x0100 != 0 }
func (h Header) RA() bool         { return h.Flags&0x0080 != 0 }

// Parse parses a DNS message from raw bytes.
func Parse(data []byte) (*Message, error) {
        if len(data) < 12 {
                return nil, errors.New("dns: packet too short (<12 bytes header)")
        }
        m := &Message{Raw: data}
        m.Header.ID = binary.BigEndian.Uint16(data[0:2])
        m.Header.Flags = binary.BigEndian.Uint16(data[2:4])
        m.Header.QDCount = binary.BigEndian.Uint16(data[4:6])
        m.Header.ANCount = binary.BigEndian.Uint16(data[6:8])
        m.Header.NSCount = binary.BigEndian.Uint16(data[8:10])
        m.Header.ARCount = binary.BigEndian.Uint16(data[10:12])

        off := 12
        // Parse questions
        for i := 0; i < int(m.Header.QDCount); i++ {
                name, n, err := readName(data, off)
                if err != nil {
                        return nil, fmt.Errorf("dns: question %d name: %w", i, err)
                }
                off += n
                if off+4 > len(data) {
                        return nil, fmt.Errorf("dns: question %d truncated", i)
                }
                qtype := binary.BigEndian.Uint16(data[off : off+2])
                qclass := binary.BigEndian.Uint16(data[off+2 : off+4])
                off += 4
                m.Questions = append(m.Questions, Question{Name: name, Type: qtype, Class: qclass})
        }
        // Parse answers
        for i := 0; i < int(m.Header.ANCount); i++ {
                name, n, err := readName(data, off)
                if err != nil {
                        return nil, fmt.Errorf("dns: answer %d name: %w", i, err)
                }
                off += n
                if off+10 > len(data) {
                        return nil, fmt.Errorf("dns: answer %d truncated", i)
                }
                atype := binary.BigEndian.Uint16(data[off : off+2])
                aclass := binary.BigEndian.Uint16(data[off+2 : off+4])
                ttl := binary.BigEndian.Uint32(data[off+4 : off+8])
                rdlen := binary.BigEndian.Uint16(data[off+8 : off+10])
                off += 10
                if off+int(rdlen) > len(data) {
                        return nil, fmt.Errorf("dns: answer %d rdata overflow", i)
                }
                rdata := make([]byte, rdlen)
                copy(rdata, data[off:off+int(rdlen)])
                off += int(rdlen)
                m.Answers = append(m.Answers, Answer{Name: name, Type: atype, Class: aclass, TTL: ttl, RData: rdata})
        }
        return m, nil
}

// readName decodes a DNS name starting at offset, following compression pointers.
// Returns the name (e.g., "example.com") and the number of bytes consumed in the
// ORIGINAL position (compression pointers don't count toward forward progress).
func readName(data []byte, off int) (string, int, error) {
        var labels []string
        originalOff := off
        jumped := false
        jumpOff := 0
        // Limit pointer following to prevent infinite loops.
        for steps := 0; steps < 128; steps++ {
                if off >= len(data) {
                        return "", 0, errors.New("dns: name truncated")
                }
                b := data[off]
                if b == 0 {
                        off++
                        if !jumped {
                                jumpOff = off
                        }
                        break
                }
                if b&0xC0 == 0xC0 {
                        // Compression pointer (2 bytes)
                        if off+1 >= len(data) {
                                return "", 0, errors.New("dns: compression pointer truncated")
                        }
                        ptr := int(binary.BigEndian.Uint16(data[off:off+2]) & 0x3FFF)
                        if !jumped {
                                jumpOff = off + 2
                        }
                        off = ptr
                        jumped = true
                        continue
                }
                // Normal label
                labelLen := int(b)
                off++
                if off+labelLen > len(data) {
                        return "", 0, errors.New("dns: label truncated")
                }
                labels = append(labels, string(data[off:off+labelLen]))
                off += labelLen
        }
        if !jumped {
                jumpOff = off
        }
        return strings.Join(labels, "."), jumpOff - originalOff, nil
}

// encodeName writes a DNS name (no compression pointers — simple form).
func encodeName(name string) []byte {
        var out []byte
        name = strings.TrimSuffix(name, ".")
        if name == "" {
                return []byte{0}
        }
        for _, label := range strings.Split(name, ".") {
                if len(label) > 63 {
                        label = label[:63]
                }
                out = append(out, byte(len(label)))
                out = append(out, []byte(label)...)
        }
        out = append(out, 0)
        return out
}

// BuildNxDomain builds an NXDOMAIN response for a query.
// This is the standard "blocked" response — apps see "host not found".
func BuildNxDomain(query *Message) []byte {
        if query == nil || len(query.Questions) == 0 {
                return nil
        }
        var buf []byte
        // Header
        buf = binary.BigEndian.AppendUint16(buf, query.Header.ID)
        // Flags: QR=1 (response), OPCODE same, AA=0, TC=0, RD copied, RA=1, RCODE=3 (NXDOMAIN)
        flags := uint16(0x8000) | (query.Header.Flags & 0x7800) | 0x0080 | uint16(RCODENxDomain)
        if query.Header.RD() {
                flags |= 0x0100
        }
        buf = binary.BigEndian.AppendUint16(buf, flags)
        buf = binary.BigEndian.AppendUint16(buf, uint16(len(query.Questions))) // QDCOUNT
        buf = binary.BigEndian.AppendUint16(buf, 0)                            // ANCOUNT
        buf = binary.BigEndian.AppendUint16(buf, 0)                            // NSCOUNT
        buf = binary.BigEndian.AppendUint16(buf, 0)                            // ARCOUNT
        // Questions (echo back)
        for _, q := range query.Questions {
                buf = append(buf, encodeName(q.Name)...)
                buf = binary.BigEndian.AppendUint16(buf, q.Type)
                buf = binary.BigEndian.AppendUint16(buf, q.Class)
        }
        return buf
}

// BuildNullIP builds a response with A record 0.0.0.0 (loopback sinkhole).
// Some apps handle this better than NXDOMAIN (AdAway style).
func BuildNullIP(query *Message) []byte {
        if query == nil || len(query.Questions) == 0 {
                return nil
        }
        q := query.Questions[0]
        // Only build A response for A queries; AAAA for AAAA queries.
        isAAAA := q.Type == TypeAAAA
        var buf []byte
        buf = binary.BigEndian.AppendUint16(buf, query.Header.ID)
        flags := uint16(0x8000) | (query.Header.Flags & 0x7800) | 0x0080 | uint16(RCODENoError)
        if query.Header.RD() {
                flags |= 0x0100
        }
        buf = binary.BigEndian.AppendUint16(buf, flags)
        buf = binary.BigEndian.AppendUint16(buf, 1) // QDCOUNT
        buf = binary.BigEndian.AppendUint16(buf, 1) // ANCOUNT
        buf = binary.BigEndian.AppendUint16(buf, 0)
        buf = binary.BigEndian.AppendUint16(buf, 0)
        buf = append(buf, encodeName(q.Name)...)
        buf = binary.BigEndian.AppendUint16(buf, q.Type)
        buf = binary.BigEndian.AppendUint16(buf, q.Class)
        // Answer section: NAME (compression pointer to question), TYPE, CLASS, TTL, RDLENGTH, RDATA
        // Use a compression pointer 0xC00C (offset 12 = start of question name) to save space.
        buf = append(buf, 0xC0, 0x0C)
        buf = binary.BigEndian.AppendUint16(buf, q.Type)  // same type as query
        buf = binary.BigEndian.AppendUint16(buf, q.Class) // same class
        if isAAAA {
                // 16 bytes of zeros (::)
                buf = binary.BigEndian.AppendUint32(buf, 60) // TTL 60s
                buf = binary.BigEndian.AppendUint16(buf, 16)
                buf = append(buf, make([]byte, 16)...)
        } else {
                // 4 bytes: 0.0.0.0
                buf = binary.BigEndian.AppendUint32(buf, 60) // TTL 60s
                buf = binary.BigEndian.AppendUint16(buf, 4)
                buf = append(buf, 0, 0, 0, 0)
        }
        return buf
}

// BuildRefused builds a REFUSED response (HostShield-style — apps see "not allowed").
func BuildRefused(query *Message) []byte {
        if query == nil || len(query.Questions) == 0 {
                return nil
        }
        var buf []byte
        buf = binary.BigEndian.AppendUint16(buf, query.Header.ID)
        flags := uint16(0x8000) | (query.Header.Flags & 0x7800) | 0x0080 | uint16(RCODERefused)
        if query.Header.RD() {
                flags |= 0x0100
        }
        buf = binary.BigEndian.AppendUint16(buf, flags)
        buf = binary.BigEndian.AppendUint16(buf, uint16(len(query.Questions)))
        buf = binary.BigEndian.AppendUint16(buf, 0)
        buf = binary.BigEndian.AppendUint16(buf, 0)
        buf = binary.BigEndian.AppendUint16(buf, 0)
        for _, q := range query.Questions {
                buf = append(buf, encodeName(q.Name)...)
                buf = binary.BigEndian.AppendUint16(buf, q.Type)
                buf = binary.BigEndian.AppendUint16(buf, q.Class)
        }
        return buf
}

// ExtractQueryDomain returns the domain from the first question, lowercased.
// Helper for the block engine.
func ExtractQueryDomain(data []byte) (string, error) {
        m, err := Parse(data)
        if err != nil {
                return "", err
        }
        if len(m.Questions) == 0 {
                return "", errors.New("dns: no questions")
        }
        return strings.ToLower(m.Questions[0].Name), nil
}

// ExtractCNAMEs returns all CNAME targets from a response's answer section.
// Used for CNAME cloaking detection: legit-looking domain CNAMEs to tracker.
func ExtractCNAMEs(data []byte) []string {
        m, err := Parse(data)
        if err != nil {
                return nil
        }
        var targets []string
        for _, a := range m.Answers {
                if a.Type == TypeCNAME {
                        // RData is a DNS name
                        name, _, err := readName(a.RData, 0)
                        if err == nil && name != "" {
                                targets = append(targets, strings.ToLower(name))
                        }
                }
        }
        return targets
}
