package bloom

import (
	"fmt"
	"testing"
)

func TestAddContains(t *testing.T) {
	f := New(1000, 0.001)
	items := []string{"apple", "banana", "cherry", "date", "elderberry"}
	for _, it := range items {
		f.Add(it)
	}
	for _, it := range items {
		if !f.Contains(it) {
			t.Errorf("Contains(%q) = false, want true", it)
		}
	}
	// Check some items NOT in the set. With 0.1% FP rate, very unlikely to false-positive.
	// But to be safe, just check a few and allow a tiny FP margin.
	fp := 0
	for _, it := range []string{"xyz", "abc", "notthere", "missing", "absent"} {
		if f.Contains(it) {
			fp++
		}
	}
	if fp > 1 {
		t.Errorf("too many false positives: %d/5", fp)
	}
}

func TestRemove(t *testing.T) {
	f := New(1000, 0.001)
	f.Add("removeme")
	if !f.Contains("removeme") {
		t.Fatal("Add failed")
	}
	if !f.Remove("removeme") {
		t.Error("Remove returned false for existing item")
	}
	if f.Contains("removeme") {
		t.Error("item still present after remove")
	}
	// Remove non-existent should return false (and not corrupt)
	if f.Remove("never-added") {
		t.Error("Remove returned true for non-existent item")
	}
}

func TestFalsePositiveRate(t *testing.T) {
	// With 10000 items and 0.1% target FP, observed FP should be < 2%.
	f := New(10000, 0.001)
	for i := 0; i < 10000; i++ {
		f.Add(fmt.Sprintf("item-%d.com", i))
	}
	fp := 0
	total := 10000
	for i := 0; i < total; i++ {
		if f.Contains(fmt.Sprintf("notadded-%d.com", i)) {
			fp++
		}
	}
	rate := float64(fp) / float64(total)
	if rate > 0.02 {
		t.Errorf("false positive rate = %.4f, want < 0.02", rate)
	}
	t.Logf("observed FP rate: %.4f%% (%d/%d)", rate*100, fp, total)
}

func BenchmarkAdd(b *testing.B) {
	f := New(b.N, 0.001)
	for i := 0; i < b.N; i++ {
		f.Add(itoaB(i) + ".com")
	}
}

func BenchmarkContains(b *testing.B) {
	f := New(10000, 0.001)
	for i := 0; i < 10000; i++ {
		f.Add(itoaB(i) + ".com")
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		f.Contains(itoaB(i%10000) + ".com")
	}
}

func itoaB(n int) string {
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
