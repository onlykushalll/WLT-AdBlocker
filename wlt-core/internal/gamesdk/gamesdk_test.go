package gamesdk

import "testing"

func TestDetectByDomain(t *testing.T) {
	e := New()
	tests := []struct{ domain string; want SDK }{
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
		{"subdomain.applovin.com", SDKAppLovin},
		{"notanad.com", SDKUnknown},
	}
	for _, tc := range tests {
		got := e.DetectByDomain(tc.domain)
		if got != tc.want {
			t.Errorf("DetectByDomain(%q) = %v, want %v", tc.domain, got, tc.want)
		}
	}
}
