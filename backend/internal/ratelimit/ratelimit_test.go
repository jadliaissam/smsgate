package ratelimit

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"sync"
	"testing"
	"time"
)

type clock struct{ ns int64 }

func (c *clock) now() int64              { return c.ns }
func (c *clock) advance(d time.Duration) { c.ns += int64(d) }

func newTest(perMin, maxKeys int) (*Limiter, *clock) {
	l := New(perMin, maxKeys)
	c := &clock{ns: 1}
	l.now = c.now
	return l, c
}

func key(b byte) Key { return Key{15: b} }

func TestDisabledIsNil(t *testing.T) {
	if New(0, 10) != nil {
		t.Fatal("perMin 0 must disable the limiter")
	}
	var l *Limiter
	called := false
	l.Middleware(func(*http.Request) Key { return Key{} })(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { called = true })).
		ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/", nil))
	if !called {
		t.Fatal("nil limiter must pass through")
	}
}

func TestBurstThenRefill(t *testing.T) {
	l, c := newTest(60, 100) // 1 token/s, burst 60
	for i := range 60 {
		if ok, _ := l.Allow(key(1)); !ok {
			t.Fatalf("request %d rejected within burst", i)
		}
	}
	ok, wait := l.Allow(key(1))
	if ok || wait <= 0 || wait > time.Second {
		t.Fatalf("over burst: ok=%v wait=%s", ok, wait)
	}
	if ok, _ := l.Allow(key(2)); !ok {
		t.Fatal("keys must be independent")
	}
	c.advance(time.Second)
	if ok, _ := l.Allow(key(1)); !ok {
		t.Fatal("not refilled after 1s")
	}
	if ok, _ := l.Allow(key(1)); ok {
		t.Fatal("refilled more than one token")
	}
	c.advance(time.Hour) // refill is capped at burst
	for i := range 60 {
		if ok, _ := l.Allow(key(1)); !ok {
			t.Fatalf("request %d after idle", i)
		}
	}
	if ok, _ := l.Allow(key(1)); ok {
		t.Fatal("burst exceeded after long idle")
	}
}

func TestMemoryBounded(t *testing.T) {
	l, c := newTest(10, 100)
	for i := range 10000 {
		var k Key
		k[0], k[1] = byte(i), byte(i>>8)
		l.Allow(k)
		if i%100 == 0 {
			c.advance(time.Millisecond)
		}
	}
	if n := l.Len(); n > 100 {
		t.Fatalf("%d keys kept, max 100", n)
	}
}

func TestIdleEntriesEvictedFirst(t *testing.T) {
	l, c := newTest(60, 1) // single shard of one key
	l.Allow(key(1))
	l.Allow(key(1))
	c.advance(2 * time.Minute) // key 1 is full again: idle
	l.Allow(key(2))
	if n := l.Len(); n != 1 {
		t.Fatalf("len %d", n)
	}
}

func TestAllowNoAlloc(t *testing.T) {
	l, _ := newTest(1000000, 1000)
	k := key(7)
	if n := testing.AllocsPerRun(1000, func() { l.Allow(k) }); n != 0 {
		t.Fatalf("%v allocs", n)
	}
}

func TestConcurrent(t *testing.T) {
	l := New(60, 1000)
	var wg sync.WaitGroup
	var mu sync.Mutex
	allowed := 0
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for range 50 {
				if ok, _ := l.Allow(key(9)); ok {
					mu.Lock()
					allowed++
					mu.Unlock()
				}
			}
		}()
	}
	wg.Wait()
	if allowed < 60 || allowed > 61 { // 61: a token may refill during the run
		t.Fatalf("allowed %d of 400, burst 60", allowed)
	}
}

func TestMiddleware429(t *testing.T) {
	l, _ := newTest(1, 10)
	h := l.Middleware(func(*http.Request) Key { return key(1) })(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("POST", "/", nil))
	if rec.Code != 204 {
		t.Fatal(rec.Code)
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("POST", "/", nil))
	var body struct {
		Error struct{ Code string } `json:"error"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &body)
	if rec.Code != 429 || body.Error.Code != "rate_limited" || rec.Header().Get("Retry-After") != "60" {
		t.Fatalf("%d %q %s", rec.Code, rec.Header().Get("Retry-After"), rec.Body)
	}
}

var private = []netip.Prefix{
	netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("172.16.0.0/12"),
	netip.MustParsePrefix("192.168.0.0/16"), netip.MustParsePrefix("127.0.0.1/32"),
}

func TestClientIP(t *testing.T) {
	cases := []struct {
		name, remote string
		xff          []string
		want         string
	}{
		{"direct, no xff", "203.0.113.7:5000", nil, "203.0.113.7"},
		{"untrusted peer: xff ignored", "203.0.113.7:5000", []string{"1.2.3.4"}, "203.0.113.7"},
		{"trusted peer: last hop", "10.0.1.2:80", []string{"198.51.100.9"}, "198.51.100.9"},
		{"spoofed left part ignored", "10.0.1.2:80", []string{"1.1.1.1, 198.51.100.9"}, "198.51.100.9"},
		{"chained trusted proxies skipped", "10.0.1.2:80", []string{"6.6.6.6, 198.51.100.9, 10.0.0.3"}, "198.51.100.9"},
		{"several header lines", "127.0.0.1:80", []string{"6.6.6.6", "198.51.100.9"}, "198.51.100.9"},
		{"trusted peer, no xff", "10.0.1.2:80", nil, "10.0.1.2"},
		{"garbage hop stops the walk", "10.0.1.2:80", []string{"6.6.6.6, junk"}, "10.0.1.2"},
		{"all hops trusted", "10.0.1.2:80", []string{"192.168.1.1, 10.0.0.3"}, "192.168.1.1"},
		{"ipv4-mapped peer", "[::ffff:10.0.1.2]:80", []string{"198.51.100.9"}, "198.51.100.9"},
		{"ipv6 client", "10.0.1.2:80", []string{"2001:db8::1"}, "2001:db8::1"},
	}
	for _, tc := range cases {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = tc.remote
		for _, v := range tc.xff {
			r.Header.Add("X-Forwarded-For", v)
		}
		if got := ClientIP(r, private); got.String() != tc.want {
			t.Errorf("%s: got %s, want %s", tc.name, got, tc.want)
		}
	}
}

func TestIPKeyGroupsIPv6By64(t *testing.T) {
	a := AddrKey(netip.MustParseAddr("2001:db8:1:2:aaaa::1"))
	b := AddrKey(netip.MustParseAddr("2001:db8:1:2:bbbb::2"))
	c := AddrKey(netip.MustParseAddr("2001:db8:1:3::1"))
	if a != b || a == c {
		t.Fatal("IPv6 keys must aggregate per /64")
	}
	if AddrKey(netip.MustParseAddr("1.2.3.4")) == AddrKey(netip.MustParseAddr("1.2.3.5")) {
		t.Fatal("IPv4 keys are per address")
	}
}

func BenchmarkAllow(b *testing.B) {
	l := New(1000000, 100000)
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		var k Key
		i := 0
		for pb.Next() {
			k[0], k[1] = byte(i), byte(i>>8)
			l.Allow(k)
			i++
		}
	})
}
