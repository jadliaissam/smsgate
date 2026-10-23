// Package secret holds token and password hashing helpers.
package secret

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"

	"golang.org/x/crypto/argon2"
)

// ArgonParams are the argon2id cost parameters.
type ArgonParams struct {
	MemoryKiB      uint32
	Time           uint32
	Threads        uint8
	MaxConcurrency int
}

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

// Hasher computes and verifies argon2id password hashes with bounded concurrency.
type Hasher struct {
	p   ArgonParams
	sem chan struct{}
}

// NewHasher returns a Hasher; non-positive concurrency or zero threads become 1.
func NewHasher(p ArgonParams) *Hasher {
	if p.MaxConcurrency < 1 {
		p.MaxConcurrency = 1
	}
	if p.Threads == 0 {
		p.Threads = 1
	}
	return &Hasher{p: p, sem: make(chan struct{}, p.MaxConcurrency)}
}

// Hash returns the encoded argon2id hash of pw using the Hasher's params.
func (h *Hasher) Hash(pw string) (string, error) {
	salt := make([]byte, saltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", err
	}
	h.sem <- struct{}{}
	key := argon2.IDKey([]byte(pw), salt, h.p.Time, h.p.MemoryKiB, h.p.Threads, keyLen)
	<-h.sem
	enc := base64.RawStdEncoding
	return fmt.Sprintf("$argon2id$v=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2.Version, h.p.MemoryKiB, h.p.Time, h.p.Threads,
		enc.EncodeToString(salt), enc.EncodeToString(key)), nil
}

// Check verifies pw against encoded using the params stored in encoded.
// Malformed or out-of-bounds encodings return false.
func (h *Hasher) Check(pw, encoded string) bool {
	parts := strings.Split(encoded, "$")
	if len(parts) != 6 || parts[0] != "" || parts[1] != "argon2id" || parts[2] != "v=19" {
		return false
	}
	var m, t, p uint64
	params := strings.Split(parts[3], ",")
	if len(params) != 3 {
		return false
	}
	for i, pair := range [3]struct {
		key string
		dst *uint64
	}{{"m=", &m}, {"t=", &t}, {"p=", &p}} {
		v, ok := strings.CutPrefix(params[i], pair.key)
		if !ok {
			return false
		}
		n, err := strconv.ParseUint(v, 10, 32)
		if err != nil {
			return false
		}
		*pair.dst = n
	}
	if m < 8 || m > maxMemoryKiB || t < 1 || t > maxTime || p < 1 || p > 255 {
		return false
	}
	enc := base64.RawStdEncoding
	salt, err := enc.DecodeString(parts[4])
	if err != nil || len(salt) == 0 {
		return false
	}
	want, err := enc.DecodeString(parts[5])
	if err != nil || len(want) == 0 || len(want) > maxKeyLen {
		return false
	}
	h.sem <- struct{}{}
	got := argon2.IDKey([]byte(pw), salt, uint32(t), uint32(m), uint8(p), uint32(len(want)))
	<-h.sem
	return subtle.ConstantTimeCompare(got, want) == 1
}
