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
