package preparser

import (
	"strings"
	"testing"
)

func TestIf(t *testing.T) {
	in := []string{
		"line1",
		"!#if ext_wlt",
		"wlt-only-line",
		"!#endif",
		"line2",
	}
	out := Process(in, map[string]bool{"ext_wlt": true}, nil)
	if len(out) != 3 {
		t.Fatalf("len=%d want 3: %v", len(out), out)
	}
	if out[1] != "wlt-only-line" {
		t.Errorf("missing wlt line: %v", out)
	}

	// env false: the block should be dropped.
	out2 := Process(in, map[string]bool{"ext_wlt": false}, nil)
	if len(out2) != 2 {
		t.Errorf("len=%d want 2: %v", len(out2), out2)
	}
}

func TestElse(t *testing.T) {
	in := []string{
		"!#if ext_wlt",
		"wlt-line",
		"!#else",
		"other-line",
		"!#endif",
	}
	out := Process(in, map[string]bool{"ext_wlt": true}, nil)
	if !contains(out, "wlt-line") || contains(out, "other-line") {
		t.Errorf("if-true case wrong: %v", out)
	}
	out2 := Process(in, map[string]bool{"ext_wlt": false}, nil)
	if contains(out2, "wlt-line") || !contains(out2, "other-line") {
		t.Errorf("if-false case wrong: %v", out2)
	}
}

func TestNested(t *testing.T) {
	in := []string{
		"!#if ext_wlt",
		"outer-wlt",
		"!#if env_android",
		"inner-android",
		"!#endif",
		"!#endif",
		"end",
	}
	// Both true: keep all 3.
	out := Process(in, map[string]bool{"ext_wlt": true, "env_android": true}, nil)
	if !contains(out, "outer-wlt") || !contains(out, "inner-android") || !contains(out, "end") {
		t.Errorf("both-true case wrong: %v", out)
	}
	// Only outer true: keep outer, drop inner.
	out2 := Process(in, map[string]bool{"ext_wlt": true, "env_android": false}, nil)
	if !contains(out2, "outer-wlt") || contains(out2, "inner-android") {
		t.Errorf("outer-only case wrong: %v", out2)
	}
	// Outer false: drop both.
	out3 := Process(in, map[string]bool{"ext_wlt": false, "env_android": true}, nil)
	if contains(out3, "outer-wlt") || contains(out3, "inner-android") {
		t.Errorf("outer-false case wrong: %v", out3)
	}
}

func TestInclude(t *testing.T) {
	in := []string{
		"line1",
		"!#include other.txt",
		"line2",
	}
	out := Process(in, nil, func(target string) []string {
		if target == "other.txt" {
			return []string{"included1", "included2"}
		}
		return nil
	})
	want := []string{"line1", "included1", "included2", "line2"}
	if len(out) != len(want) {
		t.Fatalf("len=%d want %d: %v", len(out), len(want), out)
	}
	for i, w := range want {
		if out[i] != w {
			t.Errorf("out[%d]=%q want %q", i, out[i], w)
		}
	}
}

func TestWltTokens(t *testing.T) {
	env := map[string]bool{
		"ext_wlt":          true,
		"env_android":      true,
		"cap_dns_blocking": true,
		"cap_mitm":         false,
	}
	in := []string{
		"!#if ext_wlt",
		"wlt",
		"!#endif",
		"!#if env_android",
		"android",
		"!#endif",
		"!#if cap_mitm",
		"mitm",
		"!#endif",
		"!#if cap_dns_blocking",
		"dns",
		"!#endif",
	}
	out := Process(in, env, nil)
	if !contains(out, "wlt") || !contains(out, "android") || contains(out, "mitm") || !contains(out, "dns") {
		t.Errorf("wrong: %v", out)
	}
}

func TestUblockTokens(t *testing.T) {
	env := map[string]bool{
		"ext_ublock":   true,
		"env_chromium": true,
		"env_firefox":  false,
	}
	in := []string{
		"!#if env_chromium",
		"chrome",
		"!#endif",
		"!#if env_firefox",
		"ff",
		"!#endif",
	}
	out := Process(in, env, nil)
	if !contains(out, "chrome") || contains(out, "ff") {
		t.Errorf("wrong: %v", out)
	}
}

func TestUnknownToken(t *testing.T) {
	// Unknown tokens default to false in an empty env.
	in := []string{
		"!#if unknown_token",
		"unknown",
		"!#endif",
		"end",
	}
	out := Process(in, map[string]bool{}, nil)
	if contains(out, "unknown") || !contains(out, "end") {
		t.Errorf("wrong: %v", out)
	}
}

func TestNoDirectives(t *testing.T) {
	in := []string{"a", "b", "c"}
	out := Process(in, nil, nil)
	if len(out) != 3 || out[0] != "a" || out[1] != "b" || out[2] != "c" {
		t.Errorf("no-directive case wrong: %v", out)
	}
}

func TestNegation(t *testing.T) {
	in := []string{
		"!#if !ext_wlt",
		"non-wlt",
		"!#endif",
	}
	out := Process(in, map[string]bool{"ext_wlt": false}, nil)
	if !contains(out, "non-wlt") {
		t.Errorf("negation wrong: %v", out)
	}
	out2 := Process(in, map[string]bool{"ext_wlt": true}, nil)
	if contains(out2, "non-wlt") {
		t.Errorf("negation wrong: %v", out2)
	}
}

func TestAndOr(t *testing.T) {
	in := []string{
		"!#if ext_wlt && env_android",
		"wlt-android",
		"!#endif",
		"!#if env_chromium || env_firefox",
		"browser",
		"!#endif",
	}
	env := map[string]bool{"ext_wlt": true, "env_android": true, "env_firefox": true}
	out := Process(in, env, nil)
	if !contains(out, "wlt-android") || !contains(out, "browser") {
		t.Errorf("and/or wrong: %v", out)
	}
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if strings.TrimSpace(x) == v {
			return true
		}
	}
	return false
}
