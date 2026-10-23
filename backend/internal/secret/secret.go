// Package secret holds token and password hashing helpers.
package secret

// ArgonParams are the argon2id cost parameters.
type ArgonParams struct {
	MemoryKiB      uint32
	Time           uint32
	Threads        uint8
	MaxConcurrency int
}
