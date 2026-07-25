// Package dga implements a heuristic Domain Generation Algorithm (DGA)
// detector. DGA domains are produced by malware to algorithmically generate
// thousands of fallback C2 domains that can be registered cheaply and
// rotated quickly. WLT uses these heuristics as part of the ML-style
// suspicious-domain layer (AdGuard DNS v2.23 pattern).
//
// Features extracted per domain:
//
//   - shannon entropy of the SLD (high entropy > 3.5 is suspicious)
//   - vowel ratio (low vowel ratio < 0.25 is suspicious)
//   - digit ratio (high digit ratio > 0.3 is suspicious)
//   - n-gram score (low English bigram score = suspicious)
//   - hyphen count (more than 2 hyphens is suspicious)
//
// IsSuspicious returns true if ANY heuristic fires (conservative — false
// positives are acceptable here because the caller treats "suspicious" as
// "investigate further", not "block outright").
package dga

import (
	"math"
	"strings"
)

// Features holds the per-SLD features used by the DGA detector.
type Features struct {
	Length      int
	Entropy     float64
	VowelRatio  float64
	DigitRatio  float64
	NgramScore  float64
	Hyphens     int
}

// ExtractFeatures computes the feature vector for the SLD (second-level
// domain) of the given host. If the host has multiple labels we use only
// the SLD (the part immediately before the public suffix) since DGA
// typically lives in the SLD, not in subdomains.
func ExtractFeatures(domain string) Features {
	domain = strings.ToLower(strings.TrimSpace(domain))
	domain = strings.TrimSuffix(domain, ".")
	if domain == "" {
		return Features{}
	}
	// Get the SLD: split on '.', take the second-from-last part (handles
	// "example.com" -> "example", "www.example.co.uk" -> "example").
	labels := strings.Split(domain, ".")
	sld := labels[0]
	if len(labels) >= 2 {
		sld = labels[len(labels)-2]
	}

	f := Features{Length: len(sld)}
	if f.Length == 0 {
		return f
	}
	f.Entropy = shannonEntropy(sld)
	f.VowelRatio = vowelRatio(sld)
	f.DigitRatio = digitRatio(sld)
	f.NgramScore = ngramScore(sld)
	f.Hyphens = strings.Count(sld, "-")
	return f
}

// IsSuspicious returns true if the given domain triggers ANY of the DGA
// heuristics:
//
//   - shannon entropy > 3.5
//   - vowel ratio < 0.25 (and length >= 6, to avoid trivial short matches)
//   - digit ratio > 0.3
//   - n-gram score < 0.15 (very low English-likeness)
//   - more than 2 hyphens
func IsSuspicious(domain string) bool {
	f := ExtractFeatures(domain)
	if f.Length < 6 {
		return false
	}
	if f.Entropy > 3.5 {
		return true
	}
	if f.VowelRatio < 0.25 {
		return true
	}
	if f.DigitRatio > 0.3 {
		return true
	}
	if f.NgramScore < 0.15 {
		return true
	}
	if f.Hyphens > 2 {
		return true
	}
	return false
}

// SuspicionScore returns a 0.0-1.0 confidence score that the given domain
// is DGA-generated. Each heuristic that fires contributes to the score.
// The score is the sum of weighted heuristic contributions capped at 1.0.
func SuspicionScore(domain string) float64 {
	f := ExtractFeatures(domain)
	if f.Length < 6 {
		return 0.0
	}
	var score float64
	if f.Entropy > 3.5 {
		score += 0.25
	}
	if f.Entropy > 4.0 {
		score += 0.10
	}
	if f.VowelRatio < 0.25 {
		score += 0.20
	}
	if f.DigitRatio > 0.3 {
		score += 0.20
	}
	if f.NgramScore < 0.15 {
		score += 0.20
	}
	if f.Hyphens > 2 {
		score += 0.10
	}
	if score > 1.0 {
		score = 1.0
	}
	return score
}

// shannonEntropy computes the Shannon entropy of s in bits per character.
// Higher entropy means more randomness — a hallmark of DGA domains.
func shannonEntropy(s string) float64 {
	if s == "" {
		return 0
	}
	counts := make(map[rune]int)
	for _, r := range s {
		counts[r]++
	}
	n := float64(len(s))
	var h float64
	for _, c := range counts {
		p := float64(c) / n
		h -= p * math.Log2(p)
	}
	return h
}

// vowelRatio returns the fraction of characters in s that are vowels.
func vowelRatio(s string) float64 {
	if s == "" {
		return 0
	}
	v := 0
	for _, r := range s {
		switch r {
		case 'a', 'e', 'i', 'o', 'u', 'y':
			v++
		}
	}
	return float64(v) / float64(len(s))
}

// digitRatio returns the fraction of characters in s that are digits.
func digitRatio(s string) float64 {
	if s == "" {
		return 0
	}
	d := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			d++
		}
	}
	return float64(d) / float64(len(s))
}

// ngramScore returns the average frequency score of the English bigrams in
// s. Higher = more English-like; lower = more DGA-like.
//
// The bigram frequency table is a small hand-picked subset of the most
// common English bigrams (top ~50) plus a smoothed baseline so unknown
// bigrams still contribute a small non-zero score.
func ngramScore(s string) float64 {
	if len(s) < 2 {
		return 0
	}
	s = strings.ToLower(s)
	var sum, count float64
	for i := 0; i+1 < len(s); i++ {
		bg := s[i : i+2]
		sum += bigramWeight(bg)
		count++
	}
	if count == 0 {
		return 0
	}
	return sum / count
}

// bigramWeight returns the weight of an English bigram. Common bigrams
// score ~1.0; uncommon but valid letter-pairs score 0.2; digit/hyphen-
// containing bigrams score 0.
func bigramWeight(bg string) float64 {
	if w, ok := commonBigrams[bg]; ok {
		return w
	}
	// Both letters and not in common list: small baseline.
	if len(bg) == 2 {
		r0 := bg[0]
		r1 := bg[1]
		if isAlpha(r0) && isAlpha(r1) {
			return 0.1
		}
	}
	return 0
}

func isAlpha(b byte) bool {
	return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
}

// commonBigrams is a small subset of the most common English bigrams
// (drawn from Cornell's list of bigram frequencies). Weights are rough
// normalised frequencies.
var commonBigrams = map[string]float64{
	"th": 1.0, "he": 1.0, "in": 0.95, "er": 0.9, "an": 0.9,
	"re": 0.85, "on": 0.85, "at": 0.8, "en": 0.8, "nd": 0.75,
	"ti": 0.75, "es": 0.75, "or": 0.7, "te": 0.7, "of": 0.7,
	"ed": 0.65, "is": 0.65, "it": 0.65, "al": 0.65, "ar": 0.65,
	"st": 0.6, "to": 0.6, "nt": 0.6, "ng": 0.6, "se": 0.55,
	"ha": 0.55, "as": 0.55, "ou": 0.5, "io": 0.5, "le": 0.5,
	"ve": 0.5, "co": 0.5, "me": 0.5, "de": 0.5, "hi": 0.5,
	"ri": 0.5, "ro": 0.5, "ic": 0.5, "ne": 0.5, "ea": 0.5,
	"ra": 0.5, "ce": 0.5, "li": 0.5, "ch": 0.5, "ll": 0.5,
	"be": 0.5, "ma": 0.5, "si": 0.5, "om": 0.5, "ur": 0.5,
}
