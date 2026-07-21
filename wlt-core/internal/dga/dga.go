// Package dga implements domain classification to detect algorithmically
// generated ad/tracker domains (DGA) and AI-generated "phantom squatting" domains.
//
// Research findings (2024-2026):
// - Ad networks use DGA to rotate tracking domains faster than blocklists can track
// - AI "phantom squatting": LLMs hallucinate domains that attackers pre-register
// - NextDNS "block domains < 30 days old" heuristic is effective
// - Feature vector: entropy, n-gram score, vowel ratio, TLD, domain length
package dga

import (
        "math"
        "strings"
        "unicode"
)

// DomainFeatures extracts ad/tracker-relevant features from a domain.
type DomainFeatures struct {
        Domain     string
        TLD        string
        Length     int
        Entropy    float64
        VowelRatio float64
        DigitRatio float64
        HyphenCount int
        NgramScore float64 // lower = more suspicious
}

// ExtractFeatures computes features from a domain name.
func ExtractFeatures(domain string) DomainFeatures {
        d := strings.ToLower(strings.TrimSpace(domain))
        d = strings.Trim(d, ".")

        // Get TLD (last label)
        labels := strings.Split(d, ".")
        tld := ""
        if len(labels) > 0 {
                tld = labels[len(labels)-1]
        }

        // Get the second-level domain (SLD)
        sld := d
        if len(labels) > 1 {
                sld = labels[len(labels)-2]
        }

        f := DomainFeatures{
                Domain: d,
                TLD:    tld,
                Length: len(sld),
        }

        // Shannon entropy
        f.Entropy = shannonEntropy(sld)

        // Vowel ratio
        vowels := 0
        digits := 0
        hyphens := 0
        for _, c := range sld {
                if strings.ContainsRune("aeiou", c) {
                        vowels++
                }
                if unicode.IsDigit(c) {
                        digits++
                }
                if c == '-' {
                        hyphens++
                }
        }
        if len(sld) > 0 {
                f.VowelRatio = float64(vowels) / float64(len(sld))
                f.DigitRatio = float64(digits) / float64(len(sld))
        }
        f.HyphenCount = hyphens

        // N-gram score (how "English-like" the domain is)
        f.NgramScore = ngramScore(sld)

        return f
}

// IsSuspicious returns true if the domain looks like DGA or AI-generated.
// Uses heuristics from research: high entropy, low n-gram score, many digits.
func IsSuspicious(f DomainFeatures) bool {
        // High entropy = random-looking (DGA-like)
        if f.Entropy > 3.8 && f.Length > 10 {
                return true
        }
        // Very low vowel ratio (consonant-heavy = DGA-like)
        if f.VowelRatio < 0.15 && f.Length > 8 {
                return true
        }
        // Many digits in domain
        if f.DigitRatio > 0.4 && f.Length > 6 {
                return true
        }
        // Very low n-gram score (not English-like at all)
        if f.NgramScore < 0.1 && f.Length > 8 {
                return true
        }
        // Many hyphens
        if f.HyphenCount > 3 {
                return true
        }
        return false
}

// SuspicionScore returns 0.0-1.0 confidence that domain is DGA/AI-generated.
func SuspicionScore(f DomainFeatures) float64 {
        score := 0.0

        // Entropy contribution (0-0.3)
        if f.Entropy > 4.0 {
                score += 0.3
        } else if f.Entropy > 3.5 {
                score += 0.2
        } else if f.Entropy > 3.0 {
                score += 0.1
        }

        // Vowel ratio (0-0.2)
        if f.VowelRatio < 0.1 {
                score += 0.2
        } else if f.VowelRatio < 0.2 {
                score += 0.1
        }

        // Digit ratio (0-0.2)
        if f.DigitRatio > 0.5 {
                score += 0.2
        } else if f.DigitRatio > 0.3 {
                score += 0.1
        }

        // N-gram score (0-0.2)
        if f.NgramScore < 0.05 {
                score += 0.2
        } else if f.NgramScore < 0.1 {
                score += 0.1
        }

        // Length (0-0.1)
        if f.Length > 20 {
                score += 0.1
        } else if f.Length > 15 {
                score += 0.05
        }

        return math.Min(score, 1.0)
}

func shannonEntropy(s string) float64 {
        if len(s) == 0 {
                return 0
        }
        freq := make(map[rune]int)
        for _, c := range s {
                freq[c]++
        }
        entropy := 0.0
        n := float64(len(s))
        for _, count := range freq {
                p := float64(count) / n
                if p > 0 {
                        entropy -= p * math.Log2(p)
                }
        }
        return entropy
}

// ngramScore returns how English-like a string is (0-1, higher = more natural).
// Uses common English bigrams.
var commonBigrams = map[string]bool{
        "th": true, "he": true, "in": true, "er": true, "an": true,
        "re": true, "on": true, "at": true, "en": true, "nd": true,
        "ti": true, "es": true, "or": true, "te": true, "of": true,
        "ed": true, "is": true, "it": true, "al": true, "ar": true,
        "st": true, "to": true, "nt": true, "ng": true, "se": true,
        "ha": true, "as": true, "ou": true, "io": true, "le": true,
        "ve": true, "co": true, "me": true, "de": true, "hi": true,
        "ri": true, "ro": true, "ic": true, "ne": true, "ea": true,
        "ra": true, "ce": true, "li": true, "ch": true, "ll": true,
        "be": true, "ma": true, "si": true, "om": true, "ur": true,
}

func ngramScore(s string) float64 {
        if len(s) < 2 {
                return 0
        }
        s = strings.ToLower(s)
        total := 0
        matched := 0
        for i := 0; i < len(s)-1; i++ {
                bigram := s[i : i+2]
                if bigram[0] >= 'a' && bigram[0] <= 'z' && bigram[1] >= 'a' && bigram[1] <= 'z' {
                        total++
                        if commonBigrams[bigram] {
                                matched++
                        }
                }
        }
        if total == 0 {
                return 0
        }
        return float64(matched) / float64(total)
}
