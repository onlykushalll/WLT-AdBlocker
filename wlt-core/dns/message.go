package dns

import (
	"encoding/binary"
	"errors"
	"strings"
)

const (
	TypeA uint16 = 1
	TypeCNAME uint16 = 5
	TypeAAAA uint16 = 28
	ClassIN uint16 = 1
	RCODENxDomain uint8 = 3
	RCODENoError uint8 = 0
)

type Header struct {
	ID, Flags, QDCount, ANCount, NSCount, ARCount uint16
}
type Question struct { Name string; Type, Class uint16 }
type Answer struct { Name string; Type, Class uint16; TTL uint32; RData []byte }
type Message struct {
	Header Header
	Questions []Question
	Answers []Answer
	Raw []byte
}

func (h Header) IsQuery() bool { return h.Flags&0x8000 == 0 }
func (h Header) IsResponse() bool { return h.Flags&0x8000 != 0 }
func (h Header) RCODE() uint8 { return uint8(h.Flags&0xF) }

func Parse(data []byte) (*Message, error) {
	if len(data) < 12 { return nil, errors.New("too short") }
	m := &Message{Raw: data}
	m.Header.ID = binary.BigEndian.Uint16(data[0:2])
	m.Header.Flags = binary.BigEndian.Uint16(data[2:4])
	m.Header.QDCount = binary.BigEndian.Uint16(data[4:6])
	m.Header.ANCount = binary.BigEndian.Uint16(data[6:8])
	m.Header.NSCount = binary.BigEndian.Uint16(data[8:10])
	m.Header.ARCount = binary.BigEndian.Uint16(data[10:12])
	off := 12
	for i := 0; i < int(m.Header.QDCount); i++ {
		name, n, err := readName(data, off)
		if err != nil { return nil, err }
		off += n
		if off+4 > len(data) { return nil, errors.New("question truncated") }
		qtype := binary.BigEndian.Uint16(data[off:off+2])
		qclass := binary.BigEndian.Uint16(data[off+2:off+4])
		off += 4
		m.Questions = append(m.Questions, Question{Name: name, Type: qtype, Class: qclass})
	}
	for i := 0; i < int(m.Header.ANCount); i++ {
		name, n, err := readName(data, off)
		if err != nil { return nil, err }
		off += n
		if off+10 > len(data) { return nil, errors.New("answer truncated") }
		atype := binary.BigEndian.Uint16(data[off : off+2])
		aclass := binary.BigEndian.Uint16(data[off+2 : off+4])
		ttl := binary.BigEndian.Uint32(data[off+4 : off+8])
		rdlength := int(binary.BigEndian.Uint16(data[off+8 : off+10]))
		off += 10
		if off+rdlength > len(data) { return nil, errors.New("answer rdata truncated") }
		rdata := make([]byte, rdlength)
		copy(rdata, data[off:off+rdlength])
		off += rdlength
		m.Answers = append(m.Answers, Answer{Name: name, Type: atype, Class: aclass, TTL: ttl, RData: rdata})
	}
	return m, nil
}

func readName(data []byte, off int) (string, int, error) {
	var labels []string
	originalOff := off
	jumped := false
	jumpOff := 0
	for steps := 0; steps < 128; steps++ {
		if off >= len(data) { return "", 0, errors.New("name truncated") }
		b := data[off]
		if b == 0 {
			off++
			if !jumped { jumpOff = off }
			break
		}
		if b&0xC0 == 0xC0 {
			if off+1 >= len(data) { return "", 0, errors.New("compression pointer truncated") }
			ptr := int(binary.BigEndian.Uint16(data[off:off+2]) & 0x3FFF)
			if !jumped { jumpOff = off + 2 }
			off = ptr
			jumped = true
			continue
		}
		labelLen := int(b)
		off++
		if off+labelLen > len(data) { return "", 0, errors.New("label truncated") }
		labels = append(labels, string(data[off:off+labelLen]))
		off += labelLen
	}
	if !jumped { jumpOff = off }
	return strings.Join(labels, "."), jumpOff - originalOff, nil
}

func ExtractQueryDomain(data []byte) (string, error) {
	m, err := Parse(data)
	if err != nil { return "", err }
	if len(m.Questions) == 0 { return "", errors.New("no questions") }
	return strings.ToLower(m.Questions[0].Name), nil
}

func encodeName(name string) []byte {
	var out []byte
	name = strings.TrimSuffix(name, ".")
	if name == "" { return []byte{0} }
	for _, label := range strings.Split(name, ".") {
		if len(label) > 63 { label = label[:63] }
		out = append(out, byte(len(label)))
		out = append(out, []byte(label)...)
	}
	out = append(out, 0)
	return out
}

func BuildNxDomain(query *Message) []byte {
	if query == nil || len(query.Questions) == 0 { return nil }
	var buf []byte
	buf = binary.BigEndian.AppendUint16(buf, query.Header.ID)
	flags := uint16(0x8000) | (query.Header.Flags & 0x7800) | 0x0080 | uint16(RCODENxDomain)
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

// BuildNullIP builds a "successful" DNS response that resolves the queried
// name to a null/sinkhole address (0.0.0.0 for an A query, :: for AAAA).
// Unlike BuildNxDomain, this returns RCODE_NOERROR with a real (if useless)
// answer -- some apps handle a resolved-but-dead address more gracefully
// than an outright NXDOMAIN, retrying less aggressively.
func BuildNullIP(query *Message) []byte {
	if query == nil || len(query.Questions) == 0 { return nil }
	var buf []byte
	buf = binary.BigEndian.AppendUint16(buf, query.Header.ID)
	flags := uint16(0x8000) | (query.Header.Flags & 0x7800) | 0x0080 | uint16(RCODENoError)
	buf = binary.BigEndian.AppendUint16(buf, flags)
	buf = binary.BigEndian.AppendUint16(buf, uint16(len(query.Questions)))
	buf = binary.BigEndian.AppendUint16(buf, uint16(len(query.Questions))) // ANCOUNT: one answer per question
	buf = binary.BigEndian.AppendUint16(buf, 0)
	buf = binary.BigEndian.AppendUint16(buf, 0)
	for _, q := range query.Questions {
		buf = append(buf, encodeName(q.Name)...)
		buf = binary.BigEndian.AppendUint16(buf, q.Type)
		buf = binary.BigEndian.AppendUint16(buf, q.Class)
	}
	for _, q := range query.Questions {
		buf = append(buf, encodeName(q.Name)...)
		answerType := q.Type
		var rdata []byte
		if q.Type == TypeAAAA {
			rdata = make([]byte, 16) // ::
		} else {
			rdata = make([]byte, 4) // 0.0.0.0
			answerType = TypeA
		}
		buf = binary.BigEndian.AppendUint16(buf, answerType)
		buf = binary.BigEndian.AppendUint16(buf, q.Class)
		buf = binary.BigEndian.AppendUint32(buf, 60) // TTL
		buf = binary.BigEndian.AppendUint16(buf, uint16(len(rdata)))
		buf = append(buf, rdata...)
	}
	return buf
}

// ExtractCNAMEs walks a raw DNS response's answer section and returns the
// target of every CNAME record found, for the CNAME-cloaking check in the
// engine (a first-party-looking hostname that's actually an alias to a
// third-party tracker -- see docs/RESEARCH.md). Best-effort: returns
// whatever it collected rather than an error on malformed input, since
// this rides on top of the relay path and is never required for the relay
// itself to function.
func ExtractCNAMEs(data []byte) []string {
	var cnames []string
	if len(data) < 12 { return cnames }
	qdcount := int(binary.BigEndian.Uint16(data[4:6]))
	ancount := int(binary.BigEndian.Uint16(data[6:8]))
	if ancount == 0 { return cnames }

	off := 12
	for i := 0; i < qdcount; i++ {
		_, n, err := readName(data, off)
		if err != nil { return cnames }
		off += n + 4 // + type(2) + class(2)
		if off > len(data) { return cnames }
	}

	for i := 0; i < ancount; i++ {
		_, n, err := readName(data, off)
		if err != nil { return cnames }
		off += n
		if off+10 > len(data) { return cnames }
		rtype := binary.BigEndian.Uint16(data[off : off+2])
		rdlength := int(binary.BigEndian.Uint16(data[off+8 : off+10]))
		rdataOffset := off + 10
		if rdataOffset+rdlength > len(data) { return cnames }
		if rtype == TypeCNAME {
			target, _, err := readName(data, rdataOffset)
			if err == nil {
				cnames = append(cnames, target)
			}
		}
		off = rdataOffset + rdlength
	}
	return cnames
}
