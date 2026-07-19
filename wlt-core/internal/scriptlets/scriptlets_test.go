package scriptlets

import (
        "strings"
        "testing"
)

func TestGetScriptletsForDomain(t *testing.T) {
        e := New()

        tests := []struct {
                domain    string
                wantCount int
        }{
                {"pagead2.googlesyndication.com", 1}, // googlesyndication-adsbygoogle
                {"googleads.g.doubleclick.net", 2},   // googlesyndication + doubleclick
                {"ad.doubleclick.net", 2},            // doubleclick (exact + suffix match)
                {"unknown.com", 0},
        }
        for _, tc := range tests {
                got := e.GetScriptletsForDomain(tc.domain)
                if len(got) != tc.wantCount {
                        t.Errorf("GetScriptletsForDomain(%q) = %d scriptlets, want %d", tc.domain, len(got), tc.wantCount)
                        for _, s := range got {
                                t.Logf("  got: %s", s.Name)
                        }
                }
        }
}

func TestGenerateInjectionScript(t *testing.T) {
        e := New()
        script := e.GenerateInjectionScript("pagead2.googlesyndication.com")
        if script == "" {
                t.Fatal("empty injection script")
        }
        if !strings.Contains(script, "<script>") {
                t.Error("missing <script> tag")
        }
        if !strings.Contains(script, "adsbygoogle") {
                t.Error("missing adsbygoogle neutralization")
        }
        if !strings.Contains(script, "WLT-Adblocker") {
                t.Error("missing WLT signature")
        }
}

func TestSurrogateAdsbyGoogle(t *testing.T) {
        e := New()
        scriptlets := e.GetScriptletsForDomain("googlesyndication.com")
        if len(scriptlets) == 0 {
                t.Fatal("no scriptlets for googlesyndication.com")
        }
        // Verify the adsbygoogle surrogate
        found := false
        for _, s := range scriptlets {
                if s.Name == "googlesyndication-adsbygoogle" {
                        found = true
                        if !strings.Contains(s.JS, "adsbygoogle") {
                                t.Error("surrogate JS doesn't mention adsbygoogle")
                        }
                        if !strings.Contains(s.JS, "loaded: true") {
                                t.Error("surrogate doesn't set loaded:true")
                        }
                }
        }
        if !found {
                t.Error("adsbygoogle surrogate not found")
        }
}
