package gamesdk

import (
	"strings"
	"sync"
)

type SDK string
const (
	SDKAdMob SDK = "admob"; SDKUnity SDK = "unity"; SDKAppLovin SDK = "applovin"
	SDKIronSource SDK = "ironsource"; SDKChartboost SDK = "chartboost"; SDKVungle SDK = "vungle"
	SDKMeta SDK = "meta"; SDKAdColony SDK = "adcolony"; SDKMintegral SDK = "mintegral"
	SDKFyber SDK = "fyber"; SDKTapjoy SDK = "tapjoy"; SDKInMobi SDK = "inmobi"
	SDKUnknown SDK = "unknown"
)

type Engine struct {
	mu          sync.RWMutex
	domainIndex map[string]SDK
	ipSet       map[string]struct{}
}

func New() *Engine {
	e := &Engine{domainIndex: make(map[string]SDK), ipSet: make(map[string]struct{})}
	e.loadDefaults()
	return e
}

func (e *Engine) DetectByDomain(domain string) SDK {
	e.mu.RLock()
	defer e.mu.RUnlock()
	d := strings.ToLower(strings.TrimSpace(domain))
	d = strings.Trim(d, ".")
	labels := strings.Split(d, ".")
	for i := 0; i < len(labels)-1; i++ {
		suffix := strings.Join(labels[i:], ".")
		if sdk, ok := e.domainIndex[suffix]; ok { return sdk }
	}
	return SDKUnknown
}

func (e *Engine) loadDefaults() {
	fingerprints := map[SDK][]string{
		SDKAdMob: {"googleads.g.doubleclick.net","pagead2.googlesyndication.com","googlesyndication.com","doubleclick.net","googleadservices.com","adservice.google.com","admob.google.com"},
		SDKUnity: {"unityads.unity3d.com","cloud.unity3d.com","cdn.unity.com"},
		SDKAppLovin: {"applovin.com","applovin-thirdparty.com"},
		SDKIronSource: {"ironsrc.com"},
		SDKChartboost: {"chartboost.com"},
		SDKVungle: {"vungle.com"},
		SDKMeta: {"an.facebook.com","ads.facebook.com"},
		SDKAdColony: {"adcolony.com"},
		SDKMintegral: {"mintegral.com"},
		SDKFyber: {"fyber.com"},
		SDKTapjoy: {"tapjoy.com"},
		SDKInMobi: {"inmobi.com"},
	}
	for sdk, domains := range fingerprints {
		for _, d := range domains { e.domainIndex[strings.ToLower(d)] = sdk }
	}
}
