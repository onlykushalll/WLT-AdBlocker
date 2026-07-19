package m3uprune

import (
        "strings"
        "testing"
)

func TestPruneSpliceOut(t *testing.T) {
        p := New(`ads\.example\.com`, `manifest\.m3u8`)

        playlist := `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:10
#EXTINF:10.0,
segment1.ts
#EXT-X-CUE:TYPE="SpliceOut"
#EXT-X-ASSET:CAID="ad123"
#EXT-X-SCTE35:OUT
#EXTINF:30.0,
https://ads.example.com/ad1.ts
#EXT-X-CUE-IN
#EXT-X-SCTE35:IN
#EXTINF:10.0,
segment2.ts
#EXT-X-ENDLIST`

        pruned := p.Prune(playlist)

        // The ad segment and cue markers should be removed
        if strings.Contains(pruned, "ads.example.com") {
                t.Error("ad segment URL still present after pruning")
        }
        if strings.Contains(pruned, "SpliceOut") {
                t.Error("SpliceOut marker still present after pruning")
        }
        if strings.Contains(pruned, "CAID") {
                t.Error("ASSET marker still present after pruning")
        }
        // Video segments should still be there
        if !strings.Contains(pruned, "segment1.ts") {
                t.Error("video segment1 removed — should be kept")
        }
        if !strings.Contains(pruned, "segment2.ts") {
                t.Error("video segment2 removed — should be kept")
        }
        if !strings.Contains(pruned, "#EXT-X-ENDLIST") {
                t.Error("ENDLIST removed — should be kept")
        }
}

func TestPruneExtinfAd(t *testing.T) {
        p := New(`adserver\.com/ad`, ``)

        playlist := `#EXTM3U
#EXTINF:10.0,
video1.ts
#EXTINF:15.0,
https://adserver.com/ad/clip1.ts
#EXT-X-DISCONTINUITY
#EXTINF:10.0,
video2.ts
#EXT-X-ENDLIST`

        pruned := p.Prune(playlist)

        if strings.Contains(pruned, "adserver.com") {
                t.Error("ad URL still present")
        }
        if strings.Contains(pruned, "DISCONTINUITY") {
                t.Error("discontinuity marker still present")
        }
        if !strings.Contains(pruned, "video1.ts") {
                t.Error("video1 should be kept")
        }
        if !strings.Contains(pruned, "video2.ts") {
                t.Error("video2 should be kept")
        }
}

func TestShouldPrune(t *testing.T) {
        p := New(``, `youtube\.com.*manifest`)

        tests := []struct {
                url   string
                match bool
        }{
                {"https://www.youtube.com/api/youtubei/v1/player/manifest", true},
                {"https://www.youtube.com/watch?v=12345", false},
                {"https://manifest.googlevideo.com/api/manifest/hls/playlist", false},
        }
        for _, tc := range tests {
                got := p.ShouldPrune(tc.url)
                if got != tc.match {
                        t.Errorf("ShouldPrune(%q) = %v, want %v", tc.url, got, tc.match)
                }
        }
}

func TestNonM3U8Unchanged(t *testing.T) {
        p := New(`ad`, ``)
        nonM3u := `{"json": "response"}`
        result := p.Prune(nonM3u)
        if result != nonM3u {
                t.Error("non-M3U8 content was modified")
        }
}

func TestNoPatternReturnsUnchanged(t *testing.T) {
        p := New(``, ``)
        playlist := `#EXTM3U\n#EXTINF:10,\nseg.ts`
        result := p.Prune(playlist)
        if result != playlist {
                t.Error("content modified when no pattern set")
        }
}

func TestRealYouTubePlaylist(t *testing.T) {
        // Simulates a real YouTube HLS playlist with ad segments
        p := New(`\.googlevideo\.com/videoplayback.*ad`, `manifest`)

        playlist := `#EXTM3U
#EXT-X-VERSION:6
#EXT-X-TARGETDURATION:4
#EXT-X-MEDIA-SEQUENCE:0
#EXTINF:4.000,
https://rr1.googlevideo.com/videoplayback/expire/.../video1.ts
#EXTINF:4.000,
https://rr1.googlevideo.com/videoplayback/expire/.../video2.ts
#EXT-X-CUE:TYPE="SpliceOut"
#EXT-X-SCTE35:OUT
#EXTINF:30.000,
https://rr2.googlevideo.com/videoplayback/ad/ads/ad_clip.ts
#EXT-X-CUE-IN
#EXT-X-SCTE35:IN
#EXTINF:4.000,
https://rr1.googlevideo.com/videoplayback/expire/.../video3.ts
#EXT-X-ENDLIST`

        pruned := p.Prune(playlist)

        // Ad clip should be removed
        if strings.Contains(pruned, "/ad/ads/") {
                t.Error("ad clip not removed")
        }
        // SpliceOut markers removed
        if strings.Contains(pruned, "SpliceOut") {
                t.Error("SpliceOut not removed")
        }
        // Video segments kept
        if !strings.Contains(pruned, "video1.ts") {
                t.Error("video1 removed")
        }
        if !strings.Contains(pruned, "video3.ts") {
                t.Error("video3 removed")
        }
}
