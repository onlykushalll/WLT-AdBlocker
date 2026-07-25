package gamesdk

import (
	"strings"
	"testing"
)

func TestDetectByDomain(t *testing.T) {
	e := New()
	cases := []struct {
		domain string
		want   string
	}{
		{"pagead2.googlesyndication.com", "AdMob"},
		{"googleads.g.doubleclick.net", "AdMob"},
		{"auction.unityads.unity3d.com", "Unity"},
		{"rt.applovin.com", "AppLovin"},
		{"config.irs01.com", "ironSource"}, // irs01 substring
		{"live.chartboost.com", "Chartboost"},
		{"api.vungle.com", "Vungle"},
		{"an.facebook.com", "Meta"},
		{"ads.adcolony.com", "AdColony"},
		{"ads.mintegral.com", "Mintegral"},
		{"video.fyber.com", "Fyber"},
		{"connect.tapjoy.com", "Tapjoy"},
		{"api.inmobi.com", "InMobi"},
		{"totally-unknown.example", ""},
	}
	for _, c := range cases {
		got := e.DetectByDomain(c.domain)
		name := ""
		if got != nil {
			name = got.Name
		}
		if name != c.want {
			t.Errorf("DetectByDomain(%q) = %q, want %q", c.domain, name, c.want)
		}
	}
}

func TestHardcodedIP(t *testing.T) {
	e := New()
	// Some SDK IPs are CIDR ranges; we test only exact-string lookup here.
	// The Engine only indexes by exact IP string, so we test the literal
	// string used in the fingerprint. Engine-side CIDR matching is left
	// to the net/sni IPBlocklist package which has real CIDR support.
	for _, s := range e.All() {
		for _, ip := range s.IPs {
			if got := e.DetectByIP(ip); got == nil || got.Name != s.Name {
				t.Errorf("DetectByIP(%q) returned %v, want %q", ip, got, s.Name)
			}
		}
	}
	if got := e.DetectByIP("192.0.2.1"); got != nil {
		t.Errorf("DetectByIP(unknown) = %v, want nil", got)
	}
}

func TestGameProfile(t *testing.T) {
	e := New()
	p := e.GameProfile("com.supercell.clashroyale")
	if p == nil {
		t.Fatalf("GameProfile(clashroyale) = nil, want profile")
	}
	if len(p.SDKs) < 2 {
		t.Errorf("GameProfile(clashroyale) SDKs = %d, want >= 2", len(p.SDKs))
	}
	if p := e.GameProfile("unknown.pkg"); p != nil {
		t.Errorf("GameProfile(unknown) = %v, want nil", p)
	}
}

func TestGracefulAdResponse(t *testing.T) {
	e := New()
	admob := e.FindByName("AdMob")
	if admob == nil {
		t.Fatalf("FindByName(AdMob) = nil")
	}
	resp := e.GracefulAdResponse(admob)
	if !strings.Contains(string(resp), "VAST") {
		t.Errorf("AdMob graceful response should contain VAST, got: %s", resp)
	}
	meta := e.FindByName("Meta")
	if meta == nil {
		t.Fatalf("FindByName(Meta) = nil")
	}
	resp = e.GracefulAdResponse(meta)
	if !strings.Contains(string(resp), "no_fill") {
		t.Errorf("Meta graceful response should be JSON no_fill, got: %s", resp)
	}
	// Unknown SDK -> empty VAST.
	resp = e.GracefulAdResponse(nil)
	if !strings.Contains(string(resp), "VAST") {
		t.Errorf("nil SDK graceful response should fall back to VAST, got: %s", resp)
	}
}

func TestAllSDKsPresent(t *testing.T) {
	e := New()
	all := e.All()
	want := 12
	if len(all) != want {
		t.Fatalf("All() returned %d SDKs, want %d", len(all), want)
	}
	seen := make(map[string]bool)
	for _, s := range all {
		seen[s.Name] = true
	}
	required := []string{
		"AdMob", "Unity", "AppLovin", "ironSource", "Chartboost", "Vungle",
		"Meta", "AdColony", "Mintegral", "Fyber", "Tapjoy", "InMobi",
	}
	for _, n := range required {
		if !seen[n] {
			t.Errorf("SDK %q not registered", n)
		}
	}
}
