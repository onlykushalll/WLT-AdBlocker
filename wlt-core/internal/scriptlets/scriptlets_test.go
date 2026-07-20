package scriptlets

import (
	"strings"
	"testing"
)

func TestGetScriptletsForDomain(t *testing.T) {
	e := New()
	s := e.GetScriptletsForDomain("pagead2.googlesyndication.com")
	if len(s) == 0 { t.Error("no scriptlets for googlesyndication.com") }
	// Should include adsbygoogle
	found := false
	for _, sc := range s {
		if sc.Name == "adsbygoogle" { found = true }
	}
	if !found { t.Error("adsbygoogle scriptlet not found") }
}

func TestGenerateInjectionScript(t *testing.T) {
	e := New()
	script := e.GenerateInjectionScript("pagead2.googlesyndication.com")
	if script == "" { t.Fatal("empty injection script") }
	if !strings.Contains(script, "<script>") { t.Error("missing <script> tag") }
	if !strings.Contains(script, "adsbygoogle") { t.Error("missing adsbygoogle") }
}

func TestScriptletCount(t *testing.T) {
	e := New()
	all := e.AllScriptlets()
	t.Logf("Total scriptlets: %d", len(all))
	if len(all) < 20 { t.Errorf("only %d scriptlets, expected 20+", len(all)) }
}

func TestNoScriptletsForUnknown(t *testing.T) {
	e := New()
	s := e.GetScriptletsForDomain("unknown.example.com")
	// Should still get the global (empty Domains) scriptlets
	t.Logf("Global scriptlets for unknown domain: %d", len(s))
}
