// Package ratelimit is a small in-process token-bucket rate limiter: sharded
// maps behind mutexes, lazy refill (no background goroutine) and a hard bound
// on the number of tracked keys. Allow does not allocate once a key exists.
//
// Limits are per process: with N instances the effective limit is up to N
// times higher.
package ratelimit

import (
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/smsgate/backend/internal/httpx"
)

// Key identifies a client: an IP (see AddrKey) or a 16-byte id such as a UUID.
type Key [16]byte

const (
	maxShards = 64
	// evictScan bounds the work of making room in a full shard.
	evictScan = 32
)

type bucket struct {
	tokens float64
	last   int64 // ns on the limiter clock
}

type shard struct {
	mu sync.Mutex
	m  map[Key]bucket
	_  [40]byte // keep hot mutexes on separate cache lines
}

// Limiter allows perMin requests per minute per key with a burst of perMin.
// A nil *Limiter allows everything.
type Limiter struct {
	rate     float64 // tokens per ns
	burst    float64
	perShard int
	shards   []shard
	now      func() int64
}

// New returns a limiter allowing perMin requests per minute per key (burst
// perMin) and tracking at most maxKeys keys, or nil (disabled) when perMin < 1.
func New(perMin, maxKeys int) *Limiter {
	if perMin < 1 {
		return nil
	}
	maxKeys = max(maxKeys, 1)
	n := min(maxShards, maxKeys)
	start := time.Now()
	l := &Limiter{
		rate:     float64(perMin) / float64(time.Minute),
		burst:    float64(perMin),
		perShard: maxKeys / n,
		shards:   make([]shard, n),
		now:      func() int64 { return int64(time.Since(start)) }, // monotonic
	}
	for i := range l.shards {
		l.shards[i].m = make(map[Key]bucket)
	}
	return l
}

func (l *Limiter) shard(k Key) *shard {
	h := uint32(2166136261) // FNV-1a
	for _, b := range k {
		h ^= uint32(b)
		h *= 16777619
	}
	return &l.shards[h%uint32(len(l.shards))]
}

// Allow takes one token for k. When none is left it returns false and how long
// until the next token.
func (l *Limiter) Allow(k Key) (bool, time.Duration) {
	now := l.now()
	s := l.shard(k)
	s.mu.Lock()
	b, ok := s.m[k]
	if ok {
		b.tokens = min(l.burst, b.tokens+float64(now-b.last)*l.rate)
	} else {
		if len(s.m) >= l.perShard {
			l.evict(s, now)
		}
		b.tokens = l.burst
	}
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		s.m[k] = b
		s.mu.Unlock()
		return true, 0
	}
	s.m[k] = b
	s.mu.Unlock()
	return false, time.Duration((1 - b.tokens) / l.rate)
}

// evict makes room in a full shard: among up to evictScan entries (map
// iteration starts at a random point) it drops the idle ones, i.e. buckets
// that have refilled completely and so carry no state; if none is idle it
// drops the first one seen. Caller holds s.mu.
func (l *Limiter) evict(s *shard, now int64) {
	var victim Key
	seen, dropped := 0, false
	for k, b := range s.m {
		if seen == 0 {
			victim = k
		}
		if b.tokens+float64(now-b.last)*l.rate >= l.burst {
			delete(s.m, k)
			dropped = true
		}
		if seen++; seen >= evictScan {
			break
		}
	}
	if !dropped {
		delete(s.m, victim)
	}
}

// Len returns the number of tracked keys.
func (l *Limiter) Len() int {
	n := 0
	for i := range l.shards {
		s := &l.shards[i]
		s.mu.Lock()
		n += len(s.m)
		s.mu.Unlock()
	}
	return n
}

var errLimited = &httpx.Error{Status: http.StatusTooManyRequests, Code: "rate_limited", Message: "too many requests, retry later"}

// Middleware rejects requests over the limit for key(r) with 429 rate_limited
// and a Retry-After header (whole seconds). A nil limiter passes everything.
func (l *Limiter) Middleware(key func(*http.Request) Key) func(http.Handler) http.Handler {
	if l == nil {
		return func(next http.Handler) http.Handler { return next }
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			ok, wait := l.Allow(key(r))
			if ok {
				next.ServeHTTP(w, r)
				return
			}
			secs := max(int64((wait+time.Second-1)/time.Second), 1)
			w.Header().Set("Retry-After", strconv.FormatInt(secs, 10))
			httpx.Handle(func(http.ResponseWriter, *http.Request) error { return errLimited }).ServeHTTP(w, r)
		})
	}
}

// AddrKey is the rate-limit key of an IP address. IPv6 addresses are grouped
// by /64 (what one subscriber typically gets), so rotating addresses within a
// prefix does not dodge the limit.
func AddrKey(a netip.Addr) Key {
	a = a.Unmap()
	k := Key(a.As16())
	if a.Is6() {
		clear(k[8:])
	}
	return k
}

// IPKey returns a key function for client IPs (see ClientIP). Requests whose
// address cannot be determined share the zero key.
func IPKey(trusted []netip.Prefix) func(*http.Request) Key {
	return func(r *http.Request) Key {
		a := ClientIP(r, trusted)
		if !a.IsValid() {
			return Key{}
		}
		return AddrKey(a)
	}
}

func contains(ps []netip.Prefix, a netip.Addr) bool {
	for _, p := range ps {
		if p.Contains(a) {
			return true
		}
	}
	return false
}

// ClientIP returns the client address of r. X-Forwarded-For is consulted only
// when the immediate peer is a trusted proxy; it is then walked from the right
// (the hop the trusted proxy appended) skipping further trusted proxies, and
// the first untrusted hop is the client. Hops left of it are client-supplied
// and ignored. An unparsable hop ends the walk at the last trusted address.
func ClientIP(r *http.Request, trusted []netip.Prefix) netip.Addr {
	ap, err := netip.ParseAddrPort(r.RemoteAddr)
	if err != nil {
		return netip.Addr{}
	}
	addr := ap.Addr().Unmap()
	if !contains(trusted, addr) {
		return addr
	}
	vals := r.Header.Values("X-Forwarded-For")
	for i := len(vals) - 1; i >= 0; i-- {
		v := vals[i]
		for v != "" {
			hop := v
			if j := strings.LastIndexByte(v, ','); j >= 0 {
				hop, v = v[j+1:], v[:j]
			} else {
				v = ""
			}
			a, err := netip.ParseAddr(strings.TrimSpace(hop))
			if err != nil {
				return addr
			}
			addr = a.Unmap()
			if !contains(trusted, addr) {
				return addr
			}
		}
	}
	return addr
}
