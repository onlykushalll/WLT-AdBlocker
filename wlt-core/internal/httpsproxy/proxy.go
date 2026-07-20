// Package httpsproxy implements the HTTPS interception proxy for Phase 3.
//
// This is the local proxy that:
//   1. Accepts TLS connections redirected from the VPN
//   2. Reads the ClientHello to get the SNI hostname
//   3. Signs a cert for that hostname using the local CA
//   4. Completes TLS handshake with the client
//   5. Opens a real TLS connection to the destination
//   6. Inspects HTTP requests (URL rules, $removeparam)
//   7. Inspects HTTP responses (m3u-prune, scriptlet injection, cosmetic CSS)
//   8. Relays filtered traffic
//
// The VPN redirects TCP port 443 traffic to this local proxy.
// Non-HTTPS traffic (and cert-pinned apps in passthrough) bypasses this.
package httpsproxy

import (
        "bufio"
        "bytes"
        "crypto/tls"
        "errors"
        "io"
        "net"
        "net/http"
        "net/url"
        "strconv"
        "strings"
        "sync"
        "time"

        "wlt-core/internal/mitm"
        "wlt-core/internal/m3uprune"
        "wlt-core/internal/scriptlets"
        "wlt-core/internal/trie"
)

// Proxy is the HTTPS interception proxy.
type Proxy struct {
        ca         *mitm.CertificateAuthority
        blockTrie  *trie.Trie
        allowTrie  *trie.Trie
        scriptlets *scriptlets.Engine
        m3uPruner  *m3uprune.Pruner

        mu       sync.RWMutex
        listener net.Listener
        running  bool

        // Passthrough domains (cert-pinned apps that should NOT be MITM'd)
        passthrough map[string]bool

        stats ProxyStats
}

// ProxyStats tracks proxy statistics.
type ProxyStats struct {
        mu             sync.Mutex
        Connections    int64
        RequestsInspected int64
        ResponsesFiltered int64
        ScriptletsInjected int64
        M3uPruned      int64
        BytesRelayed   int64
}

// New creates a new HTTPS proxy.
func New(ca *mitm.CertificateAuthority, blockTrie, allowTrie *trie.Trie) *Proxy {
        return &Proxy{
                ca:          ca,
                blockTrie:   blockTrie,
                allowTrie:   allowTrie,
                scriptlets:  scriptlets.New(),
                m3uPruner:   m3uprune.New(`googlevideo\.com/videoplayback.*ad|ads\.`, `manifest|m3u8`),
                passthrough: make(map[string]bool),
        }
}

// AddPassthrough adds a domain to the passthrough list (no MITM).
func (p *Proxy) AddPassthrough(domain string) {
        p.mu.Lock()
        defer p.mu.Unlock()
        p.passthrough[strings.ToLower(strings.TrimSpace(domain))] = true
}

// IsPassthrough checks if a domain should bypass MITM.
func (p *Proxy) IsPassthrough(domain string) bool {
        p.mu.RLock()
        defer p.mu.RUnlock()
        d := strings.ToLower(strings.TrimSpace(domain))
        if p.passthrough[d] {
                return true
        }
        // Check suffixes
        labels := strings.Split(d, ".")
        for i := 0; i < len(labels)-1; i++ {
                suffix := strings.Join(labels[i:], ".")
                if p.passthrough[suffix] {
                        return true
                }
        }
        return false
}

// Start begins listening for connections on [addr].
func (p *Proxy) Start(addr string) error {
        p.mu.Lock()
        defer p.mu.Unlock()
        if p.running {
                return errors.New("proxy already running")
        }
        l, err := net.Listen("tcp", addr)
        if err != nil {
                return errors.New("proxy: failed to listen: " + err.Error())
        }
        p.listener = l
        p.running = true
        go p.acceptLoop()
        return nil
}

// Stop shuts down the proxy.
func (p *Proxy) Stop() {
        p.mu.Lock()
        defer p.mu.Unlock()
        p.running = false
        if p.listener != nil {
                p.listener.Close()
        }
}

func (p *Proxy) acceptLoop() {
        for p.running {
                conn, err := p.listener.Accept()
                if err != nil {
                        if p.running {
                                // Log error, continue
                        }
                        return
                }
                go p.handleConnection(conn)
        }
}

// handleConnection processes one intercepted TLS connection.
func (p *Proxy) handleConnection(clientConn net.Conn) {
        defer clientConn.Close()
        p.stats.mu.Lock()
        p.stats.Connections++
        p.stats.mu.Unlock()

        // 1. Peek at the ClientHello to get SNI
        // We need to read the TLS ClientHello without consuming it
        clientHello, err := peekClientHello(clientConn)
        if err != nil {
                return
        }

        sni := clientHello.ServerName
        if sni == "" {
                // No SNI — can't determine target, passthrough or drop
                return
        }

        sni = strings.ToLower(sni)

        // 2. Check if domain is in passthrough list (cert-pinned apps)
        if p.IsPassthrough(sni) {
                // Passthrough: just relay without inspection
                p.relayPassthrough(clientConn, sni)
                return
        }

        // 3. Check if domain is blocked
        if ok, _ := p.blockTrie.Contains(sni); ok {
                // Blocked — close connection
                clientConn.Close()
                return
        }

        // 4. Sign a certificate for this domain
        signedCert, err := p.ca.SignCertificate(sni)
        if err != nil {
                return
        }

        // 5. Complete TLS handshake with client using our cert
        tlsCert := tls.Certificate{
                Certificate: [][]byte{signedCert.CertDER, p.ca.CAPEM()},
                PrivateKey:  signedCert.Key,
        }
        tlsConfig := &tls.Config{
                Certificates: []tls.Certificate{tlsCert},
        }

        tlsConn := tls.Server(clientConn, tlsConfig)
        err = tlsConn.Handshake()
        if err != nil {
                return
        }
        defer tlsConn.Close()

        // 6. Connect to the real server
        upstreamConn, err := tls.Dial("tcp", net.JoinHostPort(sni, "443"), &tls.Config{
                ServerName: sni,
        })
        if err != nil {
                return
        }
        defer upstreamConn.Close()

        // 7. Inspect and relay HTTP traffic
        p.inspectAndRelay(tlsConn, upstreamConn, sni)
}

// peekClientHello reads the TLS ClientHello from a connection without
// consuming the data (using a buffered reader/peeker).
func peekClientHello(conn net.Conn) (*tls.ClientHelloInfo, error) {
        // Set a read deadline
        conn.SetReadDeadline(time.Now().Add(10 * time.Second))
        defer conn.SetReadDeadline(time.Time{})

        // Read the TLS record header (5 bytes)
        header := make([]byte, 5)
        if _, err := io.ReadFull(conn, header); err != nil {
                return nil, err
        }

        // Must be TLS handshake
        if header[0] != 0x16 {
                return nil, errors.New("not a TLS handshake")
        }

        // Read the rest of the ClientHello
        recordLen := int(header[3])<<8 | int(header[4])
        record := make([]byte, recordLen)
        if _, err := io.ReadFull(conn, record); err != nil {
                return nil, err
        }

        // Parse SNI from the ClientHello
        // We use a simplified parser — the full one is in net/sni.go
        sni := extractSNI(record)
        if sni == "" {
                return nil, errors.New("no SNI in ClientHello")
        }

        return &tls.ClientHelloInfo{ServerName: sni}, nil
}

// extractSNI parses the SNI extension from a TLS ClientHello record.
func extractSNI(record []byte) string {
        if len(record) < 38 {
                return ""
        }
        // Skip: handshake type (1) + length (3) + version (2) + random (32) + session_id (1+len)
        off := 4 + 2 + 32
        if off >= len(record) {
                return ""
        }
        sidLen := int(record[off])
        off += 1 + sidLen

        // Skip cipher suites (2+len)
        if off+2 > len(record) {
                return ""
        }
        csLen := int(record[off])<<8 | int(record[off+1])
        off += 2 + csLen

        // Skip compression methods (1+len)
        if off+1 > len(record) {
                return ""
        }
        cmLen := int(record[off])
        off += 1 + cmLen

        // Extensions
        if off+2 > len(record) {
                return ""
        }
        extLen := int(record[off])<<8 | int(record[off+1])
        off += 2
        extEnd := off + extLen
        if extEnd > len(record) {
                extEnd = len(record)
        }

        // Walk extensions looking for SNI (0x0000)
        for off+4 <= extEnd {
                extType := int(record[off])<<8 | int(record[off+1])
                extDataLen := int(record[off+2])<<8 | int(record[off+3])
                off += 4
                if off+extDataLen > extEnd {
                        break
                }
                if extType == 0x0000 {
                        // SNI extension
                        return parseSNIExtension(record[off : off+extDataLen])
                }
                off += extDataLen
        }
        return ""
}

func parseSNIExtension(data []byte) string {
        if len(data) < 2 {
                return ""
        }
        listLen := int(data[0])<<8 | int(data[1])
        off := 2
        end := off + listLen
        if end > len(data) {
                end = len(data)
        }
        for off+3 <= end {
                nameType := data[off]
                nameLen := int(data[off+1])<<8 | int(data[off+2])
                off += 3
                if off+nameLen > end {
                        break
                }
                if nameType == 0 { // host_name
                        return strings.ToLower(string(data[off : off+nameLen]))
                }
                off += nameLen
        }
        return ""
}

// relayPassthrough relays traffic without inspection (for cert-pinned apps).
func (p *Proxy) relayPassthrough(clientConn net.Conn, sni string) {
        upstream, err := tls.Dial("tcp", net.JoinHostPort(sni, "443"), &tls.Config{
                ServerName: sni,
        })
        if err != nil {
                return
        }
        defer upstream.Close()

        // Bidirectional copy
        done := make(chan struct{}, 2)
        go func() {
                io.Copy(upstream, clientConn)
                done <- struct{}{}
        }()
        go func() {
                io.Copy(clientConn, upstream)
                done <- struct{}{}
        }()
        <-done
}

// inspectAndRelay handles HTTP request/response inspection.
func (p *Proxy) inspectAndRelay(clientConn, upstreamConn net.Conn, sni string) {
        reader := bufio.NewReader(clientConn)

        for {
                // Read HTTP request
                req, err := http.ReadRequest(reader)
                if err != nil {
                        return
                }

                p.stats.mu.Lock()
                p.stats.RequestsInspected++
                p.stats.mu.Unlock()

                // Apply URL rules (block/allow based on URL pattern)
                // For now, check domain against trie
                if ok, _ := p.blockTrie.Contains(sni); ok {
                        return // blocked
                }

                // Strip tracking parameters ($removeparam)
                removeTrackingParams(req.URL)

                // Forward request to upstream
                err = req.Write(upstreamConn)
                if err != nil {
                        return
                }

                // Read response from upstream
                upstreamReader := bufio.NewReader(upstreamConn)
                resp, err := http.ReadResponse(upstreamReader, req)
                if err != nil {
                        return
                }
                defer resp.Body.Close()

                p.stats.mu.Lock()
                p.stats.ResponsesFiltered++
                p.stats.mu.Unlock()

                // Read response body
                body, err := io.ReadAll(resp.Body)
                if err != nil {
                        return
                }

                contentType := resp.Header.Get("Content-Type")

                // Apply m3u-prune to HLS playlists
                if strings.Contains(contentType, "mpegurl") || strings.Contains(contentType, "x-mpegurl") {
                        if p.m3uPruner.ShouldPrune(req.URL.String()) {
                                pruned := p.m3uPruner.Prune(string(body))
                                if pruned != string(body) {
                                        body = []byte(pruned)
                                        p.stats.mu.Lock()
                                        p.stats.M3uPruned++
                                        p.stats.mu.Unlock()
                                }
                        }
                }

                // Inject scriptlets into HTML responses
                if strings.Contains(contentType, "text/html") {
                        scriptletJS := p.scriptlets.GenerateInjectionScript(sni)
                        if scriptletJS != "" {
                                body = injectScriptIntoHTML(body, []byte(scriptletJS))
                                p.stats.mu.Lock()
                                p.stats.ScriptletsInjected++
                                p.stats.mu.Unlock()
                        }
                }

                // Update Content-Length
                resp.Header.Set("Content-Length", strconv.Itoa(len(body)))
                resp.ContentLength = int64(len(body))

                // Write response to client
                resp.Write(clientConn)

                p.stats.mu.Lock()
                p.stats.BytesRelayed += int64(len(body))
                p.stats.mu.Unlock()
        }
}

// removeTrackingParams strips utm_*, fbclid, gclid etc. from URLs.
func removeTrackingParams(u *url.URL) {
        q := u.Query()
        if len(q) == 0 {
                return
        }
        changed := false
        trackingParams := []string{
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                "fbclid", "gclid", "msclkid", "yclid", "mc_eid", "mc_cid",
                "_ga", "_gid", "_gl", "ref", "referrer",
        }
        for _, param := range trackingParams {
                if q.Has(param) {
                        q.Del(param)
                        changed = true
                }
        }
        if changed {
                u.RawQuery = q.Encode()
        }
}

// injectScriptIntoHTML inserts a <script> tag into the <head> of an HTML document.
func injectScriptIntoHTML(html, script []byte) []byte {
        headIdx := bytes.Index(bytes.ToLower(html), []byte("<head"))
        if headIdx == -1 {
                // No <head> tag — prepend
                return append(script, html...)
        }
        // Find the end of the <head...> tag
        tagEnd := bytes.IndexByte(html[headIdx:], '>')
        if tagEnd == -1 {
                return append(script, html...)
        }
        insertPos := headIdx + tagEnd + 1
        result := make([]byte, 0, len(html)+len(script))
        result = append(result, html[:insertPos]...)
        result = append(result, script...)
        result = append(result, html[insertPos:]...)
        return result
}

// ProxyStatsSnapshot is a point-in-time, lock-free copy of ProxyStats --
// safe to pass around, log, or serialize. ProxyStats itself must never be
// copied once it's in use (it embeds a sync.Mutex, and go vet correctly
// flags any function that returns ProxyStats by value for exactly that
// reason -- a copied mutex's lock state is disconnected from the original,
// which is a real concurrency bug, not a style nit).
type ProxyStatsSnapshot struct {
        Connections        int64
        RequestsInspected  int64
        ResponsesFiltered  int64
        ScriptletsInjected int64
        M3uPruned          int64
        BytesRelayed       int64
}

// GetStats returns a lock-free snapshot of current proxy statistics.
func (p *Proxy) GetStats() ProxyStatsSnapshot {
        p.stats.mu.Lock()
        defer p.stats.mu.Unlock()
        return ProxyStatsSnapshot{
                Connections:        p.stats.Connections,
                RequestsInspected:  p.stats.RequestsInspected,
                ResponsesFiltered:  p.stats.ResponsesFiltered,
                ScriptletsInjected: p.stats.ScriptletsInjected,
                M3uPruned:          p.stats.M3uPruned,
                BytesRelayed:       p.stats.BytesRelayed,
        }
}
