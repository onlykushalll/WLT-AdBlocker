package dns

import "testing"

func TestParseQuery(t *testing.T) {
	// Build a minimal DNS query for "example.com" type A
	q := buildQuery("example.com", TypeA, 0x1234)
	m, err := Parse(q)
	if err != nil {
		t.Fatalf("Parse failed: %v", err)
	}
	if m.Header.ID != 0x1234 {
		t.Errorf("ID = %d, want 0x1234", m.Header.ID)
	}
	if !m.Header.IsQuery() {
		t.Error("should be a query")
	}
	if len(m.Questions) != 1 {
		t.Fatalf("questions = %d, want 1", len(m.Questions))
	}
	if m.Questions[0].Name != "example.com" {
		t.Errorf("question name = %q, want example.com", m.Questions[0].Name)
	}
	if m.Questions[0].Type != TypeA {
		t.Errorf("question type = %d, want %d", m.Questions[0].Type, TypeA)
	}
}

func TestExtractQueryDomain(t *testing.T) {
	q := buildQuery("ads.doubleclick.net", TypeA, 0x0001)
	d, err := ExtractQueryDomain(q)
	if err != nil {
		t.Fatal(err)
	}
	if d != "ads.doubleclick.net" {
		t.Errorf("got %q, want ads.doubleclick.net", d)
	}
}

func TestBuildNxDomain(t *testing.T) {
	q := buildQuery("blocked.com", TypeA, 0xABCD)
	resp := BuildNxDomain(mustParse(q))
	if len(resp) == 0 {
		t.Fatal("empty NXDOMAIN response")
	}
	m, err := Parse(resp)
	if err != nil {
		t.Fatalf("parse NXDOMAIN response: %v", err)
	}
	if !m.Header.IsResponse() {
		t.Error("NXDOMAIN should be a response")
	}
	if m.Header.RCODE() != RCODENxDomain {
		t.Errorf("RCODE = %d, want %d (NXDOMAIN)", m.Header.RCODE(), RCODENxDomain)
	}
	if m.Header.ID != 0xABCD {
		t.Errorf("ID mismatch: %d vs %d", m.Header.ID, 0xABCD)
	}
	if len(m.Questions) != 1 {
		t.Errorf("questions = %d, want 1", len(m.Questions))
	}
}

func TestBuildNullIP(t *testing.T) {
	q := buildQuery("blocked.com", TypeA, 0x0001)
	resp := BuildNullIP(mustParse(q))
	if len(resp) == 0 {
		t.Fatal("empty NullIP response")
	}
	m, err := Parse(resp)
	if err != nil {
		t.Fatalf("parse NullIP: %v", err)
	}
	if m.Header.RCODE() != RCODENoError {
		t.Errorf("RCODE = %d, want NOERROR", m.Header.RCODE())
	}
	if len(m.Answers) != 1 {
		t.Fatalf("answers = %d, want 1", len(m.Answers))
	}
	if m.Answers[0].Type != TypeA {
		t.Errorf("answer type = %d, want A", m.Answers[0].Type)
	}
	if len(m.Answers[0].RData) != 4 {
		t.Errorf("RData len = %d, want 4", len(m.Answers[0].RData))
	}
	for i, b := range m.Answers[0].RData {
		if b != 0 {
			t.Errorf("RData[%d] = %d, want 0 (null IP)", i, b)
		}
	}
}

func TestExtractCNAMEs(t *testing.T) {
	// Build a response with a CNAME answer
	q := buildQuery("legit.example.com", TypeA, 0x0001)
	resp := buildResponseWithCNAME(q, "legit.example.com", "tracker.evil.com")
	cnames := ExtractCNAMEs(resp)
	if len(cnames) != 1 {
		t.Fatalf("cnames = %d, want 1", len(cnames))
	}
	if cnames[0] != "tracker.evil.com" {
		t.Errorf("cname = %q, want tracker.evil.com", cnames[0])
	}
}

// buildQuery builds a DNS query packet for testing.
func buildQuery(name string, qtype uint16, id uint16) []byte {
	var buf []byte
	buf = appendUint16(buf, id)
	buf = appendUint16(buf, 0x0100) // RD=1
	buf = appendUint16(buf, 1)      // QDCOUNT
	buf = appendUint16(buf, 0)
	buf = appendUint16(buf, 0)
	buf = appendUint16(buf, 0)
	buf = append(buf, encodeNameForTest(name)...)
	buf = appendUint16(buf, qtype)
	buf = appendUint16(buf, ClassIN)
	return buf
}

func buildResponseWithCNAME(query []byte, qname, cname string) []byte {
	var buf []byte
	buf = appendUint16(buf, 0x0001) // ID
	buf = appendUint16(buf, 0x8180) // response, RD, RA
	buf = appendUint16(buf, 1)      // QDCOUNT
	buf = appendUint16(buf, 1)      // ANCOUNT
	buf = appendUint16(buf, 0)
	buf = appendUint16(buf, 0)
	// Question
	buf = append(buf, encodeNameForTest(qname)...)
	buf = appendUint16(buf, TypeA)
	buf = appendUint16(buf, ClassIN)
	// Answer: CNAME
	buf = append(buf, encodeNameForTest(qname)...)
	buf = appendUint16(buf, TypeCNAME)
	buf = appendUint16(buf, ClassIN)
	buf = appendUint32(buf, 300) // TTL
	cnameBytes := encodeNameForTest(cname)
	buf = appendUint16(buf, uint16(len(cnameBytes)))
	buf = append(buf, cnameBytes...)
	return buf
}

func encodeNameForTest(name string) []byte {
	var out []byte
	for _, label := range splitForTest(name) {
		out = append(out, byte(len(label)))
		out = append(out, []byte(label)...)
	}
	out = append(out, 0)
	return out
}

func splitForTest(name string) []string {
	var out []string
	cur := ""
	for _, c := range name {
		if c == '.' {
			if cur != "" {
				out = append(out, cur)
				cur = ""
			}
		} else {
			cur += string(c)
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

func appendUint16(buf []byte, v uint16) []byte {
	return append(buf, byte(v>>8), byte(v))
}

func appendUint32(buf []byte, v uint32) []byte {
	return append(buf, byte(v>>24), byte(v>>16), byte(v>>8), byte(v))
}

func mustParse(data []byte) *Message {
	m, err := Parse(data)
	if err != nil {
		panic(err)
	}
	return m
}
