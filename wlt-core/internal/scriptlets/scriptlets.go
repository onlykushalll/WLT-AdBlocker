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

		// === AD NETWORK NEUTRALIZATION (7) ===
		{Name: "adsbygoogle", Description: "Neutralize AdSense", Domains: []string{"googlesyndication.com"},
			JS: "self.adsbygoogle={loaded:true,push:function(){}};"},
		{Name: "doubleclick", Description: "DoubleClick instream", Domains: []string{"doubleclick.net"},
			JS: "window.google_ad_status=1;"},
		{Name: "googletag", Description: "Google Ad Manager", Domains: []string{"googletagservices.com", "doubleclick.net"},
			JS: "window.googletag={cmd:[],defineSlot:function(){return{setTargeting:function(){return this;},addService:function(){return this;}};},enableServices:function(){},display:function(){},pubads:function(){return{refresh:function(){},setTargeting:function(){return this;}};}};"},
		{Name: "google-analytics", Description: "GA blocking", Domains: []string{"google-analytics.com"},
			JS: "window.ga=function(){};window.gtag=function(){};window.dataLayer={push:function(){}};"},
		{Name: "facebook-pixel", Description: "FB Pixel", Domains: []string{"connect.facebook.net"},
			JS: "window.fbq=function(){};window._fbq=function(){};"},
		{Name: "twitter-ads", Description: "Twitter ads", Domains: []string{"ads-twitter.com", "platform.twitter.com"},
			JS: "window.twttr={ads:{}};"},
		{Name: "amazon-ads", Description: "Amazon ads", Domains: []string{"amazon-adsystem.com"},
			JS: "window.amznads=function(){};"},

		// === NETWORK BLOCKING (3) ===
		{Name: "fetch-blocker", Description: "Block fetch to ad endpoints", Domains: []string{},
			JS: "var _f=window.fetch;window.fetch=function(u,o){if(typeof u==='string'&&/doubleclick|googlesyndication|adservice|adclick|adsystem|ads-twitter|amazon-adsystem|spotify.*ad|audio.*ad/.test(u))return new Promise(function(){});return _f.apply(this,arguments)};"},
		{Name: "xhr-blocker", Description: "Block XHR to ad endpoints", Domains: []string{},
			JS: "var _o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(/doubleclick|googlesyndication|adservice|adclick/.test(u))throw new Error('WLT');return _o.apply(this,arguments)};"},
		{Name: "noeval", Description: "Block eval()", Domains: []string{},
			JS: "window.eval=function(){return undefined;};"},

		// === ANTI-ADBLOCK (3) ===
		{Name: "abort-current-script", Description: "Abort ad scripts (uBlock)", Domains: []string{},
			JS: "Object.defineProperty(document,'Ads',{get:function(){throw new ReferenceError('WLT');}});"},
		{Name: "anti-adblock", Description: "Fake adblock detection", Domains: []string{},
			JS: "Object.defineProperty(window,'adblock',{value:false,writable:false});Object.defineProperty(window,'adblockDetected',{value:false,writable:false});Object.defineProperty(window,'canRunAds',{value:true,writable:false});Object.defineProperty(window,'isAdBlockActive',{value:false,writable:false});"},
		{Name: "overlay-buster", Description: "Remove anti-adblock overlays", Domains: []string{},
			JS: "var obs=new MutationObserver(function(muts){for(var m of muts){for(var n of m.addedNodes){if(n instanceof HTMLElement){var s=n.style;if(s&&(s.position==='fixed'||s.position==='absolute')&&s.zIndex>999&&(n.innerHTML.match(/adblock|ad blocker|disable/i))){n.remove();}}}}});obs.observe(document.body,{childList:true,subtree:true});"},

		// === POPUP BLOCKING (2) ===
		{Name: "prevent-window-open", Description: "Block popup windows", Domains: []string{},
			JS: "window.open=function(){return null;};"},
		{Name: "close-window", Description: "Auto-close ad popups", Domains: []string{},
			JS: "if(window.opener&&window.name&&window.name.match(/ad|popup/i)){window.close();}"},

		// === DOM MANIPULATION (2) ===
		{Name: "remove-class", Description: "Remove ad CSS classes", Domains: []string{},
			JS: "var adCl=['ad','ads','advert','advertisement','ad-banner','ad-container','sponsor','sponsored','promo'];adCl.forEach(function(c){document.querySelectorAll('.'+c).forEach(function(el){el.style.display='none';});});"},
		{Name: "prevent-refresh", Description: "Block meta refresh redirects", Domains: []string{},
			JS: "document.querySelectorAll('meta[http-equiv=refresh]').forEach(function(m){m.remove();});"},

		// === TIMER MANIPULATION (2) ===
		{Name: "adjust-setInterval", Description: "Slow ad rotation timers", Domains: []string{},
			JS: "var _si=window.setInterval;window.setInterval=function(fn,d){return d<5000&&d>0?_si(fn,d*10):_si(fn,d);};"},
		{Name: "adjust-setTimeout", Description: "Slow ad display timers", Domains: []string{},
			JS: "var _st=window.setTimeout;window.setTimeout=function(fn,d){return d<3000&&d>0?_st(fn,d*10):_st(fn,d);};"},

		// === PRIVACY (4) ===
		{Name: "prevent-canvas", Description: "Block canvas fingerprinting", Domains: []string{},
			JS: "HTMLCanvasElement.prototype.toDataURL=function(){return 'data:,';};"},
		{Name: "webrtc-if", Description: "Block WebRTC IP leaks", Domains: []string{},
			JS: "window.RTCPeerConnection=function(){};window.webkitRTCPeerConnection=function(){};"},
		{Name: "window-name-defuser", Description: "Block window.name tracking", Domains: []string{},
			JS: "Object.defineProperty(window,'name',{value:'',writable:false});"},
		{Name: "no-floc", Description: "Block FLoC/Topics API", Domains: []string{},
			JS: "Object.defineProperty(document,'interestCohort',{value:function(){return Promise.resolve({id:''});}});"},

		// === XML/M3U PRUNING (1) ===
		{Name: "xml-prune", Description: "Prune VAST XML ad responses", Domains: []string{},
			JS: "var _ps=DOMParser.prototype.parseFromString;DOMParser.prototype.parseFromString=function(t,ty){if((ty==='application/xml'||ty==='text/xml')&&t.match(/VAST|InLine|Impression/i)){t=t.replace(/<Ad>.*?<\\/Ad>/gs,'');}return _ps.call(this,t,ty);};"},

		// === MISC (2) ===
		{Name: "disable-newtab-links", Description: "Disable ad new tab links", Domains: []string{},
			JS: "document.querySelectorAll('a[target=_blank]').forEach(function(a){if(a.href.match(/ad|sponsor|promo/i)){a.target='_self';a.href='#';}});"},
		{Name: "alert-buster", Description: "Block alert() from ad scripts", Domains: []string{},
			JS: "window.alert=function(){};window.confirm=function(){return true;};window.prompt=function(){return '';};"},

		// === YOUTUBE SPECIFIC (5) — Phase 3 HTTPS MITM ===
		{Name: "yt-player-intercept", Description: "YouTube: intercept player response, remove ad placements",
			Domains: []string{"youtube.com", "www.youtube.com", "m.youtube.com"},
			JS: `var _defineProperty=Object.defineProperty;
			try{
				var origResponse=window.ytInitialPlayerResponse;
				if(origResponse){
					if(origResponse.adPlacements)origResponse.adPlacements=[];
					if(origResponse.adSlots)origResponse.adSlots=[];
					if(origResponse.playerAds)origResponse.playerAds=[];
					if(origResponse.auxiliaryUi)origResponse.auxiliaryUi={messageRenderers:{}};
				}
				_defineProperty(window,'ytInitialPlayerResponse',{value:origResponse,writable:false,configurable:false});
			}catch(e){}
			// Also intercept ytInitialData for home page ads
			try{
				var origData=window.ytInitialData;
				if(origData){
					var str=JSON.stringify(origData);
					str=str.replace(/"adSlotsRegex"[^}]+}/g,'{}');
					str=str.replace(/"adPlacements"[^]]+]/g,'"adPlacements":[]');
					window.ytInitialData=JSON.parse(str);
				}
			}catch(e){}`},
		{Name: "yt-speed-up-ads", Description: "YouTube: speed up ads 16x + mute + skip",
			Domains: []string{"youtube.com", "www.youtube.com", "m.youtube.com"},
			JS: `var skipCheck=setInterval(function(){
				var v=document.querySelector('video');
				if(!v)return;
				var adShowing=document.querySelector('.ytp-ad-player-overlay')||document.querySelector('.ad-showing');
				if(adShowing){
					v.playbackRate=16;
					v.muted=true;
					if(v.duration>0){v.currentTime=v.duration;}
					var skipBtn=document.querySelector('.ytp-ad-skip-button')||document.querySelector('.ytp-ad-skip-button-modern');
					if(skipBtn)skipBtn.click();
				}
			},100);
			// Also intercept the polymer player config
			try{
				var _cfg=window.ytplayer;
				if(_cfg&&_cfg.config){
					_cfg.config.args=_cfg.config.args||{};
				}
			}catch(e){}`},
		{Name: "yt-remove-ad-survey", Description: "YouTube: remove ad survey overlays",
			Domains: []string{"youtube.com"},
			JS: `var rmSurvey=setInterval(function(){
				document.querySelectorAll('.ytp-ad-survey,.ytp-ad-overlay-close-container,.style-scope.ytd-ad-slot-renderer').forEach(function(el){el.remove();});
				var adSlots=document.querySelectorAll('ytd-ad-slot-renderer,ytd-promoted-video-renderer');
				adSlots.forEach(function(el){el.style.display='none';el.remove();});
			},500);`},
		{Name: "yt-block-ads-request", Description: "YouTube: block ad request API calls",
			Domains: []string{"youtube.com"},
			JS: `var _ytFetch=window.fetch;
			window.fetch=function(url,opts){
				var u=typeof url==='string'?url:(url&&url.url||'');
				if(u.match(/\/api\/stats\/ads|\/get_video_stats|\/ptracking|\/api\/timedtext.*ad/)){
					return new Promise(function(){});
				}
				return _ytFetch.apply(this,arguments);
			};
			var _ytXhr=XMLHttpRequest.prototype.open;
			XMLHttpRequest.prototype.open=function(method,url){
				if(url&&url.match(/\/api\/stats\/ads|\/ptracking|ad_service/)){
					throw new Error('WLT: YouTube ad request blocked');
				}
				return _ytXhr.apply(this,arguments);
			};`},
		{Name: "yt-sponsorblock", Description: "YouTube: SponsorBlock-style skip (basic)",
			Domains: []string{"youtube.com"},
			JS: `// Basic sponsor segment skip - checks for sponsor markers
			var sponsorSkip=setInterval(function(){
				var v=document.querySelector('video');
				if(!v||!v.duration)return;
				// Check for sponsor labels in the timeline
				var segments=document.querySelectorAll('.ytp-progress-bar [data-sponsor]');
				segments.forEach(function(seg){
					var start=parseFloat(seg.getAttribute('data-start'));
					var end=parseFloat(seg.getAttribute('data-end'));
					if(v.currentTime>=start&&v.currentTime<end-0.5){
						v.currentTime=end;
					}
				});
			},1000);`},

		// === SPOTIFY SPECIFIC (3) — Phase 3 HTTPS MITM ===
		{Name: "spotify-ad-intercept", Description: "Spotify: intercept ad API responses",
			Domains: []string{"spclient.wg.spotify.com", "api.spotify.com"},
			JS: `// Intercept Spotify ad API calls
			var _spFetch=window.fetch;
			window.fetch=function(url,opts){
				var u=typeof url==='string'?url:(url&&url.url||'');
				if(u.match(/\/ad-ads|\/ads\/|\/audio-ad|\/partner-ad|ad_slot/)){
					return new Promise(function(){
						// Never resolves - ad request silently dropped
					});
				}
				return _spFetch.apply(this,arguments);
			};`},
		{Name: "spotify-feature-flags", Description: "Spotify: override ad feature flags",
			Domains: []string{"spclient.wg.spotify.com"},
			JS: `// Override Spotify feature flags to disable ads
			try{
				if(window.Spotify){
					var orig=window.Spotify.Player;
					if(orig){
						window.Spotify.Player=function(){
							orig.apply(this,arguments);
							this._options=this._options||{};
							this._options.enableAds=false;
							this._options.isPremium=true;
						};
						window.Spotify.Player.prototype=orig.prototype;
					}
				}
			}catch(e){}
			// Also intercept feature flag endpoint
			var _spXhr=XMLHttpRequest.prototype.open;
			XMLHttpRequest.prototype.open=function(m,u){
				if(u&&u.match(/feature-flags|ad-format/)){
					// Return empty ad config
					var self=this;
					setTimeout(function(){
						Object.defineProperty(self,'responseText',{value:'{"ads":{},"enableAudioAds":false}'});
						Object.defineProperty(self,'readyState',{value:4});
						if(self.onreadystatechange)self.onreadystatechange();
					},10);
					return;
				}
				return _spXhr.apply(this,arguments);
			};`},
		{Name: "spotify-audio-ad-block", Description: "Spotify: block audio ad slot events",
			Domains: []string{"spclient.wg.spotify.com", "apresolve.spotify.com"},
			JS: `// Block Spotify audio ad slot events
			var adEvents=['ad-slot','audio-ad','ad-partner','ad-impression'];
			var _spEvt=window.dispatchEvent;
			window.dispatchEvent=function(evt){
				if(evt&&evt.type&&adEvents.some(function(e){return evt.type.indexOf(e)>=0;})){
					return true; // Swallow ad events
				}
				return _spEvt.apply(this,arguments);
			};
			// Override EventTarget.addEventListener to filter ad events
			var _spAEL=EventTarget.prototype.addEventListener;
			EventTarget.prototype.addEventListener=function(type,fn,opts){
				if(type&&adEvents.some(function(e){return type.indexOf(e)>=0;})){
					return; // Don't register ad event listeners
				}
				return _spAEL.apply(this,arguments);
			};`},
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
