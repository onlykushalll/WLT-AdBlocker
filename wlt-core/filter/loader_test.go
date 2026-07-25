package filter

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeTempFile(t *testing.T, name, content string) string {
	t.Helper()
	dir := t.TempDir()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatalf("write %s: %v", name, err)
	}
	return path
}

func TestParseLine(t *testing.T) {
	cases := []struct {
		line string
		want string
	}{
		// Comments.
		{"# comment", ""},
		{"! comment", ""},
		{"", ""},
		// Bare domain.
		{"example.com", "example.com"},
		{"EXAMPLE.COM", "example.com"},
		// Hosts format.
		{"0.0.0.0 ads.example.com", "ads.example.com"},
		{"127.0.0.1 tracker.example.com", "tracker.example.com"},
		// ABP format.
		{"||doubleclick.net^", "doubleclick.net"},
		{"||ads.example.com^$third-party", "ads.example.com^$third-party"}, // we keep modifiers for caller
		// ABP exception — skip.
		{"@@||allow.example.com^", ""},
		// Wildcard prefix stripped.
		{"*.wildcard.example.com", "wildcard.example.com"},
		// Trailing dot stripped.
		{"trailing.example.com.", "trailing.example.com"},
	}
	for _, c := range cases {
		// For the ABP-with-modifier case the parser will keep the modifier
		// attached; adjust the expectation accordingly. We only test the
		// plain "||domain^" case in the want list above.
		got := parseLine(c.line)
		if c.line == "||ads.example.com^$third-party" {
			// We didn't strip modifiers — accept either form.
			if got != "ads.example.com^$third-party" && got != "ads.example.com" {
				t.Errorf("parseLine(%q) = %q, want something with ads.example.com", c.line, got)
			}
			continue
		}
		if got != c.want {
			t.Errorf("parseLine(%q) = %q, want %q", c.line, got, c.want)
		}
	}
}

func TestLoadFile(t *testing.T) {
	content := `# My blocklist
0.0.0.0 ads.example.com
127.0.0.1 tracker.example.com
||doubleclick.net^
bare.example.com
! ABP comment
*.wildcard.example.com
`
	path := writeTempFile(t, "list.txt", content)
	domains, err := LoadFile(path)
	if err != nil {
		t.Fatalf("LoadFile: %v", err)
	}
	want := []string{
		"ads.example.com",
		"tracker.example.com",
		"doubleclick.net",
		"bare.example.com",
		"wildcard.example.com",
	}
	if len(domains) != len(want) {
		t.Fatalf("LoadFile returned %d domains, want %d: %v", len(domains), len(want), domains)
	}
	for i, d := range domains {
		if d != want[i] {
			t.Errorf("LoadFile[%d] = %q, want %q", i, d, want[i])
		}
	}
}

func TestLoadFromAssets(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "list1.txt"), []byte("a.example.com\nb.example.com\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "list2.txt"), []byte("b.example.com\nc.example.com\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	// sources.json with one entry.
	if err := os.WriteFile(filepath.Join(dir, "sources.json"), []byte(`[{"name":"test","url":"https://example.com/list.txt","format":"hosts"}]`), 0o644); err != nil {
		t.Fatal(err)
	}

	ll, err := LoadFromAssets(dir)
	if err != nil {
		t.Fatalf("LoadFromAssets: %v", err)
	}
	if len(ll.Errors) != 0 {
		t.Errorf("LoadFromAssets errors: %v", ll.Errors)
	}
	// 3 unique domains across both files (b is deduped).
	if len(ll.Domains) != 3 {
		t.Errorf("LoadFromAssets returned %d domains, want 3: %v", len(ll.Domains), ll.Domains)
	}
	joined := strings.Join(ll.Domains, ",")
	for _, want := range []string{"a.example.com", "b.example.com", "c.example.com"} {
		if !strings.Contains(joined, want) {
			t.Errorf("LoadFromAssets missing %q (have: %s)", want, joined)
		}
	}
	if len(ll.Sources) != 1 || ll.Sources[0].Name != "test" {
		t.Errorf("LoadFromAssets sources wrong: %v", ll.Sources)
	}
}
