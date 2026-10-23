package secret

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

var ctx = context.Background()

var defaults = ArgonParams{MemoryKiB: 19456, Time: 2, Threads: 1, MaxConcurrency: 2}

func TestToken(t *testing.T) {
	a, b := Token("sk_"), Token("sk_")
	if !strings.HasPrefix(a, "sk_") || len(a) != len("sk_")+43 {
		t.Fatalf("bad token %q", a)
	}
	if a == b {
		t.Fatal("tokens must differ")
	}
}

func TestPairingCode(t *testing.T) {
	const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
	for range 100 {
		c := PairingCode()
		if len(c) != 8 {
			t.Fatalf("len %d", len(c))
		}
		for _, r := range c {
			if !strings.ContainsRune(alphabet, r) {
				t.Fatalf("bad char %q in %q", r, c)
			}
		}
	}
}

func TestHash(t *testing.T) {
	// sha256("abc")
	want := "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	if got := Hash("abc"); got != want {
		t.Fatalf("got %s", got)
	}
}

func TestPasswordRoundTrip(t *testing.T) {
	h := NewHasher(defaults)
	enc, err := h.Hash(ctx, "correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(enc, "$argon2id$v=19$m=19456,t=2,p=1$") {
		t.Fatalf("bad prefix %q", enc)
	}
	if ok, err := h.Check(ctx, "correct horse", enc); !ok || err != nil {
		t.Fatal("correct password rejected")
	}
	if ok, _ := h.Check(ctx, "wrong", enc); ok {
		t.Fatal("wrong password accepted")
	}
}

func TestCheckWithOldParams(t *testing.T) {
	old := NewHasher(ArgonParams{MemoryKiB: 8192, Time: 1})
	enc, err := old.Hash(ctx, "pw")
	if err != nil {
		t.Fatal(err)
	}
	if ok, _ := NewHasher(defaults).Check(ctx, "pw", enc); !ok {
		t.Fatal("old-param hash rejected")
	}
}

func TestCheckPasswordGarbage(t *testing.T) {
	h := NewHasher(ArgonParams{MemoryKiB: 8192, Time: 1})
	for _, enc := range []string{
		"nope", "", "$argon2id$v=19$m=1,t=1,p=1$$",
		"$argon2id$v=19$m=8192,t=1,p=1$!!$!!",
		"$argon2id$v=19$m=4294967295,t=1,p=1$c2FsdHNhbHQ$aGFzaA",
		"$argon2id$v=19$m=8192,t=1000,p=1$c2FsdHNhbHQ$aGFzaA",
		"$argon2id$v=19$m=8192,t=1,p=0$c2FsdHNhbHQ$aGFzaA",
		"$argon2i$v=19$m=8192,t=1,p=1$c2FsdHNhbHQ$aGFzaA",
		"$argon2id$v=18$m=8192,t=1,p=1$c2FsdHNhbHQ$aGFzaA",
	} {
		if ok, _ := h.Check(ctx, "nope", enc); ok {
			t.Fatalf("accepted %q", enc)
		}
	}
}

func TestHashCancelledContextSkipsHashing(t *testing.T) {
	// Huge memory cost: if hashing ran, the call would take far longer.
	h := NewHasher(ArgonParams{MemoryKiB: 1 << 20, Time: 10, Threads: 1, MaxConcurrency: 1, MaxQueue: 4})
	c, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	if _, err := h.Hash(c, "pw"); !errors.Is(err, context.Canceled) {
		t.Fatalf("Hash err = %v", err)
	}
	if ok, err := h.Check(c, "pw", "$argon2id$v=19$m=1048576,t=10,p=1$c2FsdHNhbHQ$aGFzaGhhc2hoYXNo"); ok || !errors.Is(err, context.Canceled) {
		t.Fatalf("Check = %v, %v", ok, err)
	}
	if d := time.Since(start); d > 100*time.Millisecond {
		t.Fatalf("took %s, hashing was not skipped", d)
	}
}

func TestWaiterGivesUpOnCancel(t *testing.T) {
	h := NewHasher(ArgonParams{MemoryKiB: 8, Time: 1, MaxConcurrency: 1, MaxQueue: 4})
	h.sem <- struct{}{} // all slots busy
	defer func() { <-h.sem }()
	c, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	start := time.Now()
	if _, err := h.Hash(c, "pw"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v", err)
	}
	if d := time.Since(start); d > time.Second {
		t.Fatalf("waited %s", d)
	}
	if n := h.waiting.Load(); n != 0 {
		t.Fatalf("waiting = %d after cancel", n)
	}
}

func TestQueueOverflowIsBusy(t *testing.T) {
	h := NewHasher(ArgonParams{MemoryKiB: 8, Time: 1, MaxConcurrency: 1, MaxQueue: 1})
	h.sem <- struct{}{} // occupy the only slot
	done := make(chan error, 1)
	go func() { _, err := h.Hash(context.Background(), "pw"); done <- err }()
	for deadline := time.Now().Add(2 * time.Second); h.waiting.Load() != 1; {
		if time.Now().After(deadline) {
			t.Fatal("waiter never queued")
		}
		time.Sleep(time.Millisecond)
	}
	if _, err := h.Hash(context.Background(), "pw"); !errors.Is(err, ErrBusy) {
		t.Fatalf("Hash err = %v, want ErrBusy", err)
	}
	if _, err := h.Check(context.Background(), "pw", "$argon2id$v=19$m=8,t=1,p=1$c2FsdHNhbHQ$aGFzaA"); !errors.Is(err, ErrBusy) {
		t.Fatalf("Check err = %v, want ErrBusy", err)
	}
	<-h.sem // free the slot: the queued waiter proceeds
	if err := <-done; err != nil {
		t.Fatalf("queued waiter: %v", err)
	}
}

func TestNoQueueFailsFastWhenSaturated(t *testing.T) {
	h := NewHasher(ArgonParams{MemoryKiB: 8, Time: 1, MaxConcurrency: 1, MaxQueue: 0})
	h.sem <- struct{}{}
	defer func() { <-h.sem }()
	if _, err := h.Hash(context.Background(), "pw"); !errors.Is(err, ErrBusy) {
		t.Fatalf("err = %v", err)
	}
}
