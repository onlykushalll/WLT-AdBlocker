package preparser

import (
	"strings"
	"testing"
)

func TestBasicIfTrue(t *testing.T) {
	p := New(false)
	input := `!#if ext_wlt
||ads.com^
!#endif`
	output := p.Process(input, nil)
	if !strings.Contains(output, "||ads.com^") {
		t.Error("ext_wlt=true block should be included")
	}
}

func TestBasicIfFalse(t *testing.T) {
	p := New(false)
	input := `!#if env_firefox
||firefox-only.com^
!#endif`
	output := p.Process(input, nil)
	if strings.Contains(output, "firefox-only.com") {
		t.Error("env_firefox=false block should be excluded")
	}
}

func TestIfElse(t *testing.T) {
	p := New(false)
	input := `!#if env_firefox
||firefox.com^
!#else
||other.com^
!#endif`
	output := p.Process(input, nil)
	if strings.Contains(output, "firefox.com^") {
		t.Error("firefox branch should be excluded")
	}
	if !strings.Contains(output, "other.com^") {
		t.Error("else branch should be included")
	}
}

func TestNegatedCondition(t *testing.T) {
	p := New(false)
	input := `!#if !env_chromium
||non-chromium.com^
!#endif`
	output := p.Process(input, nil)
	if !strings.Contains(output, "non-chromium.com") {
		t.Error("!env_chromium should be true (env_chromium=false)")
	}
}

func TestNestedIf(t *testing.T) {
	p := New(false)
	input := `!#if ext_wlt
!#if cap_dns_blocking
||dns-block.com^
!#endif
!#if cap_mitm
||mitm-only.com^
!#endif
!#endif`
	output := p.Process(input, nil)
	if !strings.Contains(output, "dns-block.com") {
		t.Error("nested ext_wlt + cap_dns_blocking should be included")
	}
	if strings.Contains(output, "mitm-only.com") {
		t.Error("cap_mitm=false should exclude mitm-only")
	}
}

func TestInclude(t *testing.T) {
	p := New(false)
	input := `!#include included-list.txt
||after-include.com^`
	resolver := func(path string) (string, error) {
		if path == "included-list.txt" {
			return "||included.com^", nil
		}
		return "", nil
	}
	output := p.Process(input, resolver)
	if !strings.Contains(output, "included.com") {
		t.Error("included content should be present")
	}
	if !strings.Contains(output, "after-include.com") {
		t.Error("content after include should be present")
	}
}

func TestRegularLinesUnaffected(t *testing.T) {
	p := New(false)
	input := `||normal.com^
! comment
0.0.0.0 hosts.com`
	output := p.Process(input, nil)
	if !strings.Contains(output, "normal.com") {
		t.Error("normal rules should pass through")
	}
	if !strings.Contains(output, "hosts.com") {
		t.Error("hosts entries should pass through")
	}
}

func TestSetEnv(t *testing.T) {
	p := New(false)
	p.SetEnv("cap_mitm", true)
	input := `!#if cap_mitm
||mitm.com^
!#endif`
	output := p.Process(input, nil)
	if !strings.Contains(output, "mitm.com") {
		t.Error("cap_mitm should be true after SetEnv")
	}
}
