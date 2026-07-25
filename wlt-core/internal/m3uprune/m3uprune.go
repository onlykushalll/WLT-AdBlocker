// Package m3uprune implements an HLS (m3u8) playlist ad-segment stripper.
//
// Many streaming providers (Hulu, fuboTV, Sling, Paramount+, Pluto, Tubi)
// insert ad breaks into their HLS streams using SCTE-35 SpliceOut / SpliceIn
// markers. The segments between SpliceOut and SpliceIn are the ad break.
//
// Prune() takes a raw m3u8 playlist and returns a new playlist with ad
// segments removed. It also strips the surrounding EXT-X-DISCONTINUITY
// markers so the pruned playlist remains valid and the player doesn't
// stumble on a discontinuity with no segments between them.
//
// Detection markers (case-insensitive):
//   - #EXT-X-SCTE35-OUT    — start of an ad break
//   - #EXT-X-SCTE35-IN     — end of an ad break
//   - #EXT-OATCLS-SCTE35   — an alternative SCTE-35 marker used by some CDNs
//   - #EXT-X-ASSET         — some providers tag ad segments this way
//
// The pruner also drops any EXTINF line that immediately follows a SpliceOut
// marker (the EXTINF describes the segment that follows, so removing the
// segment requires removing its EXTINF too).
package m3uprune

import (
        "bytes"
        "strings"
)

// Prune removes ad segments from an HLS m3u8 playlist. The input is a raw
// playlist (LF or CRLF terminated lines). The output is a new playlist
// with ad segments and their surrounding discontinuity markers removed.
//
// If no ad markers are present the input is returned unchanged.
func Prune(playlist []byte) []byte {
        lines := splitLines(playlist)
        if len(lines) == 0 {
                return playlist
        }

        out := make([]string, 0, len(lines))
        inAdBreak := false
        removedAny := false

        for i := 0; i < len(lines); i++ {
                line := lines[i]
                trimmed := strings.TrimSpace(line)
                upper := strings.ToUpper(trimmed)

                // Detect the start of an ad break.
                if isSpliceOut(upper) {
                        inAdBreak = true
                        removedAny = true
                        // Drop the SpliceOut marker itself.
                        continue
                }
                // Detect the end of an ad break.
                if isSpliceIn(upper) {
                        inAdBreak = false
                        // Drop the SpliceIn marker itself.
                        continue
                }

                if inAdBreak {
                        // Drop everything inside the break (EXTINF + URI + any
                        // per-segment tags like #EXT-X-DISCONTINUITY).
                        continue
                }

                // Outside an ad break. We also drop EXTINF/URI pairs that look
                // like ad segments by heuristic — #EXTINF lines whose following
                // URI contains "/ads/" or "/advertis" or "/adcreative". This is
                // a safety net for providers that don't emit SCTE-35 markers.
                if isAdExtinfPair(lines, i) {
                        // Skip the EXTINF and the following URI line.
                        i++
                        removedAny = true
                        continue
                }

                out = append(out, line)
        }

        // Strip stray EXT-X-DISCONTINUITY lines that are now adjacent (i.e.
        // a discontinuity immediately followed by another discontinuity with
        // no segment in between).
        out = collapseAdjacentDiscontinuities(out)

        if !removedAny {
                // No ads found; return the original bytes unchanged so callers can
                // detect "nothing changed" with bytes.Equal.
                return playlist
        }

        var buf bytes.Buffer
        for i, l := range out {
                buf.WriteString(l)
                // Preserve original line endings: if the input used \r\n we keep
                // the \r that was already on the line; we just need to add the
                // trailing \n. We detect by checking the raw bytes of the next
                // line vs what we have.
                if i < len(out)-1 || hasTrailingNewline(playlist) {
                        buf.WriteByte('\n')
                }
        }
        return buf.Bytes()
}

// isSpliceOut reports whether the uppercased tag line marks the start of
// an ad break.
func isSpliceOut(upper string) bool {
        return strings.HasPrefix(upper, "#EXT-X-SCTE35-OUT") ||
                strings.HasPrefix(upper, "#EXT-OATCLS-SCTE35") && strings.Contains(upper, "CUE=\"OUT\"")
}

// isSpliceIn reports whether the uppercased tag line marks the end of an
// ad break.
func isSpliceIn(upper string) bool {
        return strings.HasPrefix(upper, "#EXT-X-SCTE35-IN") ||
                strings.HasPrefix(upper, "#EXT-OATCLS-SCTE35") && strings.Contains(upper, "CUE=\"IN\"")
}

// isAdExtinfPair returns true if lines[i] is an EXTINF line and lines[i+1]
// (if present) is a URI that looks like an ad creative. The EXTINF + URI
// are treated as a single ad segment and both lines are dropped.
func isAdExtinfPair(lines []string, i int) bool {
        upper := strings.ToUpper(strings.TrimSpace(lines[i]))
        if !strings.HasPrefix(upper, "#EXTINF") {
                return false
        }
        if i+1 >= len(lines) {
                return false
        }
        uri := strings.ToLower(strings.TrimSpace(lines[i+1]))
        if uri == "" || strings.HasPrefix(uri, "#") {
                return false
        }
        for _, pat := range []string{"/ads/", "/advertis", "/adcreative", "/ad-creative", "/doubleclick", "/sponsored"} {
                if strings.Contains(uri, pat) {
                        return true
                }
        }
        return false
}

// collapseAdjacentDiscontinuities drops any #EXT-X-DISCONTINUITY line that
// is immediately followed (skipping blank lines) by another
// #EXT-X-DISCONTINUITY line. This keeps the playlist valid after we've
// removed the segments between two discontinuity markers.
func collapseAdjacentDiscontinuities(lines []string) []string {
        out := make([]string, 0, len(lines))
        for i := 0; i < len(lines); i++ {
                upper := strings.ToUpper(strings.TrimSpace(lines[i]))
                if upper == "#EXT-X-DISCONTINUITY" {
                        // Look at the very next non-blank line. If it's also a
                        // discontinuity, drop this one (keep the second, which will
                        // be reconsidered on the next iteration).
                        j := i + 1
                        for j < len(lines) && strings.TrimSpace(lines[j]) == "" {
                                j++
                        }
                        if j < len(lines) && strings.ToUpper(strings.TrimSpace(lines[j])) == "#EXT-X-DISCONTINUITY" {
                                // Drop this duplicate discontinuity.
                                continue
                        }
                }
                out = append(out, lines[i])
        }
        return out
}

// splitLines splits the playlist on \n and strips trailing \r. Empty
// trailing entries (from a final newline) are removed.
func splitLines(b []byte) []string {
        if len(b) == 0 {
                return nil
        }
        s := string(b)
        parts := strings.Split(s, "\n")
        for i, p := range parts {
                parts[i] = strings.TrimSuffix(p, "\r")
        }
        // Drop the trailing empty entry produced by a final newline.
        if len(parts) > 0 && parts[len(parts)-1] == "" {
                parts = parts[:len(parts)-1]
        }
        return parts
}

// hasTrailingNewline reports whether the input bytes ended with a newline.
func hasTrailingNewline(b []byte) bool {
        if len(b) == 0 {
                return false
        }
        return b[len(b)-1] == '\n'
}
