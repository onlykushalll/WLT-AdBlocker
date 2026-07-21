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

                // === ANTI-ADBLOCK (5) — uBlock techniques ===
                {Name: "abort-current-script", Description: "Abort ad scripts (uBlock)", Domains: []string{},
                        JS: "Object.defineProperty(document,'Ads',{get:function(){throw new ReferenceError('WLT');}});"},
                {Name: "anti-adblock", Description: "Fake adblock detection", Domains: []string{},
                        JS: "Object.defineProperty(window,'adblock',{value:false,writable:false});Object.defineProperty(window,'adblockDetected',{value:false,writable:false});Object.defineProperty(window,'canRunAds',{value:true,writable:false});Object.defineProperty(window,'isAdBlockActive',{value:false,writable:false});"},
                {Name: "overlay-buster", Description: "Remove anti-adblock overlays", Domains: []string{},
                        JS: "var obs=new MutationObserver(function(muts){for(var m of muts){for(var n of m.addedNodes){if(n instanceof HTMLElement){var s=n.style;if(s&&(s.position==='fixed'||s.position==='absolute')&&s.zIndex>999&&(n.innerHTML.match(/adblock|ad blocker|disable/i))){n.remove();}}}}});obs.observe(document.body,{childList:true,subtree:true});"},
                {Name: "abort-on-property-read", Description: "Abort when ad script reads a property (uBlock)", Domains: []string{},
                        JS: "var _aopr=function(chain){var parts=chain.split('.');var obj=window;for(var i=0;i<parts.length-1;i++){if(!obj[parts[i]])return;obj=obj[parts[i]];}var prop=parts[parts.length-1];Object.defineProperty(obj,prop,{get:function(){throw new ReferenceError('WLT blocked: '+chain);},set:function(){}});};_aopr('document.ads');_aopr('window.adblock');"},
                {Name: "abort-on-property-write", Description: "Abort when ad script writes a property (uBlock)", Domains: []string{},
                        JS: "var _aopw=function(chain){var parts=chain.split('.');var obj=window;for(var i=0;i<parts.length-1;i++){if(!obj[parts[i]])return;obj=obj[parts[i]];}var prop=parts[parts.length-1];Object.defineProperty(obj,prop,{set:function(){throw new ReferenceError('WLT blocked: '+chain);}});};"},

                // === POPUP BLOCKING (2) ===
                {Name: "prevent-window-open", Description: "Block popup windows", Domains: []string{},
                        JS: "window.open=function(){return null;};"},
                {Name: "close-window", Description: "Auto-close ad popups", Domains: []string{},
                        JS: "if(window.opener&&window.name&&window.name.match(/ad|popup/i)){window.close();}"},

                // === DOM MANIPULATION (4) ===
                {Name: "remove-class", Description: "Remove ad CSS classes", Domains: []string{},
                        JS: "var adCl=['ad','ads','advert','advertisement','ad-banner','ad-container','sponsor','sponsored','promo'];adCl.forEach(function(c){document.querySelectorAll('.'+c).forEach(function(el){el.style.display='none';});});"},
                {Name: "prevent-refresh", Description: "Block meta refresh redirects", Domains: []string{},
                        JS: "document.querySelectorAll('meta[http-equiv=refresh]').forEach(function(m){m.remove();});"},
                {Name: "remove-node-text", Description: "Remove text from ad DOM nodes (uBlock)", Domains: []string{},
                        JS: "var _rnt=function(tag,needle){var re=new RegExp(needle,'i');document.querySelectorAll(tag).forEach(function(el){if(re.test(el.textContent)){el.textContent='';}});};_rnt('script','adsbygoogle');_rnt('script','doubleclick');"},
                {Name: "replace-node-text", Description: "Replace text in DOM nodes (uBlock)", Domains: []string{},
                        JS: "var _rntext=function(tag,from,to){document.querySelectorAll(tag).forEach(function(el){el.textContent=el.textContent.replace(new RegExp(from,'g'),to);});};"},

                // === TIMER MANIPULATION (2) ===
                {Name: "adjust-setInterval", Description: "Slow ad rotation timers", Domains: []string{},
                        JS: "var _si=window.setInterval;window.setInterval=function(fn,d){return d<5000&&d>0?_si(fn,d*10):_si(fn,d);};"},
                {Name: "adjust-setTimeout", Description: "Slow ad display timers", Domains: []string{},
                        JS: "var _st=window.setTimeout;window.setTimeout=function(fn,d){return d<3000&&d>0?_st(fn,d*10):_st(fn,d)};"},

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

                // === YOUTUBE (5) ===
                {Name: "yt-player-intercept", Description: "YouTube: remove ad placements from player response",
                        Domains: []string{"youtube.com", "www.youtube.com", "m.youtube.com"},
                        JS: "try{var r=window.ytInitialPlayerResponse;if(r){r.adPlacements=[];r.adSlots=[];r.playerAds=[];}Object.defineProperty(window,'ytInitialPlayerResponse',{value:r,writable:false});}catch(e){}try{var d=window.ytInitialData;if(d){var s=JSON.stringify(d);s=s.replace(/\"adPlacements\"[^]]+]/g,'\"adPlacements\":[]');window.ytInitialData=JSON.parse(s);}}catch(e){}"},
                {Name: "yt-speed-up-ads", Description: "YouTube: speed up ads 16x + mute + skip",
                        Domains: []string{"youtube.com", "www.youtube.com", "m.youtube.com"},
                        JS: "setInterval(function(){var v=document.querySelector('video');if(!v)return;var ad=document.querySelector('.ytp-ad-player-overlay')||document.querySelector('.ad-showing');if(ad){v.playbackRate=16;v.muted=true;if(v.duration>0)v.currentTime=v.duration;var s=document.querySelector('.ytp-ad-skip-button')||document.querySelector('.ytp-ad-skip-button-modern');if(s)s.click();}},100);"},
                {Name: "yt-remove-ad-survey", Description: "YouTube: remove ad survey overlays",
                        Domains: []string{"youtube.com"},
                        JS: "setInterval(function(){document.querySelectorAll('.ytp-ad-survey,.ytp-ad-overlay-close-container,ytd-ad-slot-renderer,ytd-promoted-video-renderer').forEach(function(el){el.remove();});},500);"},
                {Name: "yt-block-ads-request", Description: "YouTube: block ad API calls",
                        Domains: []string{"youtube.com"},
                        JS: "var _yf=window.fetch;window.fetch=function(u,o){var s=typeof u==='string'?u:(u&&u.url||'');if(s.match(/\\/api\\/stats\\/ads|\\/get_video_stats|\\/ptracking|ad_service/))return new Promise(function(){});return _yf.apply(this,arguments);};var _yx=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){if(u&&u.match(/\\/api\\/stats\\/ads|\\/ptracking|ad_service/))throw new Error('WLT');return _yx.apply(this,arguments);};"},

                // === SPOTIFY (3) ===
                {Name: "spotify-ad-intercept", Description: "Spotify: intercept ad API responses",
                        Domains: []string{"spclient.wg.spotify.com", "api.spotify.com"},
                        JS: "var _sf=window.fetch;window.fetch=function(u,o){var s=typeof u==='string'?u:(u&&u.url||'');if(s.match(/\\/ad-ads|\\/ads\\/|\\/audio-ad|\\/partner-ad|ad_slot/))return new Promise(function(){});return _sf.apply(this,arguments);};"},

                // === TRUSTED SCRIPTLETS (3) — higher privilege ===
                {Name: "trusted-replace-fetch-response", Description: "Modify fetch responses in real-time (uBlock trusted)", Domains: []string{},
                        JS: "var _trf=window.fetch;window.fetch=function(){var p=_trf.apply(this,arguments);return p.then(function(r){return r.clone();});};"},
                {Name: "trusted-replace-xhr-response", Description: "Modify XHR responses (uBlock trusted)", Domains: []string{},
                        JS: "var _xhr=XMLHttpRequest;window.XMLHttpRequest=function(){var x=new _xhr();var _open=x.open;x.open=function(m,u){_open.apply(x,arguments);var _get=Object.getOwnPropertyDescriptor(x,'response');Object.defineProperty(x,'response',{get:function(){var r=_get?_get.get.call(x):x.response;return r;}});};return x;};"},
                {Name: "trusted-click-element", Description: "Auto-click elements (uBlock trusted)", Domains: []string{},
                        JS: "window.__wltClick=function(sel){var el=document.querySelector(sel);if(el){el.click();return true;}return false;};"},

                // === ADDITIONAL HARDCENING (3) ===
                {Name: "break-on-call", Description: "Break specific function calls (uBlock)", Domains: []string{},
                        JS: "window.__wltBreak=function(obj,prop){var orig=obj[prop];obj[prop]=function(){throw new Error('WLT blocked: '+prop);};return function(){obj[prop]=orig;};};"},
                {Name: "call-nothrow", Description: "Wrap function calls to suppress errors (uBlock)", Domains: []string{},
                        JS: "window.__wltSafeCall=function(fn){try{return fn();}catch(e){return undefined;}};"},
                {Name: "json-prune", Description: "Remove properties from JSON responses (uBlock)", Domains: []string{},
                        JS: "var _jp=JSON.parse;JSON.parse=function(text){var r=_jp.apply(this,arguments);if(r&&typeof r==='object'){if(r.ads)delete r.ads;if(r.adPlacements)r.adPlacements=[];if(r.adSlots)r.adSlots=[];if(r.playerAds)r.playerAds=[];}return r;};"},

                // === SPONSORBLOCK ===
                {Name: "yt-sponsorblock", Description: "YouTube: SponsorBlock-style skip (basic)",
                        Domains: []string{"youtube.com"},
                        JS: "setInterval(function(){var v=document.querySelector('video');if(!v||!v.duration)return;var segs=document.querySelectorAll('.ytp-progress-bar [data-sponsor]');segs.forEach(function(seg){var st=parseFloat(seg.getAttribute('data-start'));var en=parseFloat(seg.getAttribute('data-end'));if(v.currentTime>=st&&v.currentTime<en-0.5)v.currentTime=en;});},1000);"},

                // === TWITCH (3) — video-swap proxy approach ===
                {Name: "twitch-video-swap", Description: "Twitch: swap ad stream with commercial break screen",
                        Domains: []string{"twitch.tv", "www.twitch.tv", "m.twitch.tv"},
                        JS: `// TwitchAdSolutions video-swap technique (pixeltris/TwitchAdSolutions)
                        var twitchAdCheck=setInterval(function(){
                                var v=document.querySelector('video');
                                if(!v)return;
                                // Check if ad is playing (Twitch shows .ad-banner or changes player state)
                                var adBanner=document.querySelector('.ad-banner,.video-banner,.player-banner');
                                var isAd=v.duration>0&&v.duration<31&&!v.controls;
                                if(adBanner||isAd){
                                        // Mute and speed up
                                        v.muted=true;
                                        v.playbackRate=16;
                                        if(v.duration>0&&v.duration<31){v.currentTime=v.duration;}
                                        // Try to skip
                                        var skipBtn=document.querySelector('.player-overlay button');
                                        if(skipBtn)skipBtn.click();
                                }
                        },200);`},
                {Name: "twitch-mute-ads", Description: "Twitch: mute + hide ad overlays",
                        Domains: []string{"twitch.tv"},
                        JS: `var twMuteAds=setInterval(function(){
                                var v=document.querySelector('video');
                                if(!v)return;
                                // Twitch ads are short (15-30s) with no controls
                                if(v.duration>0&&v.duration<31){
                                        v.muted=true;
                                        v.volume=0;
                                        // Hide ad overlay
                                        var overlays=document.querySelectorAll('.ad-banner,.video-banner,.player-banner,.tw-banner');
                                        overlays.forEach(function(el){el.style.display='none';});
                                }
                        },100);`},
                {Name: "twitch-block-ad-request", Description: "Twitch: block ad API requests",
                        Domains: []string{"twitch.tv"},
                        JS: `var _twFetch=window.fetch;
                        window.fetch=function(u,o){
                                var s=typeof u==='string'?u:(u&&u.url||'');
                                if(s.match(/\/api\/channel\/.*\/ads|\/gql.*ad|\/spade|trow\.twitch/))
                                        return new Promise(function(){});
                                return _twFetch.apply(this,arguments);
                        };
                        var _twXhr=XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open=function(m,u){
                                if(u&&u.match(/\/api\/channel\/.*\/ads|\/spade|trow\.twitch/))
                                        throw new Error('WLT: Twitch ad blocked');
                                return _twXhr.apply(this,arguments);
                        };`},

                // === REDDIT/TWITTER SPONSORED CONTENT (2) ===
                {Name: "reddit-hide-promoted", Description: "Reddit: hide promoted posts",
                        Domains: []string{"reddit.com", "www.reddit.com", "old.reddit.com"},
                        JS: `var rmPromoted=setInterval(function(){
                                document.querySelectorAll('[data-promoted=true],.promotedlink,.promoted,promotedlink').forEach(function(el){
                                        el.style.display='none';
                                });
                                // New Reddit
                                document.querySelectorAll('shreddit-post').forEach(function(el){
                                        if(el.getAttribute('promoted')==='true')el.style.display='none';
                                });
                        },500);`},
                {Name: "twitter-hide-promoted", Description: "Twitter/X: hide promoted tweets",
                        Domains: []string{"twitter.com","x.com","www.x.com"},
                        JS: `var rmPromotedTweets=setInterval(function(){
                                document.querySelectorAll('[data-testid="placementTracking"]').forEach(function(el){
                                        if(el.textContent.match(/Promoted/i))el.style.display='none';
                                });
                                document.querySelectorAll('article').forEach(function(art){
                                        if(art.textContent.match(/Promoted by/i))art.style.display='none';
                                });
                        },500);`},

                // === INSTAGRAM SPONSORED (1) ===
                {Name: "instagram-hide-sponsored", Description: "Instagram: hide sponsored posts via procedural filter",
                        Domains: []string{"instagram.com","www.instagram.com"},
                        JS: `// Instagram splits "Sponsored" across spans — use procedural matching
                        var rmIgSponsored=setInterval(function(){
                                document.querySelectorAll('article').forEach(function(art){
                                        // Check for "Sponsored" text (may be split across spans)
                                        var text=art.textContent;
                                        if(text.match(/Sponsored|Paid partnership/i)){
                                                art.style.display='none';
                                        }
                                        // Also check for sponsored label structure
                                        var sponsorLabel=art.querySelector('[data-sponsorship]');
                                        if(sponsorLabel)art.style.display='none';
                                });
                        },1000);`},

                // === CRYPTO MINING BLOCKING (1) ===
                {Name: "block-crypto-miners", Description: "Block in-browser crypto miners (WASM)",
                        Domains: []string{},
                        JS: `// Block WebAssembly-based miners
                        if(typeof WebAssembly!=='undefined'){
                                var _wasmInstantiate=WebAssembly.instantiate;
                                WebAssembly.instantiate=function(source,importObject){
                                        // Check if the WASM module looks like a miner (large binary, imports threads)
                                        var srcStr=source instanceof ArrayBuffer?new Uint8Array(source):source;
                                        if(srcStr&&srcStr.length>10000){
                                                var hasThread=importObject&&importObject.env&&importObject.env.memory;
                                                if(hasThread||srcStr.length>100000){
                                                        return Promise.reject(new Error('WLT: Crypto miner blocked'));
                                                }
                                        }
                                        return _wasmInstantiate.apply(this,arguments);
                                };
                                var _wasmInstantiateStreaming=WebAssembly.instantiateStreaming;
                                if(_wasmInstantiateStreaming){
                                        WebAssembly.instantiateStreaming=function(response,importObject){
                                                var url=response&&response.url||'';
                                                if(url.match(/miner|coinhive|cryptonight|monero|wasm.*min/i)){
                                                        return Promise.reject(new Error('WLT: Miner blocked'));
                                                }
                                                return _wasmInstantiateStreaming.apply(this,arguments);
                                        };
                                }
                        }
                        // Also block Worker-based miners
                        var _worker=window.Worker;
                        window.Worker=function(url){
                                var s=typeof url==='string'?url:(url&&url.url||'');
                                if(s.match(/miner|coinhive|cryptonight|monero/i)){
                                        throw new Error('WLT: Miner worker blocked');
                                }
                                return new _worker(url);
                        };
                        window.Worker.prototype=_worker.prototype;`},
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
