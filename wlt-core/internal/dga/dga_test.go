package dga

import "testing"

func TestExtractFeatures(t *testing.T) {
	f := ExtractFeatures("google.com")
	if f.TLD != "com" {
		t.Errorf("TLD = %s, want com", f.TLD)
	}
	if f.Length != 6 {
		t.Errorf("Length = %d, want 6", f.Length)
	}
}

func TestSuspiciousDGA(t *testing.T) {
	// DGA-like domains (high entropy, random)
	suspicious := []string{
		"xkqjfwznpyr.com",
		"asdf1234jkl9876.net",
		"bcdfghjklmnp.org",
	}
	for _, d := range suspicious {
		f := ExtractFeatures(d)
		if !IsSuspicious(f) {
			t.Errorf("DGA domain %s not flagged as suspicious (entropy=%.2f, vowels=%.2f, ngram=%.2f)", d, f.Entropy, f.VowelRatio, f.NgramScore)
		}
	}
}

func TestLegitimateDomains(t *testing.T) {
	legit := []string{
		"google.com",
		"github.com",
		"stackoverflow.com",
		"wikipedia.org",
		"apple.com",
	}
	for _, d := range legit {
		f := ExtractFeatures(d)
		if IsSuspicious(f) {
			t.Errorf("Legitimate domain %s flagged as suspicious (entropy=%.2f)", d, f.Entropy)
		}
	}
}

func TestSuspicionScore(t *testing.T) {
	// DGA domain should have higher score than legitimate
	dgaScore := SuspicionScore(ExtractFeatures("xkqjfwznpyrabc.com"))
	legitScore := SuspicionScore(ExtractFeatures("google.com"))
	if dgaScore <= legitScore {
		t.Errorf("DGA score (%.2f) should be > legit score (%.2f)", dgaScore, legitScore)
	}
}

func TestShannonEntropy(t *testing.T) {
	// "aaaa" has entropy 0 (no randomness)
	if shannonEntropy("aaaa") != 0 {
		t.Error("entropy of 'aaaa' should be 0")
	}
	// "abcd" has entropy 2.0
	e := shannonEntropy("abcd")
	if e < 1.9 || e > 2.1 {
		t.Errorf("entropy of 'abcd' = %.2f, want ~2.0", e)
	}
}
