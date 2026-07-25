package bloom

import "math"

// mathLog delegates to math.Log so the public Filter file doesn't import
// math directly (keeps the surface of bloom.go narrow for reviewers).
func mathLog(x float64) float64 { return math.Log(x) }
