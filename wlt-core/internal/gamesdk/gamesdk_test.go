package gamesdk

import "testing"

func TestDetectByDomain(t *testing.T) {
	e := New()

	tests := []struct {
		domain string
		want   SDK
	}{
		{"pagead2.googlesyndication.com", SDKAdMob},
		{"googleads.g.doubleclick.net", SDKAdMob},
		{"ads.unityads.unity3d.com", SDKUnity},
		{"rt.applovin.com", SDKAppLovin},
		{"api.ironsrc.com", SDKIronSource},
		{"live.chartboost.com", SDKChartboost},
		{"api.vungle.com", SDKVungle},
		{"an.facebook.com", SDKMeta},
		{"ads.adcolony.com", SDKAdColony},
		{"api.mintegral.com", SDKMintegral},
		{"engine.fyber.com", SDKFyber},
		{"connect.tapjoy.com", SDKTapjoy},
		{"api.inmobi.com", SDKInMobi},
		{"subdomain.applovin.com", SDKAppLovin},  // suffix match
		{"notanad.com", SDKUnknown},
		{"", SDKUnknown},
	}
	for _, tc := range tests {
		got := e.DetectByDomain(tc.domain)
		if got != tc.want {
			t.Errorf("DetectByDomain(%q) = %v, want %v", tc.domain, got, tc.want)
		}
	}
}

func TestHardcodedIP(t *testing.T) {
	e := New()
	e.AddHardcodedIP("1.2.3.4")
	if !e.IsHardcodedIP("1.2.3.4") {
		t.Error("hardcoded IP not detected")
	}
	if e.IsHardcodedIP("5.6.7.8") {
		t.Error("non-added IP incorrectly detected")
	}
}

func TestGameProfile(t *testing.T) {
	e := New()
	e.RecordGameActivity("com.example.game", SDKAdMob)
	e.RecordGameActivity("com.example.game", SDKUnity)
	e.RecordGameActivity("com.example.game", SDKUnity) // duplicate

	profile := e.GameProfile("com.example.game")
	if len(profile) != 2 {
		t.Errorf("profile size = %d, want 2 (AdMob + Unity)", len(profile))
	}

	// Unknown package returns empty
	if len(e.GameProfile("com.unknown")) != 0 {
		t.Error("unknown package should have empty profile")
	}
}

func TestGracefulAdResponse(t *testing.T) {
	xml := GracefulAdResponse(SDKAdMob, "application/xml")
	if string(xml) == "" {
		t.Error("XML response empty")
	}
	if !contains(string(xml), "VAST") {
		t.Error("XML response should be VAST")
	}
	json := GracefulAdResponse(SDKAppLovin, "application/json")
	if !contains(string(json), "no_fill") {
		t.Error("JSON response should indicate no_fill")
	}
}

func contains(s, substr string) bool {
	for i := 0; i+len(substr) <= len(s); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
