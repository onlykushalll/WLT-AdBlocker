// Package sponsorblock implements a minimal SponsorBlock API client.
//
// SponsorBlock (https://sponsor.ajay.app) is a crowd-sourced database of
// sponsored segment timestamps for YouTube videos. This client queries the
// public API for a given video ID + categories and returns the parsed
// segments. GenerateSkipJS() then produces a self-contained JS snippet
// that auto-skips (or mutes) the segments in the YouTube web player.
//
// Categories (per SponsorBlock spec):
//   - sponsor          — paid promotion (skip)
//   - selfpromo        — creator self-promotion (skip)
//   - interaction      — "subscribe/like/smash that bell" reminders (skip)
//   - intro            — title card / intro animation (skip)
//   - outro            — end credits / outro (skip)
//   - preview          — "previously on..." recap (skip)
//   - music_offtopic   — non-music sections in music videos (skip)
//   - filler           — tangents / irrelevant footage (skip)
//   - poi_highlight    — point of interest (do NOT skip — seek there)
//   - exclusive_access — paid-access preview (do NOT skip)
//   - chapter          — chapter marker (do NOT skip)
//
// Auto-skip categories: sponsor, selfpromo, interaction, intro, outro,
// preview, music_offtopic, filler.
// Mute-only categories: none by default (the caller can pass any subset
// but the JS distinguishes "skip" vs "mute" via ShouldSkip).
package sponsorblock

import (
        "encoding/json"
        "errors"
        "fmt"
        "io"
        "net/http"
        "net/url"
        "strings"
        "time"
)

// APIBase is the public SponsorBlock API endpoint.
const APIBase = "https://sponsor.ajay.app/api/skipSegments"

// AllCategories is the full set of SponsorBlock categories.
var AllCategories = []string{
        "sponsor", "selfpromo", "interaction", "intro", "outro", "preview",
        "music_offtopic", "filler", "poi_highlight", "exclusive_access", "chapter",
}

// SkipCategories are the categories WLT auto-skips by default.
var SkipCategories = []string{
        "sponsor", "selfpromo", "interaction", "intro", "outro", "preview",
        "music_offtopic", "filler",
}

// MuteCategories are the categories WLT mutes (but does not skip) by default.
var MuteCategories = []string{}

// NoSkipCategories are categories that should NOT be auto-skipped.
var NoSkipCategories = []string{"poi_highlight", "exclusive_access", "chapter"}

// Segment is a single SponsorBlock segment.
type Segment struct {
        UUID       string  `json:"UUID"`
        Category   string  `json:"category"`
        ActionType string  `json:"actionType"` // skip, mute, full
        StartTime  float64 `json:"startTime"`
        EndTime    float64 `json:"endTime"`
        // VideoDuration is the total video duration in seconds.
        VideoDuration float64 `json:"videoDuration"`
}

// rawSegment is the wire format returned by the API: the segment field is a
// 2-element array [start, end].
type rawSegment struct {
        UUID       string    `json:"UUID"`
        Category   string    `json:"category"`
        ActionType string    `json:"actionType"`
        Segment    []float64 `json:"segment"`
        VideoDur   float64   `json:"videoDuration"`
}

// Client is a SponsorBlock API client.
type Client struct {
        HTTP    *http.Client
        BaseURL string
        APIKey  string
}

// New returns a Client configured with sensible defaults.
func New() *Client {
        return &Client{
                HTTP:    &http.Client{Timeout: 8 * time.Second},
                BaseURL: APIBase,
        }
}

// GetSegments queries the SponsorBlock API for videoID. categories is the
// list of categories to fetch (defaults to SkipCategories if empty).
//
// Returns (segments, nil) on success. An API 404 (no segments for this
// video) returns an empty slice and nil error.
func (c *Client) GetSegments(videoID string, categories []string) ([]Segment, error) {
        videoID = strings.TrimSpace(videoID)
        if videoID == "" {
                return nil, errors.New("sponsorblock: empty videoID")
        }
        if len(categories) == 0 {
                categories = SkipCategories
        }

        q := url.Values{}
        q.Set("videoID", videoID)
        for _, cat := range categories {
                q.Add("category", cat)
        }

        req, err := http.NewRequest(http.MethodGet, c.BaseURL+"?"+q.Encode(), nil)
        if err != nil {
                return nil, err
        }
        if c.APIKey != "" {
                req.Header.Set("X-Api-Key", c.APIKey)
        }
        resp, err := c.HTTP.Do(req)
        if err != nil {
                return nil, fmt.Errorf("sponsorblock: HTTP: %w", err)
        }
        defer resp.Body.Close()

        if resp.StatusCode == http.StatusNotFound {
                return nil, nil
        }
        if resp.StatusCode != http.StatusOK {
                body, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
                return nil, fmt.Errorf("sponsorblock: HTTP %d: %s", resp.StatusCode, body)
        }

        body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
        if err != nil {
                return nil, fmt.Errorf("sponsorblock: read: %w", err)
        }
        return ParseResponse(body)
}

// ParseResponse parses a SponsorBlock API JSON response into a slice of
// Segments. The API returns an array of objects with a 2-element "segment"
// array [start, end].
func ParseResponse(body []byte) ([]Segment, error) {
        if len(body) == 0 {
                return nil, nil
        }
        var raws []rawSegment
        if err := json.Unmarshal(body, &raws); err != nil {
                return nil, fmt.Errorf("sponsorblock: parse: %w", err)
        }
        out := make([]Segment, 0, len(raws))
        for _, r := range raws {
                s := Segment{
                        UUID:           r.UUID,
                        Category:       r.Category,
                        ActionType:     r.ActionType,
                        VideoDuration:  r.VideoDur,
                }
                if len(r.Segment) >= 2 {
                        s.StartTime = r.Segment[0]
                        s.EndTime = r.Segment[1]
                }
                out = append(out, s)
        }
        return out, nil
}

// ShouldSkip returns true if segments in the given category should be
// auto-skipped (as opposed to muted or ignored).
func ShouldSkip(category string) bool {
        switch category {
        case "sponsor", "selfpromo", "interaction", "intro", "outro", "preview",
                "music_offtopic", "filler":
                return true
        }
        return false
}

// GenerateSkipJS returns a self-contained JS snippet that auto-skips (or
// mutes) the given segments on a YouTube web player. The snippet hooks the
// video element's timeupdate event and seeks past each segment when its
// start time is reached.
func GenerateSkipJS(segments []Segment) string {
        if len(segments) == 0 {
                return ""
        }
        var skip, mute []string
        for _, s := range segments {
                entry := fmt.Sprintf("{start:%.3f,end:%.3f,cat:%q}", s.StartTime, s.EndTime, s.Category)
                if ShouldSkip(s.Category) {
                        skip = append(skip, entry)
                } else if s.ActionType == "mute" {
                        mute = append(mute, entry)
                }
        }
        var b strings.Builder
        b.WriteString("(function(){\n")
        b.WriteString("  var skip = [" + strings.Join(skip, ",") + "];\n")
        b.WriteString("  var mute = [" + strings.Join(mute, ",") + "];\n")
        b.WriteString(`  function apply(){
    var v = document.querySelector('video');
    if (!v) return;
    var t = v.currentTime;
    for (var i = 0; i < skip.length; i++) {
      if (t >= skip[i].start && t < skip[i].end - 0.05) {
        v.currentTime = skip[i].end;
        break;
      }
    }
    var muted = false;
    for (var j = 0; j < mute.length; j++) {
      if (t >= mute[j].start && t < mute[j].end) { muted = true; break; }
    }
    v.muted = muted;
  }
  function attach(){
    var v = document.querySelector('video');
    if (!v) { setTimeout(attach, 500); return; }
    v.addEventListener('timeupdate', apply);
  }
  attach();
})();
`)
        b.WriteString("\n")
        return b.String()
}
