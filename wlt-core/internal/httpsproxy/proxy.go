// Package httpsproxy implements the WLT Phase 3 HTTPS MITM proxy.
//
// The proxy accepts HTTP CONNECT requests from the system VPN service,
// peeks the TLS ClientHello to extract the SNI, signs a per-domain leaf
// certificate with the WLT CA, then transparently bridges the encrypted
// connection while inspecting every request/response for:
//
//   - HLS m3u8 playlists (ad segments pruned via m3uprune)
//   - HTML responses (scriptlets injected for matching domains)
//   - CSS-injectable responses (cosmetic rules for matching domains)
//   - Tracking parameters stripped from URL paths (utm_*, fbclid, gclid,
//     msclkid, mc_eid)
//
// The proxy is intentionally simple: it's a per-connection goroutine that
// runs a half-duplex CONNECT-then-TLS bridge. It does NOT cache or pool
// upstream connections — every request opens a fresh upstream socket.
// This keeps the implementation small and the resource usage predictable
// on a phone.
package httpsproxy

import (
        "bufio"
        "bytes"
        "crypto/tls"
        "fmt"
        "io"
        "net"
        "net/http"
        "strings"
        "sync"
        "sync/atomic"
        "time"

        "github.com/wlt/adblocker/internal/cosmetic"
        "github.com/wlt/adblocker/internal/m3uprune"
        "github.com/wlt/adblocker/internal/mitm"
        "github.com/wlt/adblocker/internal/scriptlets"
        "github.com/wlt/adblocker/internal/trie"
)

// ProxyStats holds counters for the HTTPS proxy. All fields are accessed
// atomically — the struct itself is never copied. Use Snapshot() to get a
// consistent point-in-time copy.
type ProxyStats struct {
        connections      atomic.Uint64
        httpsConnections atomic.Uint64
        requests         atomic.Uint64
        m3uPruned        atomic.Uint64
        scriptletInj     atomic.Uint64
        cosmeticInj      atomic.Uint64
        paramsStripped   atomic.Uint64
        upstreamErrors   atomic.Uint64
}

// Snapshot returns a point-in-time copy of the proxy stats. The returned
// struct is safe to log/serialise.
type StatsSnapshot struct {
        Connections      uint64
        HTTPSConnections uint64
        Requests         uint64
        M3UPruned        uint64
        ScriptletInj     uint64
        CosmeticInj      uint64
        ParamsStripped   uint64
        UpstreamErrors   uint64
}

// Snapshot returns a point-in-time copy of the proxy stats.
func (s *ProxyStats) Snapshot() StatsSnapshot {
        return StatsSnapshot{
                Connections:      s.connections.Load(),
                HTTPSConnections: s.httpsConnections.Load(),
                Requests:         s.requests.Load(),
                M3UPruned:        s.m3uPruned.Load(),
                ScriptletInj:     s.scriptletInj.Load(),
                CosmeticInj:      s.cosmeticInj.Load(),
                ParamsStripped:   s.paramsStripped.Load(),
                UpstreamErrors:   s.upstreamErrors.Load(),
        }
}

// Proxy is the HTTPS MITM proxy.
type Proxy struct {
        ca       *mitm.CA
        cosmetic *cosmetic.Engine
        scripts  *scriptlets.Engine

        mu       sync.Mutex
        listener net.Listener
        started  bool

        stats ProxyStats

        // SECURITY (C2 fix): MITM allowlist — only domains in this trie are
        // intercepted. All other domains are relayed as raw TCP (no TLS
        // decryption). This prevents the proxy from decrypting banking,
        // healthcare, and government traffic by default. The user must
        // explicitly opt-in specific domains for MITM.
        mitmAllowList *trie.Trie
        // mitmAllowAll, if true, disables the allowlist check and MITMs all
        // domains (legacy/debug behavior — NOT recommended for production).
        mitmAllowAll bool

        // SECURITY (H2 fix): Bounded goroutine pool to prevent OOM under
        // connection flooding. The semaphore channel caps concurrent
        // in-flight connections. Default is 64.
        sem chan struct{}
}

// New returns a Proxy that uses the given CA to sign per-domain leaf certs.
// The cosmetic and scriptlet engines may be nil — in that case the proxy
// won't inject any CSS/JS into HTML responses.
func New(ca *mitm.CA) *Proxy {
        p := &Proxy{
                ca:            ca,
                cosmetic:      cosmetic.New(),
                scripts:       scriptlets.New(),
                mitmAllowList: trie.New(),
                sem:           make(chan struct{}, 64), // max 64 concurrent connections
        }
        p.cosmetic.LoadDefaults()
        p.scripts.LoadDefaults()
        return p
}

// AddMitmDomain adds a domain to the MITM allowlist. Only domains in this
// list will have their TLS traffic intercepted; all others are relayed raw.
// SECURITY (C2 fix): This is the ONLY way to enable MITM for a domain.
func (p *Proxy) AddMitmDomain(domain string) {
        p.mitmAllowList.Insert(domain)
}

// SetMitmAllowAll enables MITM for ALL domains (debug/legacy mode).
// NOT recommended for production — use AddMitmDomain instead.
func (p *Proxy) SetMitmAllowAll(allow bool) {
        p.mitmAllowAll = allow
}

// MitmAllowListSize returns the number of domains in the MITM allowlist.
func (p *Proxy) MitmAllowListSize() int {
        return p.mitmAllowList.Size()
}

// shouldMitm returns true if the proxy should intercept TLS for [sni].
// If mitmAllowAll is true, always returns true (debug mode).
// Otherwise, checks the allowlist trie.
func (p *Proxy) shouldMitm(sni string) bool {
        if p.mitmAllowAll {
                return true
        }
        return p.mitmAllowList.Contains(sni)
}

// CosmeticEngine returns the proxy's cosmetic engine so the caller can
// load additional rules.
func (p *Proxy) CosmeticEngine() *cosmetic.Engine { return p.cosmetic }

// ScriptletEngine returns the proxy's scriptlet engine so the caller can
// register additional scriptlets.
func (p *Proxy) ScriptletEngine() *scriptlets.Engine { return p.scripts }

// Stats returns a snapshot of the proxy stats.
func (p *Proxy) Stats() StatsSnapshot { return p.stats.Snapshot() }

// Start begins listening on addr. It returns immediately; the proxy runs
// in a background goroutine.
func (p *Proxy) Start(addr string) error {
        p.mu.Lock()
        defer p.mu.Unlock()
        if p.started {
                return fmt.Errorf("httpsproxy: already started")
        }
        ln, err := net.Listen("tcp", addr)
        if err != nil {
                return fmt.Errorf("httpsproxy: listen: %w", err)
        }
        p.listener = ln
        p.started = true
        go p.acceptLoop()
        return nil
}

// Stop terminates the listener. In-flight connections are allowed to
// finish naturally; new connections are refused.
func (p *Proxy) Stop() error {
        p.mu.Lock()
        defer p.mu.Unlock()
        if !p.started {
                return nil
        }
        err := p.listener.Close()
        p.listener = nil
        p.started = false
        return err
}

// IsRunning reports whether the proxy is currently accepting connections.
func (p *Proxy) IsRunning() bool {
        p.mu.Lock()
        defer p.mu.Unlock()
        return p.started
}

// acceptLoop accepts connections in a loop until the listener is closed.
// SECURITY (H2 fix): Uses a bounded semaphore to cap concurrent goroutines
// at 64. If the pool is full, new connections block until a slot frees,
// preventing OOM under connection flooding.
func (p *Proxy) acceptLoop() {
        for {
                conn, err := p.listener.Accept()
                if err != nil {
                        return
                }
                p.stats.connections.Add(1)
                // Acquire a semaphore slot — blocks if 64 connections are
                // already in flight. This bounds memory usage.
                p.sem <- struct{}{}
                go func() {
                        defer func() { <-p.sem }() // release slot
                        p.handleConn(conn)
                }()
        }
}

// handleConn services one client connection: read the CONNECT request,
// peek the SNI, install TLS, and bridge HTTP requests to the upstream.
//
// SECURITY (C2 fix): If the SNI is NOT in the MITM allowlist, the connection
// is relayed as raw TCP (no TLS decryption). This prevents the proxy from
// intercepting banking, healthcare, and government traffic by default.
func (p *Proxy) handleConn(conn net.Conn) {
        defer conn.Close()
        conn.SetDeadline(time.Now().Add(2 * time.Minute))

        br := bufio.NewReader(conn)
        req, err := http.ReadRequest(br)
        if err != nil {
                return
        }
        if req.Method != http.MethodConnect {
                // Plain HTTP request — we don't support this in the MITM proxy
                // (the VPN service handles plain HTTP separately).
                return
        }
        host := req.URL.Host
        if host == "" {
                host = req.Host
        }
        if host == "" {
                fmt.Fprintf(conn, "HTTP/1.1 400 Bad Request\r\n\r\n")
                return
        }

        // Acknowledge the CONNECT.
        if _, err := fmt.Fprintf(conn, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
                return
        }

        // Peek the first TLS record to extract the SNI.
        sni, peeked, err := peekSNI(br)
        if err != nil || sni == "" {
                // No SNI — fall back to the CONNECT host.
                sni = stripPort(host)
        }

        // SECURITY (C2 fix): Only MITM domains in the allowlist. All other
        // domains are relayed as raw TCP — we never decrypt their TLS traffic.
        if !p.shouldMitm(sni) {
                // Relay raw bytes: write the peeked ClientHello back, then
                // pipe bidirectionally until either side closes.
                p.relayRaw(conn, br, peeked, host)
                return
        }

        // Sign a leaf cert for the SNI (the domain IS in the allowlist).
        leaf, err := p.ca.Sign(sni)
        if err != nil {
                return
        }

        // Wrap the client connection in TLS (we are the server). The peeked
        // bytes are the ClientHello that the bufio.Reader has already
        // consumed; we need to replay them into the TLS handshake.
        clientTLS := tls.Server(newPrependConn(conn, peeked), &tls.Config{
                Certificates: []tls.Certificate{*leaf},
                MinVersion:   tls.VersionTLS12,
        })
        if err := clientTLS.Handshake(); err != nil {
                return
        }
        defer clientTLS.Close()
        p.stats.httpsConnections.Add(1)

        // Bridge HTTP requests over the TLS connection.
        p.bridge(clientTLS, sni)
}

// bridge reads HTTP/1.1 requests from the client TLS connection and
// services them, optionally modifying request URLs and response bodies.
func (p *Proxy) bridge(clientConn net.Conn, host string) {
        br := bufio.NewReader(clientConn)
        for {
                req, err := http.ReadRequest(br)
                if err != nil {
                        return
                }
                p.stats.requests.Add(1)
                p.serviceRequest(clientConn, req, host)
        }
}

// relayRaw bridges a TCP connection WITHOUT decrypting TLS. Used when the
// SNI is NOT in the MITM allowlist (C2 fix). The peeked ClientHello bytes
// are written back to the upstream first, then data is piped bidirectionally.
//
// This is the privacy-safe path: banking, healthcare, and government sites
// pass through untouched. Only allowlisted domains get TLS interception.
func (p *Proxy) relayRaw(clientConn net.Conn, br *bufio.Reader, peeked []byte, host string) {
        // Dial the upstream server.
        upstreamAddr := host
        if !strings.Contains(upstreamAddr, ":") {
                upstreamAddr += ":443"
        }
        upstream, err := net.DialTimeout("tcp", upstreamAddr, 10*time.Second)
        if err != nil {
                p.stats.upstreamErrors.Add(1)
                return
        }
        defer upstream.Close()

        // Write the peeked ClientHello bytes to the upstream so the TLS
        // handshake can proceed end-to-end without us seeing the contents.
        if len(peeked) > 0 {
                if _, err := upstream.Write(peeked); err != nil {
                        return
                }
        }

        // Also flush any remaining buffered bytes from the bufio.Reader.
        if br.Buffered() > 0 {
                buf := make([]byte, br.Buffered())
                _, _ = br.Read(buf)
                if _, err := upstream.Write(buf); err != nil {
                        return
                }
        }

        // Bidirectional pipe: client ↔ upstream. No inspection, no modification.
        done := make(chan struct{}, 2)
        // client → upstream
        go func() {
                io.Copy(upstream, clientConn)
                done <- struct{}{}
        }()
        // upstream → client
        go func() {
                io.Copy(clientConn, upstream)
                done <- struct{}{}
        }()
        // Wait for either direction to finish.
        <-done
}

// serviceRequest forwards a single request to the upstream server, with
// optional URL-parameter stripping and response-body rewriting.
func (p *Proxy) serviceRequest(clientConn net.Conn, req *http.Request, host string) {
        // Strip tracking parameters from the request URL.
        if stripped := stripTrackingParams(req.URL.Path); stripped != req.URL.Path {
                p.stats.paramsStripped.Add(1)
                req.URL.Path = stripped
        }
        if stripped := stripTrackingParams(req.URL.RawQuery); stripped != req.URL.RawQuery {
                p.stats.paramsStripped.Add(1)
                req.URL.RawQuery = stripped
        }

        // Build the upstream URL.
        upstreamURL := "https://" + host + req.RequestURI
        upstreamReq, err := http.NewRequest(req.Method, upstreamURL, req.Body)
        if err != nil {
                p.stats.upstreamErrors.Add(1)
                return
        }
        upstreamReq.Header = req.Header.Clone()
        upstreamReq.Header.Set("Host", host)

        // Forward to the upstream.
        client := &http.Client{
                Timeout: 30 * time.Second,
                Transport: &http.Transport{
                        TLSClientConfig: nil, // use default verification
                },
        }
        resp, err := client.Do(upstreamReq)
        if err != nil {
                p.stats.upstreamErrors.Add(1)
                return
        }
        defer resp.Body.Close()

        // Read the body. We may need to rewrite it.
        // SECURITY (H2 fix): Cap body at 10 MB to prevent OOM from malicious
        // upstream servers sending huge responses. m3u playlists and HTML
        // pages are always well under this limit.
        body, err := io.ReadAll(io.LimitReader(resp.Body, 10*1024*1024))
        if err != nil {
                p.stats.upstreamErrors.Add(1)
                return
        }

        ct := resp.Header.Get("Content-Type")
        ctype := strings.ToLower(ct)

        // m3u playlist pruning.
        if isM3U(ctype) {
                pruned := m3uprune.Prune(body)
                if !bytes.Equal(pruned, body) {
                        p.stats.m3uPruned.Add(1)
                        body = pruned
                        resp.Header.Set("Content-Length", fmt.Sprintf("%d", len(body)))
                }
        }

        // HTML scriptlet + cosmetic injection.
        if isHTML(ctype) {
                var injected bool
                if p.scripts != nil {
                        newBody := p.scripts.Inject(body, host)
                        if !bytes.Equal(newBody, body) {
                                p.stats.scriptletInj.Add(1)
                                body = newBody
                                injected = true
                        }
                }
                if p.cosmetic != nil {
                        html := p.cosmetic.GenerateInjectionHTML(host)
                        if html != "" {
                                body = injectStyleScript(body, []byte(html))
                                p.stats.cosmeticInj.Add(1)
                                injected = true
                        }
                }
                if injected {
                        resp.Header.Set("Content-Length", fmt.Sprintf("%d", len(body)))
                }
        }

        // Write the response back to the client. We use a manual write to
        // avoid http.WriteResponse's chunked encoding behaviour.
        writeResponse(clientConn, resp, body)
}

// writeResponse writes an HTTP/1.1 response (status line, headers, body)
// to w.
func writeResponse(w net.Conn, resp *http.Response, body []byte) {
        // Status line.
        fmt.Fprintf(w, "HTTP/%d.%d %s\r\n", resp.ProtoMajor, resp.ProtoMinor, resp.Status)
        // Headers.
        resp.Header.Set("Content-Length", fmt.Sprintf("%d", len(body)))
        resp.Header.Del("Transfer-Encoding")
        for k, vs := range resp.Header {
                for _, v := range vs {
                        fmt.Fprintf(w, "%s: %s\r\n", k, v)
                }
        }
        fmt.Fprint(w, "\r\n")
        w.Write(body)
}

// injectStyleScript inserts the given <style>/<script> bytes into the HTML
// <head>. Falls back to prepending if there's no <head>.
func injectStyleScript(html []byte, inject []byte) []byte {
        lower := bytes.ToLower(html)
        idx := bytes.Index(lower, []byte("<head"))
        if idx < 0 {
                return append(inject, html...)
        }
        closeIdx := bytes.IndexByte(html[idx:], '>')
        if closeIdx < 0 {
                return append(inject, html...)
        }
        insertAt := idx + closeIdx + 1
        out := make([]byte, 0, len(html)+len(inject))
        out = append(out, html[:insertAt]...)
        out = append(out, inject...)
        out = append(out, html[insertAt:]...)
        return out
}

// stripTrackingParams removes utm_*, fbclid, gclid, msclkid, mc_eid query
// parameters from a URL query string or path-encoded query. The input
// may be either a query string (without leading "?") or a path with
// query; both forms are handled.
func stripTrackingParams(s string) string {
        if !strings.Contains(s, "=") {
                return s
        }
        // Split path / query if both present.
        pathPart, queryPart := s, ""
        if q := strings.Index(s, "?"); q >= 0 {
                pathPart = s[:q]
                queryPart = s[q+1:]
        } else if strings.HasPrefix(s, "/") == false && strings.Contains(s, "&") {
                // Looks like a bare query string.
                queryPart = s
                pathPart = ""
        } else {
                // Treat as a bare query string (no path, no leading "?").
                queryPart = s
                pathPart = ""
        }
        if queryPart == "" {
                return s
        }
        parts := strings.Split(queryPart, "&")
        kept := make([]string, 0, len(parts))
        for _, kv := range parts {
                eq := strings.Index(kv, "=")
                var key string
                if eq >= 0 {
                        key = kv[:eq]
                } else {
                        key = kv
                }
                keyLower := strings.ToLower(key)
                if isTrackingParam(keyLower) {
                        continue
                }
                kept = append(kept, kv)
        }
        out := strings.Join(kept, "&")
        if pathPart != "" {
                if out == "" {
                        return pathPart
                }
                return pathPart + "?" + out
        }
        return out
}

// isTrackingParam returns true if the given lowercased query parameter name
// is a known tracking parameter that should be stripped.
// Phase 6: Expanded from 16 to 45+ tracking parameters (uBO removeparam research).
func isTrackingParam(name string) bool {
        // UTM tracking (all utm_* params)
        if strings.HasPrefix(name, "utm_") {
                return true
        }
        // HubSpot tracking
        if strings.HasPrefix(name, "_hs") {
                return true
        }
        // Google Analytics
        if strings.HasPrefix(name, "_ga") || strings.HasPrefix(name, "_gid") || strings.HasPrefix(name, "_gl") {
                return true
        }
        switch name {
        // Facebook
        case "fbclid", "fb_ref", "fb_source", "fb_action_ids", "fb_action_types":
                return true
        // Google Ads
        case "gclid", "gclsrc", "dclid", "wbraid", "gbraid":
                return true
        // Microsoft Ads
        case "msclkid", "mktid", "mkwid", "pcrid":
                return true
        // Yahoo Ads
        case "yclid", "_yt":
                return true
        // Mailchimp
        case "mc_eid", "mc_cid":
                return true
        // Instagram
        case "igshid":
                return true
        // Alibaba
        case "spm":
                return true
        // Twitter/X
        case "twclid", "tw_source", "tw_ad":
                return true
        // HubSpot
        case "_hsenc", "_hsmi", "hsCtaTracking":
                return true
        // Other tracking
        case "icid", "vero_id", "wickedid", "ob_click_id",
                "oly_enc_id", "oly_anon_id", "ref", "ref_src",
                "__s", "piwik_id", "pk_campaign", "pk_kwd",
                "piwik_cdn", "pk_source", "pk_medium":
                return true
        }
        return false
}

// isM3U returns true if the content-type indicates an HLS playlist.
func isM3U(ctype string) bool {
        return strings.Contains(ctype, "mpegurl") ||
                strings.Contains(ctype, "vnd.apple.mpegurl") ||
                strings.Contains(ctype, "x-mpegurl")
}

// isHTML returns true if the content-type indicates an HTML response.
func isHTML(ctype string) bool {
        return strings.Contains(ctype, "text/html") ||
                strings.Contains(ctype, "application/xhtml+xml")
}

// stripPort removes any trailing ":port" suffix from the host.
func stripPort(h string) string {
        if i := strings.LastIndex(h, ":"); i > 0 {
                return h[:i]
        }
        return h
}

// ---------------------------------------------------------------------------
// Connection helpers
// ---------------------------------------------------------------------------

// prependConn wraps a net.Conn and serves the given prefix bytes first,
// then forwards reads/writes to the underlying conn. Used to replay
// peeked ClientHello bytes back into the TLS handshake.
type prependConn struct {
        net.Conn
        prefix *bytes.Reader
}

func newPrependConn(c net.Conn, prefix []byte) *prependConn {
        return &prependConn{
                Conn:   c,
                prefix: bytes.NewReader(prefix),
        }
}

func (c *prependConn) Read(b []byte) (int, error) {
        if c.prefix.Len() > 0 {
                return c.prefix.Read(b)
        }
        return c.Conn.Read(b)
}

// ---------------------------------------------------------------------------
// SNI peeking helpers
// ---------------------------------------------------------------------------

// peekSNI reads enough bytes from br to parse the TLS ClientHello and
// returns the SNI hostname. The peeked bytes are returned alongside so the
// caller can replay them into the TLS handshake.
func peekSNI(br *bufio.Reader) (string, []byte, error) {
        // TLS record header is 5 bytes: type (1), version (2), length (2).
        hdr, err := br.Peek(5)
        if err != nil {
                return "", nil, err
        }
        if hdr[0] != 0x16 {
                return "", nil, fmt.Errorf("not a TLS handshake")
        }
        recLen := int(hdr[3])<<8 | int(hdr[4])
        if recLen <= 0 || recLen > 16384+2048 {
                return "", nil, fmt.Errorf("bad TLS record length")
        }
        // Peek the full record.
        peekN := 5 + recLen
        buf, err := br.Peek(peekN)
        if err != nil {
                return "", nil, err
        }
        sni := parseSNI(buf)
        return sni, buf, nil
}

// parseSNI extracts the SNI hostname from a TLS ClientHello record. Returns
// "" if no SNI extension is present.
func parseSNI(rec []byte) string {
        if len(rec) < 5+4 {
                return ""
        }
        body := rec[5:]
        // Handshake header: type(1) + length(3) + version(2) + random(32) +
        // session_id_len(1) + session_id + cipher_suites_len(2) + cipher_suites
        // + comp_len(1) + comp + extensions_len(2) + extensions.
        if len(body) < 4 || body[0] != 0x01 {
                return ""
        }
        off := 4
        if off+2+32+1 > len(body) {
                return ""
        }
        off += 2 + 32 // skip legacy_version + random
        sidLen := int(body[off])
        off++
        if off+sidLen > len(body) {
                return ""
        }
        off += sidLen
        if off+2 > len(body) {
                return ""
        }
        csLen := int(body[off])<<8 | int(body[off+1])
        off += 2
        if off+csLen > len(body) {
                return ""
        }
        off += csLen
        if off+1 > len(body) {
                return ""
        }
        compLen := int(body[off])
        off++
        if off+compLen > len(body) {
                return ""
        }
        off += compLen
        if off+2 > len(body) {
                return ""
        }
        extLen := int(body[off])<<8 | int(body[off+1])
        off += 2
        if off+extLen > len(body) {
                return ""
        }
        exts := body[off : off+extLen]
        // Walk extensions.
        for len(exts) >= 4 {
                code := int(exts[0])<<8 | int(exts[1])
                ln := int(exts[2])<<8 | int(exts[3])
                exts = exts[4:]
                if len(exts) < ln {
                        break
                }
                val := exts[:ln]
                exts = exts[ln:]
                if code == 0x0000 {
                        // SNI extension. Value: 2-byte list length + list of
                        // { 1-byte name_type, 2-byte name_length, name }.
                        if len(val) < 2 {
                                return ""
                        }
                        listLen := int(val[0])<<8 | int(val[1])
                        if listLen+2 > len(val) {
                                return ""
                        }
                        lst := val[2 : 2+listLen]
                        for len(lst) >= 3 {
                                nameType := lst[0]
                                nameLen := int(lst[1])<<8 | int(lst[2])
                                lst = lst[3:]
                                if len(lst) < nameLen {
                                        break
                                }
                                if nameType == 0 { // host_name
                                        return string(lst[:nameLen])
                                }
                                lst = lst[nameLen:]
                        }
                }
        }
        return ""
}
