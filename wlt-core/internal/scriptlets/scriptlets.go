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
		// --- Ad network neutralization ---
		{Name: "adsbygoogle", Description: "Neutralize AdSense", Domains: []string{"googlesyndication.com"},
			JS: "self.adsbygoogle={loaded:true,push:function(){}};"},
		{Name: "doubleclick", Description: "DoubleClick instream", Domains: []string{"doubleclick.net"},
			JS: "window.google_ad_status=1;"},
		{Name: "googletag", Description: "Google Ad Manager", Domains: []string{"googletagservices.com","doubleclick.net"},
			JS: "window.googletag={cmd:[],defineSlot:function(){return{setTargeting:function(){return this;},addService:function(){return this;}};},enableServices:function(){},display:function(){},pubads:function(){return{refresh:function(){},setTargeting:function(){return this;}};}};"},
		{Name: "google-analytics", Description: "GA blocking", Domains: []string{"google-analytics.com"},
			JS: "window.ga=function(){};window.gtag=function(){};window.dataLayer={push:function(){}};"},
		{Name: "facebook-pixel", Description: "FB Pixel", Domains: []string{"connect.facebook.net"},
			JS: "window.fbq=function(){};window._fbq=function(){};"},
		{Name: "twitter-ads", Description: "Twitter ads", Domains: []string{"ads-twitter.com","platform.twitter.com"},
			JS: "window.twttr={ads:{}};"},
		{Name: "amazon-ads", Description: "Amazon ads", Domains: []string{"amazon-adsystem.com"},
			JS: "window.amznads=function(){};"},

		// --- Network-level blocking ---
		{Name: "fetch-blocker", Description: "Block fetch to ad endpoints", Domains: []string{},
			JS: "const _f=window.fetch;window.fetch=function(u,o){if(typeof u==='string'&&/doubleclick|googlesyndication|adservice|adclick|adsystem|ads-twitter|amazon-adsystem/.test(u))return new Promise(function(){});return _f.apply(this,arguments)};"},
		{Name: "xhr-blocker", Description: "Block XHR to ad endpoints", Domains: []string{},
			JS: "const _o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(/doubleclick|googlesyndication|adservice|adclick/.test(u))throw new Error('WLT');return _o.apply(this,arguments)};"},
		{Name: "noeval", Description: "Block eval()", Domains: []string{},
			JS: "window.eval=function(){return undefined;};"},

		// --- Anti-adblock (uBlock techniques) ---
		{Name: "abort-current-script", Description: "Abort ad scripts (uBlock)", Domains: []string{},
			JS: "Object.defineProperty(document,'Ads',{get:function(){throw new ReferenceError('WLT');}});"},
		{Name: "anti-adblock", Description: "Fake adblock detection", Domains: []string{},
			JS: "Object.defineProperty(window,'adblock',{value:false,writable:false});Object.defineProperty(window,'adblockDetected',{value:false,writable:false});Object.defineProperty(window,'canRunAds',{value:true,writable:false});Object.defineProperty(window,'isAdBlockActive',{value:false,writable:false});"},
		{Name: "overlay-buster", Description: "Remove anti-adblock overlays (uBlock)", Domains: []string{},
			JS: "const obs=new MutationObserver(function(muts){for(var m of muts){for(var n of m.addedNodes){if(n instanceof HTMLElement){var s=n.style;if(s&&(s.position==='fixed'||s.position==='absolute')&&s.zIndex>999&&(n.innerHTML.match(/adblock|ad blocker|disable/i))){n.remove();}}}}});obs.observe(document.body,{childList:true,subtree:true});"},

		// --- Popup/window blocking ---
		{Name: "prevent-window-open", Description: "Block popup windows (uBlock)", Domains: []string{},
			JS: "window.open=function(){return null;};"},
		{Name: "close-window", Description: "Auto-close ad popups (uBlock)", Domains: []string{},
			JS: "if(window.opener&&window.name&&window.name.match(/ad|popup/i)){window.close();}"},

		// --- DOM manipulation ---
		{Name: "remove-class", Description: "Remove ad CSS classes (uBlock)", Domains: []string{},
			JS: "var adCl=['ad','ads','advert','advertisement','ad-banner','ad-container','sponsor','sponsored','promo'];adCl.forEach(function(c){document.querySelectorAll('.'+c).forEach(function(el){el.style.display='none';});});"},
		{Name: "prevent-refresh", Description: "Block meta refresh redirects (uBlock)", Domains: []string{},
			JS: "document.querySelectorAll('meta[http-equiv=refresh]').forEach(function(m){m.remove();});"},

		// --- Timer manipulation ---
		{Name: "adjust-setInterval", Description: "Slow ad rotation timers (uBlock)", Domains: []string{},
			JS: "var _si=window.setInterval;window.setInterval=function(fn,d){return d<5000&&d>0?_si(fn,d*10):_si(fn,d);};"},
		{Name: "adjust-setTimeout", Description: "Slow ad display timers (uBlock)", Domains: []string{},
			JS: "var _st=window.setTimeout;window.setTimeout=function(fn,d){return d<3000&&d>0?_st(fn,d*10):_st(fn,d);};"},

		// --- Privacy ---
		{Name: "prevent-canvas", Description: "Block canvas fingerprinting (uBlock)", Domains: []string{},
			JS: "HTMLCanvasElement.prototype.toDataURL=function(){return 'data:,';};"},
		{Name: "webrtc-if", Description: "Block WebRTC IP leaks (uBlock)", Domains: []string{},
			JS: "window.RTCPeerConnection=function(){};window.webkitRTCPeerConnection=function(){};"},
		{Name: "window-name-defuser", Description: "Block window.name tracking (uBlock)", Domains: []string{},
			JS: "Object.defineProperty(window,'name',{value:'',writable:false});"},

		// --- XML pruning (VAST ad responses) ---
		{Name: "xml-prune", Description: "Prune XML ad responses (uBlock)", Domains: []string{},
			JS: "var _ps=DOMParser.prototype.parseFromString;DOMParser.prototype.parseFromString=function(t,ty){if((ty==='application/xml'||ty==='text/xml')&&t.match(/VAST|InLine|Impression/i)){t=t.replace(/<Ad>.*?<\\/Ad>/gs,'');}return _ps.call(this,t,ty);};"},

		// --- Misc ---
		{Name: "no-floc", Description: "Block FLoC/Topics API", Domains: []string{},
			JS: "Object.defineProperty(document,'interestCohort',{value:function(){return Promise.resolve({id:''});}});"},
		{Name: "disable-newtab-links", Description: "Disable ad links opening new tabs (uBlock)", Domains: []string{},
			JS: "document.querySelectorAll('a[target=_blank]').forEach(function(a){if(a.href.match(/ad|sponsor|promo/i)){a.target='_self';a.href='#';}});"},
		{Name: "alert-buster", Description: "Block alert() used by ad scripts (uBlock)", Domains: []string{},
			JS: "window.alert=function(){};window.confirm=function(){return true;};window.prompt=function(){return '';};"},
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
	for _, s := range scriptlets { sb.WriteString("/* " + s.Name + " */\n" + s.JS + "\n") }
	sb.WriteString("})();\n</script>\n")
	return sb.String()
}

func (e *Engine) AllScriptlets() []Scriptlet {
	e.mu.RLock(); defer e.mu.RUnlock()
	out := make([]Scriptlet, len(e.scriptlets))
	copy(out, e.scriptlets)
	return out
}
