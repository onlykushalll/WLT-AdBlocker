package sponsorblock

import (
	"strings"
	"testing"
)

func TestGenerateSkipJS(t *testing.T) {
	segments := []Segment{
		{UUID: "a", Category: "sponsor", ActionType: "skip", StartTime: 5.0, EndTime: 10.0},
		{UUID: "b", Category: "intro", ActionType: "skip", StartTime: 0.0, EndTime: 3.0},
		{UUID: "c", Category: "selfpromo", ActionType: "mute", StartTime: 15.0, EndTime: 20.0},
	}
	js := GenerateSkipJS(segments)
	if !strings.Contains(js, "skip[") {
		t.Errorf("missing skip array: %s", js)
	}
	if !strings.Contains(js, "currentTime = skip[i].end") {
		t.Errorf("missing seek logic: %s", js)
	}
	if !strings.Contains(js, "5.000") {
		t.Errorf("missing start time: %s", js)
	}
}

func TestGenerateSkipJSEmpty(t *testing.T) {
	if js := GenerateSkipJS(nil); js != "" {
		t.Errorf("expected empty JS, got %s", js)
	}
}

func TestParseResponse(t *testing.T) {
	body := []byte(`[
		{"UUID":"a","category":"sponsor","actionType":"skip","segment":[5.0,10.0],"videoDuration":120},
		{"UUID":"b","category":"intro","actionType":"skip","segment":[0.0,3.5]}
	]`)
	segs, err := ParseResponse(body)
	if err != nil {
		t.Fatalf("ParseResponse: %v", err)
	}
	if len(segs) != 2 {
		t.Fatalf("expected 2 segments, got %d", len(segs))
	}
	if segs[0].UUID != "a" || segs[0].Category != "sponsor" {
		t.Errorf("seg0 wrong: %+v", segs[0])
	}
	if segs[0].StartTime != 5.0 || segs[0].EndTime != 10.0 {
		t.Errorf("seg0 times wrong: %+v", segs[0])
	}
	if segs[1].EndTime != 3.5 {
		t.Errorf("seg1 end wrong: %+v", segs[1])
	}
}

func TestParseResponseEmpty(t *testing.T) {
	segs, err := ParseResponse(nil)
	if err != nil {
		t.Fatalf("empty ParseResponse: %v", err)
	}
	if len(segs) != 0 {
		t.Errorf("expected 0 segs, got %d", len(segs))
	}
}

func TestParseResponseInvalid(t *testing.T) {
	_, err := ParseResponse([]byte(`{not valid json`))
	if err == nil {
		t.Fatal("expected error on invalid JSON")
	}
}

func TestShouldSkip(t *testing.T) {
	cases := map[string]bool{
		"sponsor":         true,
		"selfpromo":       true,
		"interaction":     true,
		"intro":           true,
		"outro":           true,
		"preview":         true,
		"music_offtopic":  true,
		"filler":          true,
		"poi_highlight":   false,
		"exclusive_access": false,
		"chapter":         false,
	}
	for cat, want := range cases {
		if got := ShouldSkip(cat); got != want {
			t.Errorf("ShouldSkip(%q)=%v want %v", cat, got, want)
		}
	}
}
