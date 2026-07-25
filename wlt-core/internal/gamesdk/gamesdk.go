// Package gamesdk implements the Game Ad Intelligence Engine: a database of
// 12 mobile game ad SDK fingerprints (AdMob, Unity, AppLovin, ironSource,
// Chartboost, Vungle, Meta, AdColony, Mintegral, Fyber, Tapjoy, InMobi)
// together with detection helpers (by domain, by IP), per-game profiles,
// and graceful ad-response generators (empty VAST / no_fill JSON).
//
// The engine lets WLT block game ads without breaking gameplay: instead of
// returning NXDOMAIN (which often crashes the host game), we serve a
// graceful empty ad response so the SDK proceeds to gameplay normally.
package gamesdk

import (
	"encoding/json"
	"strings"
	"sync"
)

// SDK describes one mobile game ad-network SDK.
type SDK struct {
	Name        string   // human-readable name (e.g. "AdMob")
	Domains     []string // domain substrings that identify this SDK
	IPs         []string // hardcoded ad-server IPs
	AdPatterns  []string // path/query substrings in ad requests
	Graceful    string   // graceful response type: "vast" or "json"
}

// Profile is a per-game ad profile (which SDKs the game uses).
type Profile struct {
	PackageName string
	SDKs        []*SDK
	Notes       string
}

// Engine is the Game Ad Intelligence Engine.
type Engine struct {
	mu       sync.RWMutex
	sdks     []*SDK
	byDomain map[string]*SDK // domain substring -> SDK
	byIP     map[string]*SDK // hardcoded IP -> SDK
	byPkg    map[string]*Profile
}

// New returns an Engine pre-loaded with the default 12 SDK fingerprints and
// a small set of per-game profiles.
func New() *Engine {
	e := &Engine{
		byDomain: make(map[string]*SDK),
		byIP:     make(map[string]*SDK),
		byPkg:    make(map[string]*Profile),
	}
	for i := range defaultSDKs {
		e.register(&defaultSDKs[i])
	}
	for pkg, sdkNames := range defaultProfiles {
		profsdk := make([]*SDK, 0, len(sdkNames))
		for _, name := range sdkNames {
			if s := e.FindByName(name); s != nil {
				profsdk = append(profsdk, s)
			}
		}
		e.byPkg[pkg] = &Profile{PackageName: pkg, SDKs: profsdk}
	}
	return e
}

func (e *Engine) register(s *SDK) {
	e.sdks = append(e.sdks, s)
	for _, d := range s.Domains {
		e.byDomain[strings.ToLower(d)] = s
	}
	for _, ip := range s.IPs {
		e.byIP[ip] = s
	}
}

// FindByName returns the SDK with the given name, or nil.
func (e *Engine) FindByName(name string) *SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	for _, s := range e.sdks {
		if strings.EqualFold(s.Name, name) {
			return s
		}
	}
	return nil
}

// All returns all registered SDKs.
func (e *Engine) All() []*SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make([]*SDK, len(e.sdks))
	copy(out, e.sdks)
	return out
}

// DetectByDomain returns the SDK whose domain list contains a substring of
// the given domain, or nil if no SDK matches.
func (e *Engine) DetectByDomain(domain string) *SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	d := strings.ToLower(domain)
	for _, s := range e.sdks {
		for _, pat := range s.Domains {
			if strings.Contains(d, strings.ToLower(pat)) {
				return s
			}
		}
	}
	return nil
}

// DetectByIP returns the SDK with the given hardcoded ad-server IP, or nil.
func (e *Engine) DetectByIP(ip string) *SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.byIP[ip]
}

// GameProfile returns the per-game ad profile for the given Android package
// name, or nil if unknown.
func (e *Engine) GameProfile(packageName string) *Profile {
	e.mu.RLock()
	defer e.mu.RUnlock()
	return e.byPkg[packageName]
}

// GracefulAdResponse returns a graceful empty ad response for the given SDK.
// VAST-returning SDKs (most video ad SDKs) get an empty VAST XML envelope;
// JSON SDKs (Meta Audience Network, Mintegral, InMobi) get a no_fill JSON.
func (e *Engine) GracefulAdResponse(sdk *SDK) []byte {
	if sdk == nil {
		return []byte(emptyVAST)
	}
	switch sdk.Graceful {
	case "json":
		return []byte(noFillJSON)
	default:
		return []byte(emptyVAST)
	}
}

const emptyVAST = `<?xml version="1.0" encoding="UTF-8"?>
<VAST version="4.0">
  <Ad id="wlt-no-fill">
    <InLine>
      <AdSystem>WLT</AdSystem>
      <AdTitle>WLT No Fill</AdTitle>
      <Impression/>
      <Creatives>
        <Creative>
          <Linear>
            <Duration>00:00:00</Duration>
            <MediaFiles/>
          </Linear>
        </Creative>
      </Creatives>
    </InLine>
  </Ad>
</VAST>`

const noFillJSON = `{"ad_units":[],"no_fill":true,"status":"no_ad"}`

// defaultSDKs is the canonical list of 12 supported SDK fingerprints.
var defaultSDKs = []SDK{
	{
		Name:       "AdMob",
		Domains:    []string{"admob", "googleads", "doubleclick", "googlesyndication", "google-analytics"},
		IPs:        []string{"142.250.0.0/15", "172.217.0.0/16"},
		AdPatterns: []string{"/pagead/adview", "/ads/", "/a/ads"},
		Graceful:   "vast",
	},
	{
		Name:       "Unity",
		Domains:    []string{"unityads", "unity3d", "ap.unity.com", "auction.unityads"},
		IPs:        []string{"23.235.32.0/20"},
		AdPatterns: []string{"/v1/ads", "/auction/"},
		Graceful:   "vast",
	},
	{
		Name:       "AppLovin",
		Domains:    []string{"applovin", "rt.applovin", "ms.applovin"},
		IPs:        []string{"72.52.4.0/24"},
		AdPatterns: []string{"/1.0/mediation", "/2.0/mediation"},
		Graceful:   "vast",
	},
	{
		Name:       "ironSource",
		Domains:    []string{"ironsrc", "irs01", "atom-data.io"},
		IPs:        []string{"34.96.0.0/16"},
		AdPatterns: []string{"/adRequest", "/banners"},
		Graceful:   "vast",
	},
	{
		Name:       "Chartboost",
		Domains:    []string{"chartboost", "live.chartboost"},
		IPs:        []string{"54.230.0.0/16"},
		AdPatterns: []string{"/api/v1/getcampaigns", "/interstitial"},
		Graceful:   "vast",
	},
	{
		Name:       "Vungle",
		Domains:    []string{"vungle", "api.vungle"},
		IPs:        []string{"13.32.0.0/16"},
		AdPatterns: []string{"/api/v5/ad", "/rewarded"},
		Graceful:   "vast",
	},
	{
		Name:       "Meta",
		Domains:    []string{"facebook", "fbcdn", "an.facebook", "ads.facebook"},
		IPs:        []string{"31.13.0.0/16"},
		AdPatterns: []string{"/audience_network", "/adnetwork"},
		Graceful:   "json",
	},
	{
		Name:       "AdColony",
		Domains:    []string{"adcolony", "ads.adcolony"},
		IPs:        []string{"173.205.0.0/16"},
		AdPatterns: []string{"/v2/advertisement", "/configure"},
		Graceful:   "vast",
	},
	{
		Name:       "Mintegral",
		Domains:    []string{"mintegral", "ads.mintegral", "cdn.mintegral"},
		IPs:        []string{"150.107.0.0/16"},
		AdPatterns: []string{"/sdk/v1/ad", "/rewarded_video"},
		Graceful:   "json",
	},
	{
		Name:       "Fyber",
		Domains:    []string{"fyber", "video.fyber", "imr.fyber"},
		IPs:        []string{"185.71.0.0/16"},
		AdPatterns: []string{"/api/v2/ads", "/video_ads"},
		Graceful:   "vast",
	},
	{
		Name:       "Tapjoy",
		Domains:    []string{"tapjoy", "api.tapjoy", "connect.tapjoy"},
		IPs:        []string{"54.225.0.0/16"},
		AdPatterns: []string{"/v2/rewarded_ads", "/interstitials"},
		Graceful:   "vast",
	},
	{
		Name:       "InMobi",
		Domains:    []string{"inmobi", "api.inmobi", "i.l.inmobi"},
		IPs:        []string{"103.2.96.0/22"},
		AdPatterns: []string{"/v2/adRequest", "/casm/banner"},
		Graceful:   "json",
	},
}

// defaultProfiles maps well-known game package names to the SDKs they ship.
var defaultProfiles = map[string][]string{
	"com.king.candycrushsaga":      {"AdMob", "Unity", "AppLovin"},
	"com.supercell.clashroyale":    {"Unity", "Vungle", "ironSource"},
	"com.supercell.clashofclans":   {"Unity", "AppLovin", "Meta"},
	"com.mojang.minecraftpe":       {"AdMob", "AppLovin"},
	"com.roblox.client":            {"AdMob", "Unity", "ironSource"},
	"com.nianticlabs.pokemongo":    {"AdMob", "Unity"},
	"com.kiloo.subwaysurf":         {"AdMob", "Chartboost", "AppLovin"},
	"com.gameloft.android.ANMP.GloftA8HM": {"AdMob", "Vungle", "Mintegral"},
}

// MarshalProfiles returns a JSON snapshot of all per-game profiles. Useful
// for debugging / forensics UI.
func (e *Engine) MarshalProfiles() ([]byte, error) {
	e.mu.RLock()
	defer e.mu.RUnlock()
	type out struct {
		Package string   `json:"package"`
		SDKs    []string `json:"sdks"`
	}
	var all []out
	for pkg, p := range e.byPkg {
		names := make([]string, 0, len(p.SDKs))
		for _, s := range p.SDKs {
			names = append(names, s.Name)
		}
		all = append(all, out{Package: pkg, SDKs: names})
	}
	return json.MarshalIndent(all, "", "  ")
}
