package dga

import (
        "testing"
)

func TestDGA(t *testing.T) {
        // A typical DGA-style domain: long, random-looking, no vowels to speak of.
        suspicious := "xkqjzvwtmfybcplnru"
        if !IsSuspicious(suspicious) {
                t.Errorf("expected %s to be flagged DGA", suspicious)
        }
        // Random hex-ish
        if !IsSuspicious("z9q8j7k6l5m4n3o2") {
                t.Errorf("expected z9q8j7k6l5m4n3o2 to be flagged DGA")
        }
}

func TestLegitimate(t *testing.T) {
        for _, d := range []string{"google.com", "youtube.com", "github.com", "cloudflare.com", "adblocker.org", "example.com"} {
                if IsSuspicious(d) {
                        t.Errorf("legitimate domain %s flagged as DGA", d)
                }
        }
}

func TestSuspicionScore(t *testing.T) {
        // High score for DGA-style.
        s1 := SuspicionScore("xkqjzvwtmfybcplnru")
        if s1 < 0.5 {
                t.Errorf("expected high score for DGA, got %.2f", s1)
        }
        // Low score for legitimate.
        s2 := SuspicionScore("google.com")
        if s2 > 0.2 {
                t.Errorf("expected low score for google.com, got %.2f", s2)
        }
        // Empty / too-short.
        if s3 := SuspicionScore("abc"); s3 != 0.0 {
                t.Errorf("expected 0.0 for short, got %.2f", s3)
        }
}

func TestExtractFeatures(t *testing.T) {
        f := ExtractFeatures("google.com")
        if f.Length != 6 {
                t.Errorf("Length=%d want 6", f.Length)
        }
        if f.VowelRatio < 0.3 {
                t.Errorf("vowelRatio=%.2f too low for google", f.VowelRatio)
        }
        if f.DigitRatio != 0 {
                t.Errorf("digitRatio=%.2f want 0", f.DigitRatio)
        }
        if f.NgramScore < 0.15 {
                t.Errorf("ngramScore=%.2f too low for google", f.NgramScore)
        }
        // DGA-style
        f2 := ExtractFeatures("xkqjzvwtmfybcplnru.com")
        if f2.Entropy <= 3.5 {
                t.Errorf("entropy=%.2f too low for DGA", f2.Entropy)
        }
        if f2.NgramScore > 0.2 {
                t.Errorf("ngramScore=%.2f too high for DGA", f2.NgramScore)
        }
        // Hyphens — test SLD with hyphens directly.
        f3 := ExtractFeatures("really-long-messy.com")
        if f3.Hyphens < 2 {
                t.Errorf("hyphens=%d want >=2", f3.Hyphens)
        }
}

func TestShannonEntropy(t *testing.T) {
        // "aaaa" has entropy 0 (single symbol).
        if e := shannonEntropy("aaaa"); e != 0 {
                t.Errorf("aaaa entropy=%.2f want 0", e)
        }
        // "ab" has entropy 1 bit.
        if e := shannonEntropy("ab"); e < 0.99 || e > 1.01 {
                t.Errorf("ab entropy=%.2f want 1.0", e)
        }
        // Longer random string should have higher entropy than "aaaa".
        if shannonEntropy("abcdefg") <= shannonEntropy("aaaa") {
                t.Error("abcdefg entropy should exceed aaaa")
        }
}
