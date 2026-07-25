package dns

import (
	"net"
	"testing"
)

// buildQuery builds a minimal DNS A-record query for testing.
func buildQuery(name string, id uint16) []byte {
	labels := splitLabels(name)
	q := make([]byte, 0, 12+5+len(name)+2)
	q = append(q, byte(id>>8), byte(id))
	q = append(q, 0x01, 0x00) // flags: standard query, recursion desired
	q = append(q, 0x00, 0x01) // QDCOUNT=1
	q = append(q, 0x00, 0x00) // ANCOUNT=0
	q = append(q, 0x00, 0x00) // NSCOUNT=0
	q = append(q, 0x00, 0x00) // ARCOUNT=0
	for _, l := range labels {
		q = append(q, byte(len(l)))
		q = append(q, []byte(l)...)
	}
	q = append(q, 0) // root label
	q = append(q, 0x00, 0x01) // type A
	q = append(q, 0x00, 0x01) // class IN
	return q
}

func splitLabels(name string) []string {
	if name == "" {
		return nil
	}
	out := []string{}
	cur := ""
	for i := 0; i < len(name); i++ {
		if name[i] == '.' {
			out = append(out, cur)
			cur = ""
		} else {
			cur += string(name[i])
		}
	}
	if cur != "" {
		out = append(out, cur)
	}
	return out
}

// buildQueryWithCNAME builds a DNS response containing a CNAME answer that
// uses a compression pointer to verify our parser follows them.
func buildQueryWithCNAME(name, cname string, id uint16) []byte {
	// Question section first (same as buildQuery).
	q := buildQuery(name, id)
	// Replace ANCOUNT with 1.
	q[6] = 0x00
	q[7] = 0x01
	// Append answer: compression pointer to question name (offset 12).
	ans := []byte{0xC0, 0x0C}
	ans = append(ans, 0x00, 0x05) // type CNAME
	ans = append(ans, 0x00, 0x01) // class IN
	ans = append(ans, 0x00, 0x00, 0x00, 0x3C) // TTL=60
	// Encode cname as labels (no compression).
	cn := []byte{}
	for _, l := range splitLabels(cname) {
		cn = append(cn, byte(len(l)))
		cn = append(cn, []byte(l)...)
	}
	cn = append(cn, 0)
	ans = append(ans, byte(len(cn)>>8), byte(len(cn)))
	ans = append(ans, cn...)
	q = append(q, ans...)
	return q
}

func TestParseQuery(t *testing.T) {
	q := buildQuery("example.com", 0x1234)
	m, err := ParseMessage(q)
	if err != nil {
		t.Fatalf("ParseMessage: %v", err)
	}
	if m.Header.ID != 0x1234 {
		t.Errorf("ID = %x, want 0x1234", m.Header.ID)
	}
	if m.Header.QDCount != 1 {
		t.Errorf("QDCount = %d, want 1", m.Header.QDCount)
	}
	if len(m.Questions) != 1 {
		t.Fatalf("Questions = %d, want 1", len(m.Questions))
	}
	if m.Questions[0].Name != "example.com" {
		t.Errorf("QNAME = %q, want example.com", m.Questions[0].Name)
	}
	if m.Questions[0].Type != TypeA {
		t.Errorf("QTYPE = %d, want %d (TypeA)", m.Questions[0].Type, TypeA)
	}
}

func TestExtractQueryDomain(t *testing.T) {
	q := buildQuery("sub.example.com", 0x0001)
	m, err := ParseMessage(q)
	if err != nil {
		t.Fatalf("ParseMessage: %v", err)
	}
	if d := ExtractQueryDomain(m); d != "sub.example.com" {
		t.Errorf("ExtractQueryDomain = %q, want sub.example.com", d)
	}
	if d := ExtractQueryDomain(nil); d != "" {
		t.Errorf("ExtractQueryDomain(nil) = %q, want empty", d)
	}
}

func TestBuildNxDomain(t *testing.T) {
	q := buildQuery("blocked.example.com", 0x4242)
	resp := BuildNXDOMAIN(q)
	if resp == nil {
		t.Fatalf("BuildNXDOMAIN returned nil")
	}
	m, err := ParseMessage(resp)
	if err != nil {
		t.Fatalf("ParseMessage on response: %v", err)
	}
	if m.Header.ID != 0x4242 {
		t.Errorf("Response ID = %x, want 0x4242", m.Header.ID)
	}
	rcode := m.Header.Flags & 0x000F
	if rcode != RcodeNXDomain {
		t.Errorf("RCODE = %d, want %d (NXDomain)", rcode, RcodeNXDomain)
	}
	// QR bit must be set (response).
	if m.Header.Flags&0x8000 == 0 {
		t.Errorf("QR bit not set in response flags = %x", m.Header.Flags)
	}
	// Question section echoed.
	if len(m.Questions) != 1 || m.Questions[0].Name != "blocked.example.com" {
		t.Errorf("Response question section wrong: %v", m.Questions)
	}
}

func TestBuildNullIP(t *testing.T) {
	q := buildQuery("blocked.example.com", 0x7777)
	resp := BuildNullIP(q)
	if resp == nil {
		t.Fatalf("BuildNullIP returned nil")
	}
	m, err := ParseMessage(resp)
	if err != nil {
		t.Fatalf("ParseMessage on response: %v", err)
	}
	if m.Header.ID != 0x7777 {
		t.Errorf("Response ID = %x, want 0x7777", m.Header.ID)
	}
	if m.Header.Flags&0x000F != RcodeNoError {
		t.Errorf("RCODE = %d, want 0 (NoError)", m.Header.Flags&0x000F)
	}
	if m.Header.ANCount != 1 {
		t.Fatalf("ANCount = %d, want 1", m.Header.ANCount)
	}
	if len(m.Answers) != 1 {
		t.Fatalf("Answers = %d, want 1", len(m.Answers))
	}
	rr := m.Answers[0]
	if rr.Type != TypeA {
		t.Errorf("Answer TYPE = %d, want %d (A)", rr.Type, TypeA)
	}
	if rr.Text != "0.0.0.0" {
		t.Errorf("Answer RDATA = %q, want 0.0.0.0", rr.Text)
	}
	// Verify the answer NAME uses compression pointer 0xC00C — check the
	// raw bytes just past the question section.
	// Question section length = 12 + 1 + len("blocked") + 1 + len("example")
	//   + 1 + len("com") + 1 (root) + 4 (type+class) = 12 + 22 + 4 = 38? Let's
	//   just search for the 0xC00C marker in the response.
	if !containsBytes(resp, []byte{0xC0, 0x0C}) {
		t.Errorf("Response missing compression pointer 0xC00C for answer NAME")
	}
	// Also verify with net.IP for sanity.
	if len(rr.Data) == 4 {
		ip := net.IP(rr.Data)
		if ip.String() != "0.0.0.0" {
			t.Errorf("Parsed IP = %s, want 0.0.0.0", ip.String())
		}
	}
}

func TestExtractCNAMEs(t *testing.T) {
	msg := buildQueryWithCNAME("tracker.example.com", "real-tracker.evil.net", 0x5555)
	m, err := ParseMessage(msg)
	if err != nil {
		t.Fatalf("ParseMessage: %v", err)
	}
	cnames := ExtractCNAMEs(m)
	if len(cnames) != 1 {
		t.Fatalf("ExtractCNAMEs returned %d, want 1", len(cnames))
	}
	if cnames[0] != "real-tracker.evil.net" {
		t.Errorf("CNAME target = %q, want real-tracker.evil.net", cnames[0])
	}
}

func TestBuildRefused(t *testing.T) {
	q := buildQuery("refused.example.com", 0x9999)
	resp := BuildREFUSED(q)
	if resp == nil {
		t.Fatalf("BuildREFUSED returned nil")
	}
	m, err := ParseMessage(resp)
	if err != nil {
		t.Fatalf("ParseMessage on response: %v", err)
	}
	if m.Header.Flags&0x000F != RcodeRefused {
		t.Errorf("RCODE = %d, want %d (Refused)", m.Header.Flags&0x000F, RcodeRefused)
	}
}

func containsBytes(haystack, needle []byte) bool {
	if len(needle) == 0 {
		return true
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j := 0; j < len(needle); j++ {
			if haystack[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}
