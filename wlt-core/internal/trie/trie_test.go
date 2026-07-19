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
	}{
		{"example.com", true},
		{"sub.example.com", true},
		{"ads.example.com", true},
		{"notexample.com", false},
		{"x.tracker.com", true},
		{"a.b.tracker.com", true},
		{"nottracker.com", false},
	}
	for _, tc := range tests {
		got, _ := tr.Contains(tc.domain)
		if got != tc.want {
			t.Errorf("Contains(%q) = %v, want %v", tc.domain, got, tc.want)
		}
	}
}

func TestEdgeCases(t *testing.T) {
	tr := New()
	tr.Insert("")
	tr.Insert(".")
	if tr.Size() != 0 { t.Errorf("empty inserts should not count, size=%d", tr.Size()) }
	got, _ := tr.Contains("anything.com")
	if got { t.Error("empty trie should not match") }
}
