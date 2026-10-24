//go:build race

package realtime_test

// raceEnabled: the race detector inflates stacks and heap, so memory
// thresholds are only logged.
const raceEnabled = true
