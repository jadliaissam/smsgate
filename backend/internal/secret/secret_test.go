package secret

import (
	"strings"
	"testing"
)

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
	enc, err := h.Hash("correct horse")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(enc, "$argon2id$v=19$m=19456,t=2,p=1$") {
		t.Fatalf("bad prefix %q", enc)
	}
	if !h.Check("correct horse", enc) {
		t.Fatal("correct password rejected")
	}
	if h.Check("wrong", enc) {
		t.Fatal("wrong password accepted")
	}
}

func TestCheckWithOldParams(t *testing.T) {
	old := NewHasher(ArgonParams{MemoryKiB: 8192, Time: 1})
	enc, err := old.Hash("pw")
	if err != nil {
		t.Fatal(err)
	}
	if !NewHasher(defaults).Check("pw", enc) {
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
		if h.Check("nope", enc) {
			t.Fatalf("accepted %q", enc)
		}
	}
}
