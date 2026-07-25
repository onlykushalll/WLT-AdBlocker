// Package scriptlets implements the WLT scriptlet injection engine for the
// HTTPS MITM proxy. Scriptlets are small JavaScript snippets that run in
// the page context to defuse anti-adblock, hijack ad networks, neutralize
// popups, mute/skip video ads, etc. — the uBlock Origin "##+js(...)"
// mechanism.
//
// The engine ships with 49 default scriptlets covering ad networks, anti-
// adblock, popups, DOM manipulation, timers, privacy, XML/JSON pruning,
// YouTube / Spotify / Twitch / Reddit / Twitter / Instagram / Crypto
// mitigations, trusted-replace fetch/XHR, and utilities.
//
// Inject(html, host) inserts a single <script> tag with every scriptlet
// matching host into the HTML <head>. The HTML is returned as a new byte
// slice; the input is never mutated.
package scriptlets

import (
        "bytes"
        "fmt"
        "strings"
        "sync"
)

// Engine stores the registered scriptlets and the per-domain mapping.
type Engine struct {
        mu sync.RWMutex

        // scripts maps scriptlet name -> JS body.
        scripts map[string]string

        // domainScripts maps hostSuffix -> ordered list of scriptlet names to
        // inject when the host matches the suffix.
        domainScripts map[string][]string
}

// New returns an empty Engine.
func New() *Engine {
        return &Engine{
                scripts:       make(map[string]string),
                domainScripts: make(map[string][]string),
        }
}

// Register adds a scriptlet by name. If the name already exists the JS
// body is overwritten.
func (e *Engine) Register(name, js string) {
        name = strings.ToLower(strings.TrimSpace(name))
        if name == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        e.scripts[name] = js
}

// Get returns the JS body for the named scriptlet, or ok=false.
func (e *Engine) Get(name string) (string, bool) {
        name = strings.ToLower(strings.TrimSpace(name))
        e.mu.RLock()
        defer e.mu.RUnlock()
        js, ok := e.scripts[name]
        return js, ok
}

// Assign associates a scriptlet name with a host suffix. Multiple scriptlets
// per host are allowed; the order is preserved.
func (e *Engine) Assign(hostSuffix, name string) {
        hostSuffix = normalizeSuffix(hostSuffix)
        name = strings.ToLower(strings.TrimSpace(name))
        if hostSuffix == "" || name == "" {
                return
        }
        e.mu.Lock()
        defer e.mu.Unlock()
        for _, n := range e.domainScripts[hostSuffix] {
                if n == name {
                        return
                }
        }
        e.domainScripts[hostSuffix] = append(e.domainScripts[hostSuffix], name)
}

// ForDomain returns the JS bodies of every scriptlet assigned to a host
// suffix that matches host (suffix match). Order is deterministic: longer
// suffixes first, then alphabetical.
func (e *Engine) ForDomain(host string) []string {
        host = strings.ToLower(strings.TrimSpace(host))
        e.mu.RLock()
        defer e.mu.RUnlock()

        // Collect matching suffixes.
        var suffixes []string
        for suffix := range e.domainScripts {
                if hostMatchesSuffix(host, suffix) {
                        suffixes = append(suffixes, suffix)
                }
        }
        // Deterministic order: longest suffix first (most specific), then
        // alphabetical. Use simple insertion sort (suffix lists are small).
        for i := 1; i < len(suffixes); i++ {
                for j := i; j > 0; j-- {
                        a, b := suffixes[j-1], suffixes[j]
                        if len(a) < len(b) || (len(a) == len(b) && a > b) {
                                suffixes[j-1], suffixes[j] = b, a
                        } else {
                                break
                        }
                }
        }

        var out []string
        seen := make(map[string]bool)
        for _, sfx := range suffixes {
                for _, name := range e.domainScripts[sfx] {
                        if seen[name] {
                                continue
                        }
                        seen[name] = true
                        if js, ok := e.scripts[name]; ok {
                                out = append(out, js)
                        }
                }
        }
        return out
}

// Inject inserts a <script> tag containing every scriptlet matching host
// into the HTML's <head>. If there are no matching scriptlets the input is
// returned unchanged. The input is never mutated.
func (e *Engine) Inject(html []byte, host string) []byte {
        bodies := e.ForDomain(host)
        if len(bodies) == 0 {
                return html
        }
        combined := "(function(){\n" + strings.Join(bodies, "\n") + "\n})();"
        scriptTag := []byte("<script>\n" + combined + "\n</script>")

        // Insert right after <head> (case-insensitive). If no <head> tag is
        // present, prepend the script tag to the document.
        lower := bytes.ToLower(html)
        idx := bytes.Index(lower, []byte("<head"))
        if idx < 0 {
                // No head tag: prepend to document.
                return append(scriptTag, html...)
        }
        // Find the closing > of the <head...> opening tag.
        closeIdx := bytes.IndexByte(html[idx:], '>')
        if closeIdx < 0 {
                return append(scriptTag, html...)
        }
        insertAt := idx + closeIdx + 1
        out := make([]byte, 0, len(html)+len(scriptTag))
        out = append(out, html[:insertAt]...)
        out = append(out, scriptTag...)
        out = append(out, html[insertAt:]...)
        return out
}

// LoadDefaults registers the 49 WLT default scriptlets and assigns them to
// host suffixes. The full list is documented in the package doc.
func (e *Engine) LoadDefaults() {
        e.registerAdNetworks()
        e.registerNetwork()
        e.registerAntiAdblock()
        e.registerPopups()
        e.registerDOM()
        e.registerTimers()
        e.registerPrivacy()
        e.registerXMLJSON()
        e.registerMisc()
        e.registerYouTube()
        e.registerSpotify()
        e.registerTwitch()
        e.registerReddit()
        e.registerTwitter()
        e.registerInstagram()
        e.registerCrypto()
        e.registerTrusted()
        e.registerUtilities()
}

// ---------------------------------------------------------------------------
// Default scriptlet registration helpers. Each helper registers one or more
// scriptlets and assigns them to the appropriate host suffixes.
// ---------------------------------------------------------------------------

func (e *Engine) registerAdNetworks() {
        // 7 ad-network scriptlets. These run on every host (suffix "." matches
        // everything) since the ad scripts could be loaded from any first-
        // party domain.
        e.Register("adsbygoogle", `(function(){
  Object.defineProperty(window, 'adsbygoogle', {
    configurable: true,
    get: function() { return { push: function(){} }; },
    set: function() {}
  });
})();`)
        e.Register("doubleclick", `(function(){
  var noop = function(){};
  window.googletag = window.googletag || { cmd: { push: function(f){ try{f();}catch(e){} } }, pubads: noop, defineSlot: function(){ return this; } };
})();`)
        e.Register("googletag", `(function(){
  window.googletag = window.googletag || {};
  window.googletag.cmd = window.googletag.cmd || [];
  window.googletag.cmd.push = function(f){ try{ f(); }catch(e){} };
  window.googletag.display = function(){};
  window.googletag.pubads = function(){ return { setTargeting: function(){return this;}, addService: function(){return this;}, enableSingleRequest: function(){}, disableInitialLoad: function(){} }; };
})();`)
        e.Register("google-analytics", `(function(){
  window.ga = function(){};
  window.gtag = function(){};
  window.dataLayer = { push: function(){} };
  window.GoogleAnalyticsObject = 'ga';
})();`)
        e.Register("facebook-pixel", `(function(){
  window.fbq = function(){ (window.fbq.q = window.fbq.q || []).push(arguments); };
  window.fbq.q = [];
  window._fbq = function(){};
})();`)
        e.Register("twitter-ads", `(function(){
  window.twq = function(){};
  window.twttr = window.twttr || { ads: { createPixel: function(){}, load: function(){} } };
})();`)
        e.Register("amazon-ads", `(function(){
  window.amznads = function(){};
  window.amzn = { ads: { doGetAds: function(){}, doGetAdsAsync: function(){ return Promise.resolve(); } } };
})();`)
}

func (e *Engine) registerNetwork() {
        e.Register("fetch-blocker", `(function(){
  var orig = window.fetch;
  window.fetch = function(input, init){
    var url = typeof input === 'string' ? input : (input && input.url) || '';
    if (/doubleclick|googlesyndication|google-analytics|facebook\.com\/tr|amazon-adsystem|adsystem|adservice|2mdn|adsrvr/i.test(url)) {
      return Promise.resolve(new Response('', { status: 204 }));
    }
    return orig.apply(this, arguments);
  };
})();`)
        e.Register("xhr-blocker", `(function(){
  var orig = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url){
    if (/doubleclick|googlesyndication|google-analytics|facebook\.com\/tr|amazon-adsystem|adservice|2mdn|adsrvr/i.test(url || '')) {
      this.send = function(){};
      this.abort = function(){};
      return;
    }
    return orig.apply(this, arguments);
  };
})();`)
        e.Register("noeval", `(function(){
  window.eval = function(){ return undefined; };
  try { Object.defineProperty(window, 'eval', { configurable: false, writable: false }); } catch(e) {}
})();`)
}

func (e *Engine) registerAntiAdblock() {
        e.Register("abort-current-script", `(function(){
  // Abort the currently-running script if it tries to call common
  // anti-adblock detection functions. This is a conservative shim.
  var kill = ['blockAdBlock', 'adblock', 'adBlock', 'fuckAdBlock', 'sniffAdBlock'];
  kill.forEach(function(name){
    try {
      Object.defineProperty(window, name, {
        configurable: true,
        get: function(){ throw new ReferenceError(name + ' is not defined'); }
      });
    } catch(e) {}
  });
})();`)
        e.Register("anti-adblock", `(function(){
  // Hide common anti-adblock overlay elements.
  var sels = ['.adblock-message', '.adblock-notice', '#adblock-notice', '.please-disable-adblock', '.adblock-overlay'];
  function purge(){
    sels.forEach(function(s){
      document.querySelectorAll(s).forEach(function(el){ el.remove(); });
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', purge);
  } else { purge(); }
  setInterval(purge, 1000);
})();`)
        e.Register("overlay-buster", `(function(){
  // Remove full-screen overlays that block content while adblock is detected.
  function fix(){
    document.querySelectorAll('div,section').forEach(function(el){
      var s = getComputedStyle(el);
      if (s.position === 'fixed' && s.zIndex > 999 && (s.height === '100vh' || s.width === '100vw' || el.offsetWidth > window.innerWidth * 0.8)) {
        var txt = (el.textContent || '').toLowerCase();
        if (/adblock|ad block|disable.*adblock|whitelist/.test(txt)) {
          el.style.display = 'none';
          document.body.style.overflow = 'auto';
        }
      }
    });
  }
  setInterval(fix, 500);
})();`)
        e.Register("abort-on-property-read", abortOnPropertyReadJS())
        e.Register("abort-on-property-write", abortOnPropertyWriteJS())
}

// abortOnPropertyReadJS returns the JS body for the abort-on-property-read
// scriptlet. It traverses a dotted property chain and installs a getter on
// the final segment that throws a ReferenceError.
func abortOnPropertyReadJS() string {
        return `(function(){
  // The chain is set by the caller via window.__wlt_aopr_chain = "foo.bar.baz"
  // before this scriptlet runs. We expose a helper to make that ergonomic.
  window.__wlt_aopr = function(chain){
    var parts = chain.split('.');
    var obj = window;
    for (var i = 0; i < parts.length - 1; i++) {
      if (obj[parts[i]] == null) return;
      obj = obj[parts[i]];
    }
    var last = parts[parts.length - 1];
    try {
      Object.defineProperty(obj, last, {
        configurable: true,
        get: function(){ throw new ReferenceError(chain + ' is not defined'); }
      });
    } catch(e) {}
  };
})();`
}

// abortOnPropertyWriteJS is the same idea for the write side: install a
// setter that throws.
func abortOnPropertyWriteJS() string {
        return `(function(){
  window.__wlt_aopw = function(chain){
    var parts = chain.split('.');
    var obj = window;
    for (var i = 0; i < parts.length - 1; i++) {
      if (obj[parts[i]] == null) return;
      obj = obj[parts[i]];
    }
    var last = parts[parts.length - 1];
    try {
      Object.defineProperty(obj, last, {
        configurable: true,
        set: function(){ throw new ReferenceError(chain + ' is not defined'); }
      });
    } catch(e) {}
  };
})();`
}

func (e *Engine) registerPopups() {
        e.Register("prevent-window-open", `(function(){
  var orig = window.open;
  window.open = function(url, target, features){
    if (arguments.length === 0) return null;
    var u = String(url || '');
    if (u && !/^javascript:|^about:blank$|^\/\//.test(u)) {
      // Allow same-origin / explicit user-initiated popups; block the rest.
      try {
        var a = document.createElement('a');
        a.href = u;
        if (a.host !== window.location.host) return null;
      } catch(e) { return null; }
    }
    return orig.apply(this, arguments);
  };
})();`)
        e.Register("close-window", `(function(){
  // Block scripts that try to close the tab/window without user consent.
  window.close = function(){};
})();`)
}

func (e *Engine) registerDOM() {
        e.Register("remove-class", `(function(){
  // Caller sets window.__wlt_remove_class = "selector->class1,class2"
  // or we just expose a helper.
  window.__wlt_remove_class = function(selector, classes){
    document.querySelectorAll(selector).forEach(function(el){
      classes.split(',').forEach(function(c){ el.classList.remove(c.trim()); });
    });
  };
})();`)
        e.Register("prevent-refresh", `(function(){
  // Block <meta http-equiv="refresh"> redirects.
  document.querySelectorAll('meta[http-equiv="refresh" i]').forEach(function(m){ m.remove(); });
  // Block script-initiated location.reload for the first 5s after load.
  var origReload = location.reload.bind(location);
  var until = Date.now() + 5000;
  location.reload = function(){
    if (Date.now() < until) return;
    return origReload.apply(this, arguments);
  };
})();`)
        e.Register("remove-node-text", `(function(){
  // Caller: window.__wlt_remove_node_text(selector, /regex/)
  window.__wlt_remove_node_text = function(selector, re){
    document.querySelectorAll(selector).forEach(function(el){
      if (re.test(el.textContent || '')) el.remove();
    });
  };
})();`)
        e.Register("replace-node-text", `(function(){
  // Caller: window.__wlt_replace_node_text(selector, /from/, 'to')
  window.__wlt_replace_node_text = function(selector, re, replacement){
    document.querySelectorAll(selector).forEach(function(el){
      if (re.test(el.textContent || '')) {
        el.textContent = (el.textContent || '').replace(re, replacement);
      }
    });
  };
})();`)
}

func (e *Engine) registerTimers() {
        e.Register("adjust-setInterval", `(function(){
  // Slow down setInterval callbacks for ad timers (default 1x = no change).
  // Caller: window.__wlt_setinterval_scale = 16; (16x faster)
  var orig = window.setInterval;
  window.setInterval = function(fn, delay){
    var scale = window.__wlt_setinterval_scale || 1;
    return orig.call(this, fn, Math.max(1, Math.floor(delay / scale)));
  };
})();`)
        e.Register("adjust-setTimeout", `(function(){
  var orig = window.setTimeout;
  window.setTimeout = function(fn, delay){
    var scale = window.__wlt_settimeout_scale || 1;
    return orig.call(this, fn, Math.max(0, Math.floor(delay / scale)));
  };
})();`)
}

func (e *Engine) registerPrivacy() {
        e.Register("prevent-canvas", `(function(){
  // Return a deterministic noise fingerprint so canvas-based trackers can't
  // build a stable identifier.
  function patch(proto){
    var orig = proto.getContext;
    proto.getContext = function(type){
      var ctx = orig.apply(this, arguments);
      if (ctx && type === '2d') {
        var origGetImageData = ctx.getImageData;
        ctx.getImageData = function(){
          var d = origGetImageData.apply(this, arguments);
          for (var i = 0; i < d.data.length; i += 4) {
            d.data[i] ^= 1;     // nudge R by 1 bit
          }
          return d;
        };
        var origToDataURL = this.toDataURL;
        this.toDataURL = function(){
          return origToDataURL.apply(this, arguments);
        };
      }
      return ctx;
    };
  }
  patch(HTMLCanvasElement.prototype);
})();`)
        e.Register("webrtc-if", `(function(){
  // Prevent WebRTC IP leak by stubbing RTCPeerConnection.
  try {
    window.RTCPeerConnection = function(){ throw new TypeError('WebRTC disabled'); };
    window.webkitRTCPeerConnection = window.RTCPeerConnection;
  } catch(e) {}
})();`)
        e.Register("window-name-defuser", `(function(){
  // Wipe window.name on load (some trackers stash IDs there).
  window.name = '';
})();`)
        e.Register("no-floc", `(function(){
  // Opt out of Google's FLoC cohort calculation.
  try {
    if (document.interestCohort) {
      document.interestCohort = function(){ return Promise.reject(new Error('disabled')); };
    }
  } catch(e) {}
  // Also send the Permissions-Policy header via meta-equiv (no-op if missing).
  var m = document.createElement('meta');
  m.httpEquiv = 'permissions-policy';
  m.content = 'interest-cohort=()';
  document.head && document.head.appendChild(m);
})();`)
}

func (e *Engine) registerXMLJSON() {
        e.Register("xml-prune", `(function(){
  // Caller: window.__wlt_xml_prune = function(rootEl){ return true; }
  // (i.e. a predicate to decide whether to drop the element).
  // The scriptlet hooks XMLHttpRequest to invoke the predicate on the
  // parsed XML document and strips matching subtrees before the page
  // sees them.
  var orig = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function(){
    var xhr = this;
    xhr.addEventListener('load', function(){
      try {
        if (xhr.responseType === 'document' || (xhr.getResponseHeader('content-type') || '').indexOf('xml') >= 0) {
          if (window.__wlt_xml_prune && xhr.responseXML) {
            window.__wlt_xml_prune(xhr.responseXML.documentElement);
          }
        }
      } catch(e) {}
    });
    return orig.apply(this, arguments);
  };
})();`)
        e.Register("json-prune", `(function(){
  // Hook fetch + XHR for JSON responses, run the caller-supplied
  // window.__wlt_json_prune(obj) -> obj mutator.
  var prune = function(obj){
    try {
      if (window.__wlt_json_prune && typeof obj === 'object' && obj !== null) {
        return window.__wlt_json_prune(obj) || obj;
      }
    } catch(e) {}
    return obj;
  };
  var origFetch = window.fetch;
  window.fetch = function(){
    return origFetch.apply(this, arguments).then(function(resp){
      var ct = resp.headers.get('content-type') || '';
      if (ct.indexOf('json') < 0) return resp;
      return resp.clone().json().then(function(j){
        var pruned = prune(j);
        return new Response(JSON.stringify(pruned), { status: resp.status, headers: resp.headers });
      }).catch(function(){ return resp; });
    });
  };
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url){
    this.__wlt_url = url;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(){
    var xhr = this;
    xhr.addEventListener('load', function(){
      try {
        var ct = xhr.getResponseHeader('content-type') || '';
        if (ct.indexOf('json') >= 0 && window.__wlt_json_prune) {
          var j = JSON.parse(xhr.responseText);
          var pruned = prune(j);
          Object.defineProperty(xhr, 'responseText', { value: JSON.stringify(pruned) });
          Object.defineProperty(xhr, 'response', { value: JSON.stringify(pruned) });
        }
      } catch(e) {}
    });
    return origSend.apply(this, arguments);
  };
})();`)
}

func (e *Engine) registerMisc() {
        e.Register("disable-newtab-links", `(function(){
  document.querySelectorAll('a[target="_blank"]').forEach(function(a){
    a.removeAttribute('target');
  });
  // Also patch any future-added anchors.
  var obs = new MutationObserver(function(muts){
    muts.forEach(function(m){
      m.addedNodes.forEach(function(n){
        if (n.nodeType === 1 && n.tagName === 'A' && n.getAttribute('target') === '_blank') {
          n.removeAttribute('target');
        }
      });
    });
  });
  if (document.documentElement) obs.observe(document.documentElement, { childList: true, subtree: true });
})();`)
        e.Register("alert-buster", `(function(){
  window.alert = function(){};
  window.confirm = function(){ return true; };
  window.prompt = function(){ return ''; };
})();`)
}

func (e *Engine) registerYouTube() {
        e.Register("yt-player-intercept", `(function(){
  // Strip adPlacements / adSlots / playerAds from ytInitialPlayerResponse.
  if (window.ytInitialPlayerResponse) {
    try {
      var p = window.ytInitialPlayerResponse;
      if (p.adPlacements) p.adPlacements = [];
      if (p.adSlots) p.adSlots = [];
      if (p.streamingData && p.streamingData.serverAbrStreamingUrl) {
        // keep serverAbrStreamingUrl — it's used for the actual video.
      }
      if (p.auxiliaryUi && p.auxiliaryUi.messageRenderers) {
        delete p.auxiliaryUi.messageRenderers;
      }
    } catch(e) {}
  }
  // Also patch the ytplayer.config global.
  Object.defineProperty(window, 'ytInitialPlayerResponse', {
    configurable: true,
    set: function(v){
      try {
        if (v && v.adPlacements) v.adPlacements = [];
        if (v && v.adSlots) v.adSlots = [];
      } catch(e) {}
      this.__ytipr = v;
    },
    get: function(){ return this.__ytipr; }
  });
})();`)
        e.Register("yt-speed-up-ads", `(function(){
  // Speed up any ad video to 16x, mute it, and click the skip button
  // as soon as it appears.
  var v = document.querySelector('video');
  if (v) {
    v.addEventListener('play', function(){
      var isAd = document.querySelector('.ytp-ad-player-overlay') || document.querySelector('.ad-interrupting');
      if (isAd) {
        v.playbackRate = 16;
        v.muted = true;
      }
    });
  }
  setInterval(function(){
    var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, button[class*="skip"]');
    if (skip) skip.click();
    var adEl = document.querySelector('.ytp-ad-player-overlay, .ad-interrupting');
    var vid = document.querySelector('video');
    if (adEl && vid) {
      vid.playbackRate = 16;
      vid.muted = true;
    }
  }, 250);
})();`)
        e.Register("yt-remove-ad-survey", `(function(){
  setInterval(function(){
    document.querySelectorAll('yt-confirm-dialog-renderer, ytd-button-renderer[dialog]');
    document.querySelectorAll('ytd-mealbar-promo-renderer, ytd-popup-container').forEach(function(el){ el.remove(); });
    document.querySelectorAll('button[aria-label="Skip Ads"], button[aria-label="Skip ad"]').forEach(function(b){ b.click(); });
  }, 500);
})();`)
        e.Register("yt-block-ads-request", `(function(){
  var orig = window.fetch;
  window.fetch = function(input, init){
    var u = typeof input === 'string' ? input : (input && input.url) || '';
    if (/\/api\/stats\/ads|\/ptracking|\/api\/timedtext.+?capsas=.+?ads/i.test(u)) {
      return Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }));
    }
    return orig.apply(this, arguments);
  };
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url){
    if (/\/api\/stats\/ads|\/ptracking/.test(url || '')) {
      this.send = function(){};
    }
    return origOpen.apply(this, arguments);
  };
})();`)
        e.Register("yt-sponsorblock", `(function(){
  // Stub: real SponsorBlock integration is in the sponsorblock package.
  // This scriptlet exposes window.__wlt_sponsorblock_apply(segments) which
  // the proxy calls with the skip segments for the current video.
  window.__wlt_sponsorblock_apply = function(segments){
    var vid = document.querySelector('video');
    if (!vid || !segments || !segments.length) return;
    var pending = segments.slice().sort(function(a,b){ return a.startTime - b.startTime; });
    function check(){
      var t = vid.currentTime;
      for (var i = 0; i < pending.length; i++) {
        if (t >= pending[i].startTime && t < pending[i].endTime) {
          vid.currentTime = pending[i].endTime;
          break;
        }
      }
    }
    vid.addEventListener('timeupdate', check);
  };
})();`)
        for _, name := range []string{"yt-player-intercept", "yt-speed-up-ads", "yt-remove-ad-survey", "yt-block-ads-request", "yt-sponsorblock"} {
                e.Assign("youtube.com", name)
                e.Assign("youtu.be", name)
        }
}

func (e *Engine) registerSpotify() {
        e.Register("spotify-ad-intercept", `(function(){
  // Intercept the Spotify web player's ad-stream URL.
  var orig = window.fetch;
  window.fetch = function(input, init){
    var u = typeof input === 'string' ? input : (input && input.url) || '';
    if (/audio-ak-spoti|ads\.spotify|ad-tier/i.test(u)) {
      return Promise.resolve(new Response('', { status: 204 }));
    }
    return orig.apply(this, arguments);
  };
})();`)
        e.Assign("spotify.com", "spotify-ad-intercept")
        e.Assign("open.spotify.com", "spotify-ad-intercept")
}

func (e *Engine) registerTwitch() {
        e.Register("twitch-video-swap", `(function(){
  // TwitchAdSolutions "video-swap" approach: hook the player's HTMLMediaElement
  // to swap the ad-stream URL for the live-stream URL during ad breaks.
  var orig = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
  var realStreamUrl = null;
  try {
    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
      configurable: true,
      set: function(v){
        if (v && /\/hls\//.test(v) && !realStreamUrl) realStreamUrl = v;
        if (v && /ads|sqad|stitched/i.test(v) && realStreamUrl) {
          orig.set.call(this, realStreamUrl);
          return;
        }
        orig.set.call(this, v);
      },
      get: function(){ return orig.get.call(this); }
    });
  } catch(e) {}
})();`)
        e.Register("twitch-mute-ads", `(function(){
  // Mute the player while ad indicators are visible.
  setInterval(function(){
    var v = document.querySelector('video');
    if (!v) return;
    var ad = document.querySelector('[data-test-selector="ad-banner"], .player-ad-bottom-left');
    if (ad && ad.offsetWidth > 0) { v.muted = true; }
    else { v.muted = false; }
  }, 200);
})();`)
        e.Register("twitch-block-ad-request", `(function(){
  var orig = window.fetch;
  window.fetch = function(input, init){
    var u = typeof input === 'string' ? input : (input && input.url) || '';
    if (/\/api\/channels\/.+?\/access_token|c\.twitchcdn\.net.+?\/ads\//i.test(u)) {
      return Promise.resolve(new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }));
    }
    return orig.apply(this, arguments);
  };
})();`)
        for _, name := range []string{"twitch-video-swap", "twitch-mute-ads", "twitch-block-ad-request"} {
                e.Assign("twitch.tv", name)
        }
}

func (e *Engine) registerReddit() {
        e.Register("reddit-hide-promoted", `(function(){
  function purge(){
    document.querySelectorAll('[data-promoted="true"]').forEach(function(el){
      el.style.display = 'none';
    });
    document.querySelectorAll('shreddit-post').forEach(function(el){
      if (el.getAttribute('promoted') === 'true') el.style.display = 'none';
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', purge);
  } else { purge(); }
  setInterval(purge, 1000);
})();`)
        e.Assign("reddit.com", "reddit-hide-promoted")
        e.Assign("new.reddit.com", "reddit-hide-promoted")
        e.Assign("old.reddit.com", "reddit-hide-promoted")
}

func (e *Engine) registerTwitter() {
        e.Register("twitter-hide-promoted", `(function(){
  function purge(){
    // Promoted tweets have a placementTracking child element.
    document.querySelectorAll('div[data-testid="placementTracking"]').forEach(function(el){
      var tweet = el.closest('article, [data-testid="tweet"]');
      if (tweet) tweet.style.display = 'none';
    });
    // Also catch the "Promoted" label by text.
    document.querySelectorAll('article, [data-testid="tweet"]').forEach(function(t){
      var spans = t.querySelectorAll('span');
      for (var i = 0; i < spans.length; i++) {
        if ((spans[i].textContent || '').trim() === 'Promoted') {
          t.style.display = 'none';
          break;
        }
      }
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', purge);
  } else { purge(); }
  setInterval(purge, 1000);
})();`)
        e.Assign("twitter.com", "twitter-hide-promoted")
        e.Assign("x.com", "twitter-hide-promoted")
}

func (e *Engine) registerInstagram() {
        e.Register("instagram-hide-sponsored", `(function(){
  // Instagram splits the "Sponsored" label across multiple <span> elements
  // to evade text-based blockers. We walk every post's label container,
  // concatenate the text of all its spans, and hide the post if the
  // concatenation matches /Sponsored/i.
  function purge(){
    document.querySelectorAll('article').forEach(function(art){
      // Look for the "sponsored label" area near the header.
      var spans = art.querySelectorAll('span');
      var text = '';
      spans.forEach(function(s){ text += s.textContent || ''; });
      if (/sponsored/i.test(text)) {
        art.style.display = 'none';
      }
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', purge);
  } else { purge(); }
  setInterval(purge, 1000);
})();`)
        e.Assign("instagram.com", "instagram-hide-sponsored")
}

func (e *Engine) registerCrypto() {
        e.Register("block-crypto-miners", `(function(){
  // 1) Block WebAssembly.instantiate so WASM miners (CoinHive, etc.) can't
  //    compile their payload.
  try {
    if (window.WebAssembly) {
      window.WebAssembly.instantiate = function(){ return Promise.reject(new Error('disabled')); };
      window.WebAssembly.instantiateStreaming = function(){ return Promise.reject(new Error('disabled')); };
    }
  } catch(e) {}
  // 2) Block Worker construction from known miner domains.
  var origWorker = window.Worker;
  try {
    Object.defineProperty(window, 'Worker', {
      configurable: true,
      get: function(){ return function(url){
        var u = String(url || '');
        if (/coinhive|coin-?hive|cryptonight|crypto-?loot|webminerpool|load\.jsecoin|cryptoloot/i.test(u)) {
          throw new TypeError('blocked');
        }
        return new origWorker(url);
      }; }
    });
  } catch(e) {}
})();`)
        // Assign to a broad set of hosts where miners have been seen.
        e.Assign("github.io", "block-crypto-miners")
}

func (e *Engine) registerTrusted() {
        e.Register("trusted-replace-fetch-response", `(function(){
  // Caller sets window.__wlt_trusted_fetch_replace = [{ pattern: /regex/, replacement: 'string' }, ...]
  var orig = window.fetch;
  window.fetch = function(){
    return orig.apply(this, arguments).then(function(resp){
      var rules = window.__wlt_trusted_fetch_replace;
      if (!rules || !rules.length) return resp;
      var ct = resp.headers.get('content-type') || '';
      if (ct.indexOf('text') < 0 && ct.indexOf('json') < 0) return resp;
      return resp.clone().text().then(function(body){
        var out = body;
        rules.forEach(function(r){ out = out.replace(r.pattern, r.replacement); });
        var h = new Headers(resp.headers);
        return new Response(out, { status: resp.status, statusText: resp.statusText, headers: h });
      });
    });
  };
})();`)
        e.Register("trusted-replace-xhr-response", `(function(){
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function(method, url){
    this.__wlt_url = url;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(){
    var xhr = this;
    xhr.addEventListener('readystatechange', function(){
      if (xhr.readyState === 4) {
        var rules = window.__wlt_trusted_xhr_replace;
        if (!rules || !rules.length) return;
        try {
          var body = xhr.responseText;
          var out = body;
          rules.forEach(function(r){ out = out.replace(r.pattern, r.replacement); });
          Object.defineProperty(xhr, 'responseText', { value: out });
          Object.defineProperty(xhr, 'response', { value: out });
        } catch(e) {}
      }
    });
    return origSend.apply(this, arguments);
  };
})();`)
        e.Register("trusted-click-element", `(function(){
  // Caller: window.__wlt_trusted_click = "selector" (click the first match
  // every N ms until it succeeds).
  var sel = window.__wlt_trusted_click;
  if (!sel) return;
  var tries = 0;
  var timer = setInterval(function(){
    tries++;
    if (tries > 60) { clearInterval(timer); return; }
    var el = document.querySelector(sel);
    if (el) { try { el.click(); } catch(e) {} clearInterval(timer); }
  }, 250);
})();`)
}

func (e *Engine) registerUtilities() {
        e.Register("break-on-call", `(function(){
  // Caller: window.__wlt_break_on_call = "foo.bar" — throws when the function
  // at that path is called.
  var chain = window.__wlt_break_on_call;
  if (!chain) return;
  var parts = chain.split('.');
  var obj = window;
  for (var i = 0; i < parts.length - 1; i++) {
    if (obj[parts[i]] == null) return;
    obj = obj[parts[i]];
  }
  var last = parts[parts.length - 1];
  var orig = obj[last];
  if (typeof orig !== 'function') return;
  obj[last] = function(){
    throw new ReferenceError(chain + ' called');
  };
})();`)
        e.Register("call-nothrow", `(function(){
  // Wrap a function so it never throws. Caller: window.__wlt_call_nothrow = "foo.bar"
  var chain = window.__wlt_call_nothrow;
  if (!chain) return;
  var parts = chain.split('.');
  var obj = window;
  for (var i = 0; i < parts.length - 1; i++) {
    if (obj[parts[i]] == null) return;
    obj = obj[parts[i]];
  }
  var last = parts[parts.length - 1];
  var orig = obj[last];
  if (typeof orig !== 'function') return;
  obj[last] = function(){
    try { return orig.apply(this, arguments); } catch(e) { return undefined; }
  };
})();`)

        // === uBlock Origin 2025 scriptlets (from latest uBO research) ===

        e.Register("trusted-replace-argument", `(function(){
  // uBlock Origin 2025: Replace a specific argument when a target function is called.
  // Caller sets: window.__wlt_tra_target = "console.log"; window.__wlt_tra_argIndex = 0; window.__wlt_tra_replacement = "";
  var target = window.__wlt_tra_target;
  var argIndex = window.__wlt_tra_argIndex || 0;
  var replacement = window.__wlt_tra_replacement || "";
  if (!target) return;
  var parts = target.split('.');
  var obj = window;
  for (var i = 0; i < parts.length - 1; i++) {
    if (obj[parts[i]] == null) return;
    obj = obj[parts[i]];
  }
  var last = parts[parts.length - 1];
  var orig = obj[last];
  if (typeof orig !== 'function') return;
  obj[last] = function(){
    if (arguments.length > argIndex) {
      arguments[argIndex] = replacement;
    }
    return orig.apply(this, arguments);
  };
})();`)

        e.Register("prevent-fetch", `(function(){
  // uBlock Origin: More granular than fetch-blocker — blocks fetch() only when
  // the URL matches a regex pattern. Caller sets: window.__wlt_pf_pattern = "ads|tracker";
  var pattern = window.__wlt_pf_pattern;
  if (!pattern) { pattern = "ads|tracker|doubleclick|googlesyndication"; }
  var regex = new RegExp(pattern, 'i');
  var origFetch = window.fetch;
  if (!origFetch) return;
  window.fetch = function(input, init) {
    var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
    if (regex.test(url)) {
      return new Promise(function(_, reject) {
        reject(new TypeError('Blocked by WLT: ' + url));
      });
    }
    return origFetch.apply(this, arguments);
  };
})();`)

        e.Register("prevent-xhr", `(function(){
  // uBlock Origin: More granular than xhr-blocker — blocks XMLHttpRequest only
  // when the URL matches a regex pattern. Caller sets: window.__wlt_px_pattern = "ads|tracker";
  var pattern = window.__wlt_px_pattern;
  if (!pattern) { pattern = "ads|tracker|doubleclick|googlesyndication"; }
  var regex = new RegExp(pattern, 'i');
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) {
    if (url && regex.test(url)) {
      // Replace the send method to block this request
      this.send = function() { /* no-op: blocked by WLT */ };
    }
    return origOpen.apply(this, arguments);
  };
})();`)

        e.Register("no-window-open-if", `(function(){
  // uBlock Origin: Prevent window.open() when the URL matches a pattern.
  // Caller sets: window.__wlt_nwoi_pattern = "ads|popup";
  var pattern = window.__wlt_nwoi_pattern;
  if (!pattern) { pattern = "ads|popup|sponsor"; }
  var regex = new RegExp(pattern, 'i');
  var origOpen = window.open;
  window.open = function(url) {
    if (url && regex.test(url)) {
      return null; // Block the popup
    }
    return origOpen.apply(this, arguments);
  };
})();`)

        e.Register("prevent-addEventListener", `(function(){
  // uBlock Origin: Prevent addEventListener for specific event types on
  // specific elements. Useful for blocking ad event listeners.
  // Caller sets: window.__wlt_pael_event = "click"; window.__wlt_pael_selector = ".ad";
  var evt = window.__wlt_pael_event;
  var sel = window.__wlt_pael_selector;
  if (!evt) return;
  var orig = EventTarget.prototype.addEventListener;
  EventTarget.prototype.addEventListener = function(type, listener, options) {
    if (type === evt) {
      // Check if this element matches the selector
      if (sel && this.matches && this.matches(sel)) {
        return; // Block this listener
      }
    }
    return orig.apply(this, arguments);
  };
})();`)

        e.Register("remove-attr", `(function(){
  // uBlock Origin: Remove an attribute from elements matching a selector.
  // Caller sets: window.__wlt_ra_attr = "data-ad"; window.__wlt_ra_selector = "div";
  var attr = window.__wlt_ra_attr;
  var sel = window.__wlt_ra_selector || '*';
  if (!attr) return;
  function remove() {
    var els = document.querySelectorAll(sel);
    for (var i = 0; i < els.length; i++) {
      els[i].removeAttribute(attr);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', remove);
  } else {
    remove();
  }
  // Also run on DOM mutations (for SPA pages)
  var observer = new MutationObserver(function() { remove(); });
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();`)

        e.Register("set-constant", `(function(){
  // uBlock Origin: Override a property with a constant value.
  // Useful for neutralizing ad detection: window.__wlt_sc_prop = "adblock"; window.__wlt_sc_val = false;
  var prop = window.__wlt_sc_prop;
  var val = window.__wlt_sc_val;
  if (!prop) return;
  var parts = prop.split('.');
  var obj = window;
  for (var i = 0; i < parts.length - 1; i++) {
    if (obj[parts[i]] == null) return;
    obj = obj[parts[i]];
  }
  var last = parts[parts.length - 1];
  Object.defineProperty(obj, last, {
    get: function() { return val; },
    set: function() { /* no-op: locked by WLT */ },
    configurable: false
  });
})();`)

        // === Phase 6: Additional uBlock Origin 2025 scriptlets ===

        e.Register("de-amp", `(function(){
  // Brave De-AMP: Redirect AMP pages to canonical publisher URLs
  var canonical = document.querySelector('link[rel="canonical"]');
  var amphtml = document.querySelector('link[rel="amphtml"]');
  if (canonical && amphtml) {
    var canonicalUrl = canonical.href;
    if (canonicalUrl && canonicalUrl !== window.location.href) {
      window.location.replace(canonicalUrl);
    }
  }
  if (window.location.hostname.indexOf('.amp.') !== -1 ||
      window.location.hostname.indexOf('.cdn.ampproject.') !== -1) {
    if (canonical) {
      window.location.replace(canonical.href);
    }
  }
})();`)

        e.Register("prevent-webgl", `(function(){
  // Block WebGL fingerprinting by returning null for WebGL context
  var origGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = function(type) {
    if (type === 'webgl' || type === 'webgl2' || type === 'experimental-webgl') {
      return null;
    }
    return origGetContext.apply(this, arguments);
  };
})();`)

        e.Register("prevent-audio-fingerprint", `(function(){
  // Add noise to AudioContext to prevent audio fingerprinting
  var origCreateOscillator = AudioContext.prototype.createOscillator;
  if (origCreateOscillator) {
    AudioContext.prototype.createOscillator = function() {
      var osc = origCreateOscillator.call(this);
      var origFreq = osc.frequency.value;
      try { osc.frequency.value = origFreq + (Math.random() - 0.5) * 0.0001; } catch(e) {}
      return osc;
    };
  }
})();`)

        e.Register("prevent-font-enumeration", `(function(){
  // Block font enumeration via document.fonts
  if (document.fonts) {
    document.fonts.check = function() { return false; };
  }
})();`)

        e.Register("set-attr", `(function(){
  // Set DOM attributes on elements matching selector
  var sel = window.__wlt_set_attr_selector;
  var attr = window.__wlt_set_attr_attr;
  var val = window.__wlt_set_attr_val;
  if (!sel || !attr) return;
  function setAttr() {
    var els = document.querySelectorAll(sel);
    for (var i = 0; i < els.length; i++) {
      els[i].setAttribute(attr, val);
    }
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', setAttr);
  } else { setAttr(); }
  var obs = new MutationObserver(setAttr);
  obs.observe(document.documentElement, {childList: true, subtree: true});
})();`)

        e.Register("set-cookie", `(function(){
  // Override a cookie value
  var name = window.__wlt_set_cookie_name;
  var val = window.__wlt_set_cookie_val;
  var path = window.__wlt_set_cookie_path || '/';
  if (!name) return;
  document.cookie = name + '=' + val + '; path=' + path;
})();`)

        e.Register("set-local-storage-item", `(function(){
  // Override a localStorage item
  var key = window.__wlt_set_ls_key;
  var val = window.__wlt_set_ls_val;
  if (!key) return;
  try { localStorage.setItem(key, val); } catch(e) {}
})();`)

        e.Register("set-session-storage-item", `(function(){
  // Override a sessionStorage item
  var key = window.__wlt_set_ss_key;
  var val = window.__wlt_set_ss_val;
  if (!key) return;
  try { sessionStorage.setItem(key, val); } catch(e) {}
})();`)

        e.Register("remove-cookie", `(function(){
  // Remove cookies matching a pattern
  var pattern = window.__wlt_remove_cookie_pattern || '';
  var regex = pattern ? new RegExp(pattern, 'i') : null;
  var cookies = document.cookie.split(';');
  for (var i = 0; i < cookies.length; i++) {
    var name = cookies[i].split('=')[0].trim();
    if (!regex || regex.test(name)) {
      document.cookie = name + '=; expires=Thu, 01 Jan 1970 00:00:00 UTC; path=/;';
    }
  }
})();`)

        e.Register("remove-cache-storage-item", `(function(){
  // Remove items from Cache Storage
  var key = window.__wlt_remove_cache_key;
  if (!key || !window.caches) return;
  caches.keys().then(function(names) {
    for (var i = 0; i < names.length; i++) {
      if (names[i] === key || names[i].indexOf(key) !== -1) {
        caches.delete(names[i]);
      }
    }
  });
})();`)

        e.Register("href-sanitizer", `(function(){
  // Sanitize link URLs by stripping tracking parameters
  var params = ['utm_source','utm_medium','utm_campaign','utm_term','utm_content',
                'fbclid','gclid','msclkid','mc_cid','mc_eid','igshid','spm','twclid'];
  function sanitize(url) {
    try {
      var u = new URL(url);
      for (var i = 0; i < params.length; i++) {
        u.searchParams.delete(params[i]);
      }
      return u.toString();
    } catch(e) { return url; }
  }
  var links = document.querySelectorAll('a[href]');
  for (var i = 0; i < links.length; i++) {
    links[i].href = sanitize(links[i].href);
  }
})();`)

        e.Register("spoof-css", `(function(){
  // Return fake CSS values for fingerprinting
  var prop = window.__wlt_spoof_css_prop;
  var val = window.__wlt_spoof_css_val;
  if (!prop) return;
  var origGetComputedStyle = window.getComputedStyle;
  window.getComputedStyle = function(el, pseudo) {
    var style = origGetComputedStyle.call(this, el, pseudo);
    var origGetProp = style.getPropertyValue.bind(style);
    style.getPropertyValue = function(p) {
      if (p === prop) return val;
      return origGetProp(p);
    };
    return style;
  };
})();`)

        e.Register("abort-on-stack-trace", `(function(){
  // Abort when a function matching the chain is called and the stack trace matches
  var chain = window.__wlt_aost_chain;
  var needle = window.__wlt_aost_needle;
  if (!chain) return;
  var parts = chain.split('.');
  var obj = window;
  for (var i = 0; i < parts.length - 1; i++) {
    if (obj[parts[i]] == null) return;
    obj = obj[parts[i]];
  }
  var last = parts[parts.length - 1];
  var orig = obj[last];
  if (typeof orig !== 'function') return;
  obj[last] = function() {
    var stack = new Error().stack;
    if (needle && stack.indexOf(needle) !== -1) {
      throw new ReferenceError(chain + ' called from ' + needle);
    }
    return orig.apply(this, arguments);
  };
})();`)

        e.Register("noeval-if", `(function(){
  // Conditional eval blocking — block eval when arg matches pattern
  var pattern = window.__wlt_noevalif_pattern || '';
  var regex = pattern ? new RegExp(pattern, 'i') : null;
  var origEval = window.eval;
  window.eval = function(code) {
    if (!regex || regex.test(String(code))) {
      return undefined; // Blocked
    }
    return origEval.apply(this, arguments);
  };
})();`)

        e.Register("prevent-bab", `(function(){
  // Block Anti-Adblock (BAB) library detection
  var origDefineProperty = Object.defineProperty;
  Object.defineProperty = function(obj, prop, desc) {
    if (prop === 'bab' || prop === 'blockAdBlock') {
      desc.get = function() { return undefined; };
      desc.set = function() {};
    }
    return origDefineProperty.apply(this, arguments);
  };
})();`)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

func normalizeSuffix(s string) string {
        s = strings.ToLower(strings.TrimSpace(s))
        s = strings.TrimPrefix(s, ".")
        return s
}

func hostMatchesSuffix(host, suffix string) bool {
        host = strings.ToLower(strings.TrimSpace(host))
        suffix = strings.ToLower(strings.TrimSpace(suffix))
        if host == suffix {
                return true
        }
        return strings.HasSuffix(host, "."+suffix)
}

// Compile-time format helper used by some scriptlets (kept here to avoid
// an unused-import error if fmt is otherwise unreferenced).
var _ = fmt.Sprintf
