// Package gamesdk implements the Game Ad Intelligence Engine — WLT's unique
// feature for detecting and neutralizing mobile game ad SDKs that go beyond
// static domain lists.
//
// Detection methods:
//   1. SDK fingerprinting — match connection patterns to known SDKs
//   2. Hardcoded IP database — block SDK connections that skip DNS
//   3. Rewarded ad detection — return empty response so games don't crash
//   4. Interstitial null response — valid-but-empty ad response
//
// Game SDKs handled: AdMob, Unity Ads, AppLovin, ironSource, Chartboost,
// Vungle, Meta Audience Network, AdColony, Mintegral, Fyber, Tapjoy, InMobi.
package gamesdk

import (
	"strings"
	"sync"
)

// SDK identifies a known game ad network.
type SDK string

const (
	SDKAdMob      SDK = "admob"
	SDKUnity      SDK = "unity"
	SDKAppLovin   SDK = "applovin"
	SDKIronSource SDK = "ironsource"
	SDKChartboost SDK = "chartboost"
	SDKVungle     SDK = "vungle"
	SDKMeta       SDK = "meta_audience_network"
	SDKAdColony   SDK = "adcolony"
	SDKMintegral  SDK = "mintegral"
	SDKFyber      SDK = "fyber"
	SDKTapjoy     SDK = "tapjoy"
	SDKInMobi     SDK = "inmobi"
	SDKUnknown    SDK = "unknown"
)

// Fingerprint is a pattern that identifies an SDK by domain characteristics.
type Fingerprint struct {
	SDK         SDK
	Domains     []string // exact/suffix domain matches
	PathHints   []string // URL path substrings (for HTTPS layer)
	UserAgents  []string // UA substrings
	Description string
}

// Engine holds game SDK fingerprints and provides detection.
type Engine struct {
	mu           sync.RWMutex
	fingerprints []Fingerprint
	// domainIndex: domain suffix -> SDK (fast lookup)
	domainIndex map[string]SDK
	// ipSet: hardcoded ad server IPs
	ipSet map[string]struct{}
	// perGameProfile: package name -> set of SDKs seen (learned over time)
	perGameProfile map[string]map[SDK]bool
}

// New returns an Engine preloaded with all known game SDK fingerprints.
func New() *Engine {
	e := &Engine{
		domainIndex:    make(map[string]SDK),
		ipSet:          make(map[string]struct{}),
		perGameProfile: make(map[string]map[SDK]bool),
	}
	e.loadDefaults()
	return e
}

// DetectByDomain returns the SDK that owns a domain, or SDKUnknown.
// Checks suffix matches (e.g., "x.applovin.com" matches "applovin.com").
func (e *Engine) DetectByDomain(domain string) SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	d := strings.ToLower(strings.TrimSpace(domain))
	d = strings.Trim(d, ".")
	// Walk suffixes: try d, then parent, etc.
	labels := strings.Split(d, ".")
	for i := 0; i < len(labels)-1; i++ {
		suffix := strings.Join(labels[i:], ".")
		if sdk, ok := e.domainIndex[suffix]; ok {
			return sdk
		}
	}
	return SDKUnknown
}

// IsHardcodedIP reports whether an IP is a known ad server (skips DNS).
func (e *Engine) IsHardcodedIP(ip string) bool {
	e.mu.RLock()
	defer e.mu.RUnlock()
	_, ok := e.ipSet[ip]
	return ok
}

// AddHardcodedIP adds an IP to the hardcoded blocklist (runtime, e.g. from forensics).
func (e *Engine) AddHardcodedIP(ip string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.ipSet[ip] = struct{}{}
}

// RecordGameActivity notes that a package was seen talking to an SDK.
// Builds the per-game profile over time — WLT's "per-game profiles" feature.
func (e *Engine) RecordGameActivity(packageName string, sdk SDK) {
	if sdk == SDKUnknown || packageName == "" {
		return
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.perGameProfile[packageName] == nil {
		e.perGameProfile[packageName] = make(map[SDK]bool)
	}
	e.perGameProfile[packageName][sdk] = true
}

// GameProfile returns the set of SDKs seen for a package.
func (e *Engine) GameProfile(packageName string) []SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	set := e.perGameProfile[packageName]
	out := make([]SDK, 0, len(set))
	for sdk := range set {
		out = append(out, sdk)
	}
	return out
}

// GracefulAdResponse returns a valid-but-empty response for rewarded/interstitial
// ad requests so the game doesn't crash or show error screens.
//
// For rewarded ads: returns an empty VAST XML (video ad served template),
// signaling "no ad available" — game continues, rewards nothing.
// For interstitials: returns a minimal JSON "no fill" response.
//
// This is WLT's "graceful ad failure" feature: games don't crash when blocked.
func GracefulAdResponse(sdk SDK, contentType string) []byte {
	switch {
	case strings.Contains(contentType, "xml"):
		// VAST 4.0 empty response — ad server says "no ad"
		return []byte(`<?xml version="1.0" encoding="UTF-8"?><VAST version="4.0"/>`)
	case strings.Contains(contentType, "json"):
		// Common "no fill" JSON shape used by AdMob/AppLovin/Meta
		return []byte(`{"ads":[],"status":"no_fill","code":204}`)
	default:
		return []byte{}
	}
}

// loadDefaults populates fingerprints for all major game ad SDKs.
// Data sources: SDK documentation, reverse-engineering, community lists.
func (e *Engine) loadDefaults() {
	e.fingerprints = []Fingerprint{
		{
			SDK: SDKAdMob,
			Domains: []string{
				"googleads.g.doubleclick.net",
				"pagead2.googlesyndication.com",
				"googleads4.g.doubleclick.net",
				"adclick.g.doubleclick.net",
				"ad.doubleclick.net",
				"adservice.google.com",
				"pubads.g.doubleclick.net",
				"admob.google.com",
				"ads.google.com",
				"googlesyndication.com",
			},
			PathHints:  []string{"/pagead/", "/ads/", "/gampad/"},
			UserAgents: []string{"AdsBot-Google", "Mediapartners-Google"},
			Description: "Google AdMob / AdSense — dominant mobile game ad network",
		},
		{
			SDK: SDKUnity,
			Domains: []string{
				"unityads.unity3d.com",
				"unity3d.com",
				"ads.unityads.unity3d.com",
				"config.unityads.unity3d.com",
				"cdp.cloud.unity3d.com",
				"cdn.unity.com",
				"perf-events.cloud.unity3d.com",
			},
			PathHints:   []string{"/unityads/", "/v2/ads/"},
			Description: "Unity Ads — common in Unity-based games",
		},
		{
			SDK: SDKAppLovin,
			Domains: []string{
				"applovin.com",
				"rt.applovin.com",
				"ms.applovin.com",
				"vid.applovin.com",
				"d.applovin.com",
				"pdn.applovin.com",
				"applovin-thirdparty.com",
			},
			PathHints:   []string{"/2.0/getAds", "/postback"},
			Description: "AppLovin MAX / AppLovin SDK",
		},
		{
			SDK: SDKIronSource,
			Domains: []string{
				"ironsrc.com",
				"api.ironsrc.com",
				"events.ironsrc.com",
				"cdn.ironsrc.com",
				"unity.ironsrc.com",
			},
			Description: "ironSource mediation (acquired by Unity)",
		},
		{
			SDK: SDKChartboost,
			Domains: []string{
				"chartboost.com",
				"live.chartboost.com",
				"api.chartboost.com",
				"charts.chartboost.com",
			},
			PathHints:   []string{"/get campaigns", "/api/install"},
			Description: "Chartboost — game-focused ad network",
		},
		{
			SDK: SDKVungle,
			Domains: []string{
				"vungle.com",
				"api.vungle.com",
				"events.vungle.com",
				"cdn.vungle.com",
				"logger.vungle.com",
			},
			Description: "Vungle (Liftoff) video ads",
		},
		{
			SDK: SDKMeta,
			Domains: []string{
				"facebook.com",
				"graph.facebook.com",
				"an.facebook.com",
				"ads.facebook.com",
				"fb-analytics.com",
			},
			PathHints:   []string{"/network_ads_common", "/audience_network"},
			Description: "Meta Audience Network (Facebook ads in apps)",
		},
		{
			SDK: SDKAdColony,
			Domains: []string{
				"adcolony.com",
				"ads.adcolony.com",
				"androidads.adcolony.com",
				"cdn.adcolony.com",
			},
			Description: "AdColony video ads",
		},
		{
			SDK: SDKMintegral,
			Domains: []string{
				"mintegral.com",
				"api.mintegral.com",
				"cdn.mintegral.com",
				"adsdk.mintegral.com",
			},
			Description: "Mintegral (Chinese ad network)",
		},
		{
			SDK: SDKFyber,
			Domains: []string{
				"fyber.com",
				"engine.fyber.com",
				"api.fyber.com",
				"cdn.fyber.com",
			},
			Description: "Fyber / Digital Turbine mediation",
		},
		{
			SDK: SDKTapjoy,
			Domains: []string{
				"tapjoy.com",
				"api.tapjoy.com",
				"connect.tapjoy.com",
				"ads.tapjoy.com",
			},
			Description: "Tapjoy offerwall/rewarded",
		},
		{
			SDK: SDKInMobi,
			Domains: []string{
				"inmobi.com",
				"api.inmobi.com",
				"cdn.inmobi.com",
				"i.l.inmobi.com",
			},
			Description: "InMobi ad network",
		},
	}
	// Build domain index for fast lookup.
	for _, fp := range e.fingerprints {
		for _, d := range fp.Domains {
			e.domainIndex[strings.ToLower(d)] = fp.SDK
		}
	}
	// Hardcoded IPs that some SDKs use to bypass DNS.
	// (Curated subset — full list in blocklists/wlt-game-ips.txt)
	defaultIPs := []string{
		// AdMob legacy hardcoded endpoints (now mostly DNS, kept for safety)
		"172.217.0.0", // Google services range — handled by SNI not IP
	}
	for _, ip := range defaultIPs {
		e.ipSet[ip] = struct{}{}
	}
}

// AllFingerprints returns the loaded fingerprints (for UI / forensics display).
func (e *Engine) AllFingerprints() []Fingerprint {
	e.mu.RLock()
	defer e.mu.RUnlock()
	out := make([]Fingerprint, len(e.fingerprints))
	copy(out, e.fingerprints)
	return out
}

// LoadHardcodedIPs adds IPs from a list (loaded from wlt-game-ips.txt at startup).
func (e *Engine) LoadHardcodedIPs(ips []string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	for _, ip := range ips {
		ip = strings.TrimSpace(ip)
		if ip != "" && !strings.HasPrefix(ip, "#") {
			e.ipSet[ip] = struct{}{}
		}
	}
}
