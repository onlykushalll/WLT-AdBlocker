package trie

import (
        "fmt"
        "testing"
)

func TestInsertAndContains(t *testing.T) {
        tr := New()
        tr.Insert("example.com")
        tr.Insert("ads.doubleclick.net")

        cases := []struct {
                domain string
                want   bool
        }{
                {"example.com", true},
                {"sub.example.com", true},
                {"a.b.c.example.com", true},
                {"EXAMPLE.COM", true}, // case-insensitive
                {"example.com.", true}, // trailing dot stripped
                {"notexample.com", false},
                {"different.org", false},
                {"ads.doubleclick.net", true},
                {"sub.ads.doubleclick.net", true},
                {"", false},
        }
        for _, c := range cases {
                if got := tr.Contains(c.domain); got != c.want {
                        t.Errorf("Contains(%q) = %v, want %v", c.domain, got, c.want)
                }
        }
}

func TestNormalize(t *testing.T) {
        cases := []struct {
                in, want string
        }{
                {"Example.COM", "example.com"},
                {"example.com.", "example.com"},
                {"*.example.com", "example.com"},
                {"  Foo.Bar  ", "foo.bar"},
                {"", ""},
        }
        for _, c := range cases {
                if got := Normalize(c.in); got != c.want {
                        t.Errorf("Normalize(%q) = %q, want %q", c.in, got, c.want)
                }
        }
}

func TestWildcard(t *testing.T) {
        tr := New()
        tr.Insert("*.example.com")

        // "*.example.com" matches strict subdomains but NOT example.com itself.
        cases := []struct {
                domain string
                want   bool
        }{
                {"sub.example.com", true},
                {"a.b.example.com", true},
                {"example.com", false}, // wildcard does not match parent
                {"other.com", false},
        }
        for _, c := range cases {
                if got := tr.Contains(c.domain); got != c.want {
                        t.Errorf("Contains(%q) = %v, want %v", c.domain, got, c.want)
                }
        }

        // Combining wildcard with non-wildcard rule on same parent domain.
        tr.Insert("example.com")
        if !tr.Contains("example.com") {
                t.Errorf("Contains(example.com) after adding non-wildcard rule = false, want true")
        }
        if !tr.Contains("sub.example.com") {
                t.Errorf("Contains(sub.example.com) = false, want true")
        }
}

func TestDelete(t *testing.T) {
        tr := New()
        tr.Insert("example.com")
        tr.Insert("other.org")

        if got := tr.Size(); got != 2 {
                t.Fatalf("Size after insert = %d, want 2", got)
        }
        if !tr.Delete("other.org") {
                t.Errorf("Delete(other.org) = false, want true")
        }
        if tr.Contains("other.org") {
                t.Errorf("Contains(other.org) after delete = true, want false")
        }
        // Deleting one rule must NOT affect an unrelated rule.
        if !tr.Contains("example.com") {
                t.Errorf("Contains(example.com) after deleting other.org = false, want true (unaffected)")
        }
        // Deleting again should fail.
        if tr.Delete("other.org") {
                t.Errorf("Delete(other.org) twice = true, want false")
        }
        if tr.Delete("never-inserted.com") {
                t.Errorf("Delete(never-inserted) = true, want false")
        }
        if got := tr.Size(); got != 1 {
                t.Errorf("Size after delete = %d, want 1", got)
        }

        // Verify subdomain suffix matching survives deletion of a sub-rule:
        // if both "example.com" and "sub.example.com" are inserted, deleting
        // "sub.example.com" must NOT remove the parent suffix rule, so the
        // subdomain still matches via suffix.
        tr.Insert("sub.example.com")
        if !tr.Contains("sub.example.com") {
                t.Fatalf("Contains(sub.example.com) before delete = false, want true")
        }
        if !tr.Delete("sub.example.com") {
                t.Fatalf("Delete(sub.example.com) = false, want true")
        }
        // The parent rule "example.com" is still active, so sub.example.com
        // STILL matches as a suffix of example.com.
        if !tr.Contains("sub.example.com") {
                t.Errorf("Contains(sub.example.com) = false after deleting sub-rule, want true (parent suffix rule intact)")
        }
        // But Contains(example.com) obviously still matches.
        if !tr.Contains("example.com") {
                t.Errorf("Contains(example.com) = false after sub-rule deletion, want true")
        }
}

func TestSize(t *testing.T) {
        tr := New()
        if got := tr.Size(); got != 0 {
                t.Fatalf("Size on empty trie = %d, want 0", got)
        }
        tr.Insert("a.com")
        tr.Insert("a.com") // duplicate
        if got := tr.Size(); got != 1 {
                t.Errorf("Size after duplicate insert = %d, want 1", got)
        }
        tr.Insert("*.a.com") // wildcard distinct from non-wildcard
        if got := tr.Size(); got != 2 {
                t.Errorf("Size after wildcard insert = %d, want 2", got)
        }
}

func TestEmptyAndEdge(t *testing.T) {
        tr := New()
        tr.Insert("")
        tr.Insert(".")
        if got := tr.Size(); got != 0 {
                t.Errorf("Size after empty inserts = %d, want 0", got)
        }
        if tr.Contains("") {
                t.Errorf("Contains('') = true, want false")
        }
        // Single-label domain.
        tr.Insert("localhost")
        if !tr.Contains("localhost") {
                t.Errorf("Contains(localhost) = false, want true")
        }
}

func BenchmarkContains(b *testing.B) {
        tr := New()
        for i := 0; i < 10000; i++ {
                tr.Insert(fmt.Sprintf("domain%d.example.com", i))
        }
        b.ResetTimer()
        for i := 0; i < b.N; i++ {
                _ = tr.Contains("sub.domain9999.example.com")
        }
}
