// Package filter implements streaming blocklist loading for the WLT
// engine. Supported formats:
//
//   - Hosts format ("0.0.0.0 domain" or "127.0.0.1 domain")
//   - ABP format ("||example.com^")
//   - Bare domains ("example.com")
//   - Comments ("# ..." or "! ...")
//
// Remote lists are described by a Source struct (name + URL + format) and
// can be enumerated via a sources.json file.
package filter

import (
        "bufio"
        "encoding/json"
        "errors"
        "fmt"
        "io"
        "net/http"
        "os"
        "path/filepath"
        "strings"
        "time"

        "github.com/wlt/adblocker/internal/preparser"
)

// Format constants for Source.Format.
const (
        FormatHosts  = "hosts"
        FormatABP    = "abp"
        FormatDomain = "domain"
        FormatAuto   = "auto"
)

// Source describes one remote blocklist.
type Source struct {
        Name        string `json:"name"`
        URL         string `json:"url"`
        Format      string `json:"format"`
        Description string `json:"description,omitempty"`
        Category    string `json:"category,omitempty"`
}

// LoadedLists is the result of loading all sources from an assets
// directory.
type LoadedLists struct {
        Domains []string
        Sources []Source
        Errors  []error
}

// LoadFile streams a single blocklist file and returns the parsed domains.
// Memory-efficient: only one line is buffered at a time.
func LoadFile(path string) ([]string, error) {
        f, err := os.Open(path)
        if err != nil {
                return nil, err
        }
        defer f.Close()
        return LoadReader(f)
}

// LoadReader streams domains from any io.Reader. Phase 7d: Now processes
// pre-parsing directives (!#if/!#else/!#endif/!#include) before parsing
// domain lines. WLT-specific tokens are set: ext_wlt=true, env_android=true,
// cap_dns_blocking=true, cap_mitm=false. uBlock tokens (ext_ublock,
// env_chromium, env_firefox) are set to false so browser-only rules are
// skipped.
func LoadReader(r io.Reader) ([]string, error) {
        // Read all lines first (needed for preparser).
        var rawLines []string
        scanner := bufio.NewScanner(r)
        scanner.Buffer(make([]byte, 64*1024), 1024*1024)
        for scanner.Scan() {
                rawLines = append(rawLines, scanner.Text())
        }
        if err := scanner.Err(); err != nil {
                return nil, err
        }

        // Phase 7d: Process pre-parsing directives.
        env := map[string]bool{
                "ext_wlt":           true,
                "env_android":       true,
                "cap_dns_blocking":  true,
                "cap_mitm":          false,
                "ext_ublock":        false,
                "env_chromium":      false,
                "env_firefox":       false,
                "ext_adguard":       false,
        }
        processed := preparser.Process(rawLines, env, nil)

        // Parse the processed lines into domains.
        var out []string
        for _, line := range processed {
                line = strings.TrimSpace(line)
                if d := parseLine(line); d != "" {
                        out = append(out, d)
                }
        }
        return out, nil
}

// parseLine returns a normalized lowercase domain from a single line of a
// blocklist file (in any supported format), or "" if the line should be
// skipped (comment, blank, or unrecognized).
func parseLine(line string) string {
        if line == "" {
                return ""
        }
        // Comment.
        if strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
                return ""
        }
        // Strip inline comments.
        if i := strings.IndexAny(line, "#!"); i > 0 {
                line = strings.TrimSpace(line[:i])
        }

        // ABP format: ||example.com^
        if strings.HasPrefix(line, "||") {
                line = strings.TrimPrefix(line, "||")
                line = strings.TrimRight(line, "^")
                line = strings.TrimSuffix(line, "/")
                return normalize(line)
        }
        // ABP exception: @@||example.com^ — skip (allowlist handled elsewhere).
        if strings.HasPrefix(line, "@@||") {
                return ""
        }

        // Hosts format: "0.0.0.0 domain" or "127.0.0.1 domain"
        fields := strings.Fields(line)
        if len(fields) >= 2 {
                ip := fields[0]
                if isHostsIP(ip) {
                        return normalize(fields[1])
                }
        }
        // Single bare domain.
        if len(fields) == 1 {
                return normalize(fields[0])
        }
        return ""
}

func isHostsIP(s string) bool {
        switch s {
        case "0.0.0.0", "127.0.0.1", "255.255.255.255", "::", "::1", "0.0.0.1":
                return true
        }
        return false
}

func normalize(s string) string {
        s = strings.TrimSpace(s)
        s = strings.ToLower(s)
        s = strings.TrimSuffix(s, ".")
        s = strings.TrimPrefix(s, "*.")
        // Strip any trailing port.
        if i := strings.LastIndex(s, ":"); i > 0 && !strings.Contains(s[i+1:], ".") {
                s = s[:i]
        }
        // Must contain at least one dot to be a valid domain.
        if !strings.Contains(s, ".") {
                return ""
        }
        return s
}

// LoadFromAssets loads every blocklist found in assetsDir. Files with
// extension .txt are loaded as blocklists; sources.json (if present) is
// parsed for Source metadata. The resulting LoadedLists.Domains is the
// deduplicated union of all loaded lists.
func LoadFromAssets(assetsDir string) (*LoadedLists, error) {
        info, err := os.Stat(assetsDir)
        if err != nil {
                return nil, err
        }
        if !info.IsDir() {
                return nil, errors.New("filter: assetsDir is not a directory: " + assetsDir)
        }

        ll := &LoadedLists{}
        seen := make(map[string]bool)

        // Load sources.json if present for metadata.
        sourcesPath := filepath.Join(assetsDir, "sources.json")
        if data, err := os.ReadFile(sourcesPath); err == nil {
                _ = json.Unmarshal(data, &ll.Sources)
        }

        // Walk all .txt files.
        entries, err := os.ReadDir(assetsDir)
        if err != nil {
                return nil, err
        }
        for _, ent := range entries {
                if ent.IsDir() {
                        continue
                }
                name := ent.Name()
                if !strings.HasSuffix(name, ".txt") {
                        continue
                }
                path := filepath.Join(assetsDir, name)
                domains, err := LoadFile(path)
                if err != nil {
                        ll.Errors = append(ll.Errors, fmt.Errorf("%s: %w", name, err))
                        continue
                }
                for _, d := range domains {
                        if !seen[d] {
                                seen[d] = true
                                ll.Domains = append(ll.Domains, d)
                        }
                }
        }
        return ll, nil
}

// FetchRemote downloads a remote blocklist over HTTP with a 30-second
// timeout. The body is streamed through LoadReader for memory efficiency.
func FetchRemote(src Source) ([]string, error) {
        client := &http.Client{Timeout: 30 * time.Second}
        resp, err := client.Get(src.URL)
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode != http.StatusOK {
                return nil, fmt.Errorf("filter: %s returned HTTP %d", src.URL, resp.StatusCode)
        }
        return LoadReader(resp.Body)
}

// LoadSourcesJSON reads a sources.json file from disk.
func LoadSourcesJSON(path string) ([]Source, error) {
        data, err := os.ReadFile(path)
        if err != nil {
                return nil, err
        }
        var sources []Source
        if err := json.Unmarshal(data, &sources); err != nil {
                return nil, err
        }
        return sources, nil
}
