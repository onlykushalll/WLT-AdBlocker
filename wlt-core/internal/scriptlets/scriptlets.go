package scriptlets

import (
        "strings"
        "sync"
)

type Scriptlet struct {
        Name string
        Description string
        Domains []string
        JS string
}

type Engine struct {
        mu sync.RWMutex
        scriptlets []Scriptlet
        domainIndex map[string][]int
}

func New() *Engine {
        e := &Engine{domainIndex: make(map[string][]int)}
        e.scriptlets = []Scriptlet{
                {Name: "adsbygoogle", Description: "Neutralize AdSense", Domains: []string{"googlesyndication.com"},
                        JS: "self.adsbygoogle={loaded:true,push:function(){}};"},
                {Name: "doubleclick", Description: "DoubleClick instream", Domains: []string{"doubleclick.net"},
                        JS: "window.google_ad_status=1;"},
                {Name: "fetch-blocker", Description: "Block fetch to ad endpoints", Domains: []string{},
                        JS: "const _f=window.fetch;window.fetch=function(u,o){if(typeof u==='string'&&/doubleclick|googlesyndication|adservice|adclick|adsystem/.test(u))return new Promise(function(){});return _f.apply(this,arguments)};"},
                {Name: "xhr-blocker", Description: "Block XMLHttpRequest to ad endpoints", Domains: []string{},
                        JS: "const _o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(/doubleclick|googlesyndication|adservice|adclick/.test(u))throw new Error('WLT');return _o.apply(this,arguments)};"},
                {Name: "noeval", Description: "Block eval() used by ad scripts", Domains: []string{},
                        JS: "window.eval=function(){return undefined;};"},
                {Name: "abort-current-script", Description: "Abort ad scripts by trapping property access (uBlock technique)", Domains: []string{},
                        JS: "Object.defineProperty(document,'Ads',{get:function(){throw new ReferenceError('WLT');}});"},
                {Name: "anti-adblock", Description: "Fake adblock detection status", Domains: []string{},
                        JS: "Object.defineProperty(window,'adblock',{value:false,writable:false});Object.defineProperty(window,'adblockDetected',{value:false,writable:false});Object.defineProperty(window,'canRunAds',{value:true,writable:false});"},
                {Name: "googletag", Description: "Neutralize Google Ad Manager tags", Domains: []string{"googletagservices.com","doubleclick.net"},
                        JS: "window.googletag={cmd:[],defineSlot:function(){return{setTargeting:function(){return this;},addService:function(){return this;}};},enableServices:function(){},display:function(){},pubads:function(){return{refresh:function(){},setTargeting:function(){return this;}};}};"},
                {Name: "google-analytics", Description: "Block Google Analytics", Domains: []string{"google-analytics.com"},
                        JS: "window.ga=function(){};window.gtag=function(){};window.dataLayer={push:function(){}};"},
                {Name: "facebook-pixel", Description: "Block Facebook Pixel tracking", Domains: []string{"connect.facebook.net"},
                        JS: "window.fbq=function(){};window._fbq=function(){};"},
                {Name: "twitter-ads", Description: "Block Twitter ads", Domains: []string{"ads-twitter.com","platform.twitter.com"},
                        JS: "window.twttr={ads:{}};"},
                {Name: "amazon-ads", Description: "Block Amazon ads", Domains: []string{"amazon-adsystem.com"},
                        JS: "window.amznads=function(){};"},
        }
        for i, s := range e.scriptlets {
                for _, d := range s.Domains { e.domainIndex[d] = append(e.domainIndex[d], i) }
        }
        return e
}

func (e *Engine) GetScriptletsForDomain(domain string) []Scriptlet {
        e.mu.RLock(); defer e.mu.RUnlock()
        d := strings.ToLower(strings.TrimSpace(domain))
        var result []Scriptlet
        labels := strings.Split(d, ".")
        for i := 0; i < len(labels)-1; i++ {
                if indices, ok := e.domainIndex[strings.Join(labels[i:], ".")]; ok {
                        for _, idx := range indices { result = append(result, e.scriptlets[idx]) }
                }
        }
        return result
}

func (e *Engine) GenerateInjectionScript(domain string) string {
        scriptlets := e.GetScriptletsForDomain(domain)
        if len(scriptlets) == 0 { return "" }
        var sb strings.Builder
        sb.WriteString("<script>\n(function(){\n")
        for _, s := range scriptlets { sb.WriteString(s.JS + "\n") }
        sb.WriteString("})();\n</script>\n")
        return sb.String()
}
