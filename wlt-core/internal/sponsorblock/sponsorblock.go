// Package sponsorblock implements a client for the SponsorBlock API
// (sponsor.ajay.app) — a crowdsourced database of sponsor/ad segments
// in YouTube videos.
//
// Instead of blocking ads, SponsorBlock SKIPS them: it knows the exact
// timestamps where sponsored content starts and ends, and the player
// jumps past them automatically.
//
// API: https://wiki.sponsor.ajay.app/w/API_Docs
// GET https://sponsor.ajay.app/api/skipSegments?videoID=VIDEO_ID
// Returns: [{"segment":[start,end],"category":"sponsor","UUID":"..."}]
package sponsorblock

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// Segment represents a sponsored segment to skip.
type Segment struct {
	Start    float64 `json:"segment"` // [0] = start time in seconds
	End      float64
	Category string `json:"category"` // sponsor, selfpromo, interaction, intro, outro, preview, music_offtopic
	UUID     string `json:"UUID"`
}

// RawSegment is the API response format.
type RawSegment struct {
	Segment   []float64 `json:"segment"`
	Category  string    `json:"category"`
	UUID      string    `json:"UUID"`
}

// Client queries the SponsorBlock API.
type Client struct {
	apiURL   string
	httpClient *http.Client
}

// New creates a SponsorBlock client.
func New() *Client {
	return &Client{
		apiURL: "https://sponsor.ajay.app/api",
		httpClient: &http.Client{
			Timeout: 5 * time.Second,
		},
	}
}

// GetSegments queries the SponsorBlock API for a video ID.
// Returns all sponsored segments that should be skipped.
func (c *Client) GetSegments(videoID string) ([]Segment, error) {
	if videoID == "" {
		return nil, errors.New("empty video ID")
	}

	u := fmt.Sprintf("%s/skipSegments?videoID=%s",
		c.apiURL, url.QueryEscape(videoID))

	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", "WLT-Adblocker/0.1")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode == 404 {
		return nil, nil // No segments for this video — not an error
	}
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("sponsorblock: HTTP %d", resp.StatusCode)
	}

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	var raw []RawSegment
	if err := json.Unmarshal(body, &raw); err != nil {
		return nil, err
	}

	segments := make([]Segment, 0, len(raw))
	for _, r := range raw {
		if len(r.Segment) >= 2 {
			segments = append(segments, Segment{
				Start:    r.Segment[0],
				End:      r.Segment[1],
				Category: r.Category,
				UUID:     r.UUID,
			})
		}
	}

	return segments, nil
}

// GenerateSkipJS generates JavaScript that queries the SponsorBlock API
// and auto-skips sponsored segments in a YouTube video player.
//
// This scriptlet:
// 1. Extracts the video ID from the URL
// 2. Fetches skip segments from sponsor.ajay.app
// 3. Monitors the video player's currentTime
// 4. Jumps past any sponsored segment
func (c *Client) GenerateSkipJS() string {
	return `(function(){
		var videoId = new URLSearchParams(window.location.search).get('v');
		if(!videoId) {
			// Try embed URL
			var match = window.location.pathname.match(/\/embed\/([^/?]+)/);
			if(match) videoId = match[1];
		}
		if(!videoId) return;

		fetch('https://sponsor.ajay.app/api/skipSegments?videoID=' + videoId, {
			headers: {'User-Agent': 'WLT-Adblocker'}
		}).then(function(r) {
			if(!r.ok) return [];
			return r.json();
		}).then(function(segments) {
			if(!segments || segments.length === 0) return;

			var skipData = segments.map(function(s) {
				return {start: s.segment[0], end: s.segment[1], cat: s.category};
			});

			var skipInterval = setInterval(function() {
				var v = document.querySelector('video');
				if(!v || !v.duration) return;

				for(var i = 0; i < skipData.length; i++) {
					var seg = skipData[i];
					if(v.currentTime >= seg.start && v.currentTime < seg.end - 0.3) {
						v.currentTime = seg.end;
						// Show a skip notice
						var notice = document.createElement('div');
						notice.style.cssText = 'position:absolute;top:10px;right:10px;background:rgba(0,0,0,0.8);color:#0f0;padding:4px 12px;border-radius:4px;font:14px sans-serif;z-index:9999;pointer-events:none;';
						notice.textContent = 'WLT: Skipped ' + seg.cat;
						var player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
						if(player) {
							player.appendChild(notice);
							setTimeout(function() { notice.remove(); }, 2000);
						}
						break;
					}
				}
			}, 200);
		}).catch(function() {});
	})();`
}

// APIURL returns the API endpoint URL.
func (c *Client) APIURL() string {
	return c.apiURL
}
