package accounts_test

import (
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/config"
)

func cookieHdr(c *http.Cookie) []string { return []string{"Cookie", c.Name + "=" + c.Value} }

func TestSignupVerifyLogin(t *testing.T) {
	a := apptest.New(t)
	resp, m := a.Do("POST", "/app/signup", map[string]string{"email": "  Bob@Example.com ", "password": apptest.Password})
	if resp.StatusCode != 201 || m["email"] != "bob@example.com" || m["id"] == "" {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
	c := a.Signup(t, "alice@example.com")
	if !c.HttpOnly || c.SameSite != http.SameSiteLaxMode || c.Path != "/" || c.MaxAge != int(a.Cfg.SessionTTL.Seconds()) {
		t.Fatalf("cookie %+v", c)
	}
	resp, m = a.Do("GET", "/app/me", nil, cookieHdr(c)...)
	if resp.StatusCode != 200 || m["email_verified"] != true || m["plan"] != "free" || m["email"] != "alice@example.com" {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
}

func TestUnverifiedMe(t *testing.T) {
	a := apptest.New(t)
	a.Do("POST", "/app/signup", map[string]string{"email": "u@example.com", "password": apptest.Password})
	resp, _ := a.Do("POST", "/app/login", map[string]string{"email": "u@example.com", "password": apptest.Password})
	var c *http.Cookie
	for _, k := range resp.Cookies() {
		c = k
	}
	_, m := a.Do("GET", "/app/me", nil, cookieHdr(c)...)
	if m["email_verified"] != false {
		t.Fatal(m)
	}
}

func TestSignupValidation(t *testing.T) {
	a := apptest.New(t)
	cases := []struct {
		email, pw, code string
		status          int
	}{
		{"a@example.com", "short", "weak_password", 422},
		{"a@example.com", strings.Repeat("x", 129), "weak_password", 422},
		{"nope", apptest.Password, "invalid_email", 422},
		{"a@@b.com", apptest.Password, "invalid_email", 422},
		{"@b.com", apptest.Password, "invalid_email", 422},
		{"a@", apptest.Password, "invalid_email", 422},
	}
	for _, c := range cases {
		resp, m := a.Do("POST", "/app/signup", map[string]string{"email": c.email, "password": c.pw})
		if resp.StatusCode != c.status || !strings.Contains(strings.TrimSpace(toStr(m)), c.code) {
			t.Fatalf("%+v -> %d %v", c, resp.StatusCode, m)
		}
	}
}

func toStr(m map[string]any) string {
	e, _ := m["error"].(map[string]any)
	s, _ := e["code"].(string)
	return s
}

func TestSignupDuplicate(t *testing.T) {
	a := apptest.New(t)
	b := map[string]string{"email": "dup@example.com", "password": apptest.Password}
	a.Do("POST", "/app/signup", b)
	b["email"] = "DUP@example.com"
	resp, m := a.Do("POST", "/app/signup", b)
	if resp.StatusCode != 409 || toStr(m) != "email_taken" {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
}

func TestVerifyInvalidAndReuse(t *testing.T) {
	a := apptest.New(t)
	resp, m := a.Do("POST", "/app/verify", map[string]string{"token": "bogus"})
	if resp.StatusCode != 400 || toStr(m) != "invalid_token" {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
	a.Do("POST", "/app/signup", map[string]string{"email": "v@example.com", "password": apptest.Password})
	tok := a.Token("v@example.com")
	if resp, _ := a.Do("POST", "/app/verify", map[string]string{"token": tok}); resp.StatusCode != 204 {
		t.Fatal(resp.StatusCode)
	}
	if resp, _ := a.Do("POST", "/app/verify", map[string]string{"token": tok}); resp.StatusCode != 400 {
		t.Fatal("token reusable")
	}
}

func TestVerifyExpired(t *testing.T) {
	a := apptest.New(t)
	a.Do("POST", "/app/signup", map[string]string{"email": "e@example.com", "password": apptest.Password})
	tok := a.Token("e@example.com")
	a.Advance(25 * time.Hour)
	resp, m := a.Do("POST", "/app/verify", map[string]string{"token": tok})
	if resp.StatusCode != 400 || toStr(m) != "invalid_token" {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
}

func TestLoginWrongPassword(t *testing.T) {
	a := apptest.New(t)
	a.Signup(t, "l@example.com")
	for _, e := range []string{"l@example.com", "ghost@example.com"} {
		resp, m := a.Do("POST", "/app/login", map[string]string{"email": e, "password": "wrong-password-1"})
		if resp.StatusCode != 401 || toStr(m) != "invalid_credentials" || len(resp.Cookies()) != 0 {
			t.Fatalf("%s: %d %v", e, resp.StatusCode, m)
		}
	}
}

func TestMeWithoutSession(t *testing.T) {
	a := apptest.New(t)
	if resp, _ := a.Do("GET", "/app/me", nil); resp.StatusCode != 401 {
		t.Fatal(resp.StatusCode)
	}
	if resp, _ := a.Do("GET", "/app/me", nil, "Cookie", "sg_session=garbage"); resp.StatusCode != 401 {
		t.Fatal(resp.StatusCode)
	}
}

func TestLogoutInvalidatesSession(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "o@example.com")
	resp, _ := a.Do("POST", "/app/logout", nil, cookieHdr(c)...)
	if resp.StatusCode != 204 {
		t.Fatal(resp.StatusCode)
	}
	if cs := resp.Cookies(); len(cs) != 1 || cs[0].MaxAge != -1 {
		t.Fatalf("%+v", cs)
	}
	if resp, _ := a.Do("GET", "/app/me", nil, cookieHdr(c)...); resp.StatusCode != 401 {
		t.Fatal(resp.StatusCode)
	}
}

func TestExpiredSession(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "x@example.com")
	a.Advance(31 * 24 * time.Hour)
	if resp, _ := a.Do("GET", "/app/me", nil, cookieHdr(c)...); resp.StatusCode != 401 {
		t.Fatal(resp.StatusCode)
	}
}

// With one hashing slot and no wait queue, concurrent logins beyond the slot
// are rejected with 503 busy instead of queueing goroutines.
func TestLoginBusyWhenHasherSaturated(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.Argon2.MaxConcurrency = 1; c.Argon2.MaxQueue = 0 })
	const n = 8
	codes := make([]int, n)
	errs := make([]any, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			r, m := a.Do("POST", "/app/login", map[string]string{"email": "nobody@example.com", "password": apptest.Password})
			codes[i] = r.StatusCode
			if e, ok := m["error"].(map[string]any); ok {
				errs[i] = e["code"]
			}
		}()
	}
	wg.Wait()
	busy := 0
	for i, c := range codes {
		switch {
		case c == 503 && errs[i] == "busy":
			busy++
		case c == 401:
		default:
			t.Fatalf("request %d: %d %v", i, c, errs[i])
		}
	}
	if busy == 0 {
		t.Fatalf("no 503 busy among %v", codes)
	}
}
