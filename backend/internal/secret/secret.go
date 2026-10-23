// Package secret holds token and password hashing helpers.
package secret

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync/atomic"

	"golang.org/x/crypto/argon2"
)

// ArgonParams are the argon2id cost parameters.
type ArgonParams struct {
	MemoryKiB      uint32
	Time           uint32
	Threads        uint8
	MaxConcurrency int
	// MaxQueue bounds callers waiting for a hashing slot; beyond it Hash and
	// Check fail fast with ErrBusy. 0 means no waiting at all.
	MaxQueue int
}

// ErrBusy is returned when every hashing slot is taken and the wait queue is full.
var ErrBusy = errors.New("secret: password hasher busy")

const (
	crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	saltLen   = 16
	keyLen    = 32

	// Upper bounds accepted from an encoded hash (defensive).
	maxMemoryKiB = 1 << 20 // 1 GiB
	maxTime      = 100
	maxKeyLen    = 1024
)

// Token returns prefix followed by 43 URL-safe base64 chars (32 random bytes).
func Token(prefix string) string {
	var b [32]byte
	mustRead(b[:])
	buf := make([]byte, 0, len(prefix)+43)
	buf = append(buf, prefix...)
	buf = base64.RawURLEncoding.AppendEncode(buf, b[:])
	return string(buf)
}

// PairingCode returns an 8 character Crockford base32 code.
func PairingCode() string {
	var b [8]byte
	mustRead(b[:])
	for i := range b {
		b[i] = crockford[b[i]&31] // 256 % 32 == 0: no modulo bias
	}
	return string(b[:])
}

// Hash returns the lowercase hex SHA-256 of s.
func Hash(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

func mustRead(b []byte) {
	if _, err := rand.Read(b); err != nil {
		panic("secret: crypto/rand failed: " + err.Error())
	}
}

// Hasher computes and verifies argon2id password hashes with bounded
// concurrency (MaxConcurrency running) and a bounded, cancellable wait queue
// (MaxQueue waiting), so a burst of logins can neither exhaust memory nor pile
// up goroutines behind the semaphore.
type Hasher struct {
	p       ArgonParams
	sem     chan struct{}
	waiting atomic.Int64
}

// NewHasher returns a Hasher; non-positive concurrency or zero threads become
// 1, a negative queue becomes 0.
func NewHasher(p ArgonParams) *Hasher {
	if p.MaxConcurrency < 1 {
		p.MaxConcurrency = 1
	}
	if p.Threads == 0 {
		p.Threads = 1
	}
	p.MaxQueue = max(p.MaxQueue, 0)
	return &Hasher{p: p, sem: make(chan struct{}, p.MaxConcurrency)}
}

// acquire takes a hashing slot. It returns ctx.Err() if ctx is (or becomes)
// done before a slot frees up, and ErrBusy when MaxQueue callers already wait.
// On success the caller must call release.
func (h *Hasher) acquire(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	select {
	case h.sem <- struct{}{}:
		return nil
	default:
	}
	if h.waiting.Add(1) > int64(h.p.MaxQueue) {
		h.waiting.Add(-1)
		return ErrBusy
	}
	defer h.waiting.Add(-1)
	select {
	case h.sem <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (h *Hasher) release() { <-h.sem }

func (h *Hasher) key(ctx context.Context, pw string, salt []byte, t, m uint32, p uint8, n uint32) ([]byte, error) {
	if err := h.acquire(ctx); err != nil {
		return nil, err
	}
	defer h.release()
	return argon2.IDKey([]byte(pw), salt, t, m, p, n), nil
}

// Hash returns the encoded argon2id hash of pw using the Hasher's params.
// Errors: ctx.Err(), ErrBusy, or a crypto/rand failure.
func (h *Hasher) Hash(ctx context.Context, pw string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	key, err := h.key(ctx, pw, salt, h.p.Time, h.p.MemoryKiB, h.p.Threads, keyLen)
	if err != nil {
		return "", err
	}
	enc := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, h.p.MemoryKiB, h.p.Time, h.p.Threads,
		enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

// Check verifies pw against encoded using the params stored in encoded.
// Malformed or out-of-bounds encodings return false and a nil error; err is
// non-nil only when no hash was computed (ctx.Err() or ErrBusy).
func (h *Hasher) Check(ctx context.Context, pw, encoded string) (bool, error) {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" || parts[2] != "v=19" {
		return false, nil
	}
	var m, t, p uint64
	params := strings.Split(parts[3], ",")
	if len(params) != 3 {
		return false, nil
	}
	for i, pair := range [3]struct {
		key string
		dst *uint64
	}{{"m=", &m}, {"t=", &t}, {"p=", &p}} {
		v, ok := strings.CutPrefix(params[i], pair.key)
		if !ok {
			return false, nil
		}
		n, err := strconv.ParseUint(v, 10, 32)
		if err != nil {
			return false, nil
		}
		*pair.dst = n
	}
	if m < 8 || m > maxMemoryKiB || t < 1 || t > maxTime || p < 1 || p > 255 {
		return false, nil
	}
	enc := base64.RawStdEncoding
	salt, err := enc.DecodeString(parts[4])
	if err != nil || len(salt) == 0 {
		return false, nil
	}
	want, err := enc.DecodeString(parts[5])
	if err != nil || len(want) == 0 || len(want) > maxKeyLen {
		return false, nil
	}
	got, err := h.key(ctx, pw, salt, uint32(t), uint32(m), uint8(p), uint32(len(want)))
	if err != nil {
		return false, err
	}
	return subtle.ConstantTimeCompare(got, want) == 1, nil
}
