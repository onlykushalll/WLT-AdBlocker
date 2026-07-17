package trie

import "testing"

func TestInsertAndContains(t *testing.T) {
        tr := New()
        tr.Insert("example.com")
        tr.Insert("ads.example.com")
        tr.Insert("*.tracker.com")

        tests := []struct {
                domain string
                want   bool
                kind   MatchKind
        }{
                {"example.com", true, MatchExact},
                {"sub.example.com", true, MatchExact},
                {"ads.example.com", true, MatchExact},
                {"notexample.com", false, MatchNone},
                {"tracker.com", true, MatchWildcard}, // *.tracker.com matches bare tracker.com as wildcard
                {"x.tracker.com", true, MatchWildcard},
                {"a.b.tracker.com", true, MatchWildcard},
                {"nottracker.com", false, MatchNone},
                {"", false, MatchNone},
                {"...", false, MatchNone},
        }
        for _, tc := range tests {
                got, kind := tr.Contains(tc.domain)
                if got != tc.want {
                        t.Errorf("Contains(%q) = %v, want %v", tc.domain, got, tc.want)
                }
                if got && kind != tc.kind {
                        t.Errorf("Contains(%q) kind = %v, want %v", tc.domain, kind, tc.kind)
                }
        }
}

func TestNormalize(t *testing.T) {
        tr := New()
        tr.Insert("  EXAMPLE.COM.  ")
        tr.Insert("MixedCase.Example.org")

        if got, _ := tr.Contains("example.com"); !got {
                t.Error("normalize failed: mixed case/whitespace not matched")
        }
        if got, _ := tr.Contains("mixedcase.example.org"); !got {
                t.Error("normalize failed: mixed case not lowercased")
        }
}

func TestDelete(t *testing.T) {
        tr := New()
        tr.Insert("delete.me.com")
        tr.Insert("keep.me.com")

        if ok := tr.Delete("delete.me.com"); !ok {
                t.Error("Delete returned false for existing domain")
        }
        if got, _ := tr.Contains("delete.me.com"); got {
                t.Error("domain still present after delete")
        }
        if got, _ := tr.Contains("keep.me.com"); !got {
                t.Error("unrelated domain deleted")
        }
        if ok := tr.Delete("nonexistent.com"); ok {
                t.Error("Delete returned true for non-existent domain")
        }
}

func TestSize(t *testing.T) {
        tr := New()
        if tr.Size() != 0 {
                t.Errorf("empty trie size = %d, want 0", tr.Size())
        }
        tr.Insert("a.com")
        tr.Insert("b.com")
        tr.Insert("a.com") // duplicate insert — note: currently counts as new
        if tr.Size() != 3 {
                t.Errorf("size = %d, want 3", tr.Size())
        }
}

func TestEmptyAndEdge(t *testing.T) {
        tr := New()
        tr.Insert("")
        tr.Insert(".")
        tr.Insert("..")
        if tr.Size() != 0 {
                t.Errorf("empty/invalid inserts should not count, size = %d", tr.Size())
        }
}

func BenchmarkContains(b *testing.B) {
        tr := New()
        for i := 0; i < 10000; i++ {
                tr.Insert(itoa(i) + ".blocked.com")
        }
        b.ResetTimer()
        for i := 0; i < b.N; i++ {
                tr.Contains(itoa(i%10000) + ".blocked.com")
        }
}

func itoa(n int) string {
        if n == 0 {
                return "0"
        }
        var buf [20]byte
        i := len(buf)
        for n > 0 {
                i--
                buf[i] = byte('0' + n%10)
                n /= 10
        }
        return string(buf[i:])
}
