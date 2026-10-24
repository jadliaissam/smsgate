package accounts_test

import (
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/accounts"
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

// Cookie-authenticated endpoints only accept JSON bodies (CSRF: a cross-site
// form post or simple fetch cannot send application/json).
func TestNonJSONContentTypeRejected(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "csrf@example.com")
	for _, ct := range []string{"text/plain", "application/x-www-form-urlencoded"} {
		req, _ := http.NewRequest("POST", a.URL+"/app/api-keys", strings.NewReader(`{"name":"x"}`))
		req.Header.Set("Content-Type", ct)
		req.AddCookie(c)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != 415 {
			t.Fatalf("%s: %d", ct, resp.StatusCode)
		}
	}
}

func TestResendVerification(t *testing.T) {
	a := apptest.New(t)
	email := "resend@example.com"
	a.Do("POST", "/app/signup", map[string]string{"email": email, "password": apptest.Password})
	resp, _ := a.Do("POST", "/app/login", map[string]string{"email": email, "password": apptest.Password})
	c := resp.Cookies()[0]
	first := a.Mailer.Last(email)

	// Within the interval: 204, nothing sent.
	if r, _ := a.Do("POST", "/app/resend-verification", nil, cookieHdr(c)...); r.StatusCode != 204 {
		t.Fatalf("early resend %d", r.StatusCode)
	}
	if a.Mailer.Last(email) != first {
		t.Fatal("email sent inside the resend interval")
	}

	a.Advance(a.Cfg.ResendInterval + time.Second)
	if r, _ := a.Do("POST", "/app/resend-verification", nil, cookieHdr(c)...); r.StatusCode != 204 {
		t.Fatalf("resend %d", r.StatusCode)
	}
	second := a.Mailer.Last(email)
	if second == first {
		t.Fatal("no new email after the interval")
	}
	// Old token is gone; new one verifies.
	if r, _ := a.Do("POST", "/app/verify", map[string]string{"token": accounts.TokenFrom(first)}); r.StatusCode != 400 {
		t.Fatalf("old token verify %d", r.StatusCode)
	}
	// Second call right after: still 204, nothing sent.
	if r, _ := a.Do("POST", "/app/resend-verification", nil, cookieHdr(c)...); r.StatusCode != 204 {
		t.Fatalf("repeat %d", r.StatusCode)
	}
	if a.Mailer.Last(email) != second {
		t.Fatal("email sent twice within the interval")
	}
	if r, m := a.Do("POST", "/app/verify", map[string]string{"token": accounts.TokenFrom(second)}); r.StatusCode != 204 {
		t.Fatalf("new token verify %d %v", r.StatusCode, m)
	}

	// Verified user: 204, nothing sent.
	a.Advance(a.Cfg.ResendInterval + time.Second)
	if r, _ := a.Do("POST", "/app/resend-verification", nil, cookieHdr(c)...); r.StatusCode != 204 {
		t.Fatalf("verified resend %d", r.StatusCode)
	}
	if a.Mailer.Last(email) != second {
		t.Fatal("email sent to verified user")
	}
}

func TestResendVerificationRequiresSession(t *testing.T) {
	a := apptest.New(t)
	if r, _ := a.Do("POST", "/app/resend-verification", nil); r.StatusCode != 401 {
		t.Fatalf("status %d", r.StatusCode)
	}
}

func TestResendVerificationRateLimitedPerIP(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLAuthPerMin = 2 })
	hit := func() int { r, _ := a.Do("POST", "/app/resend-verification", nil); return r.StatusCode }
	// Rate limiter runs before auth, so unauthenticated hits still count.
	if hit() != 401 || hit() != 401 || hit() != 429 {
		t.Fatal("expected 401, 401, 429")
	}
}

func TestSessionRoutesRequireJSONContentType(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "ct@example.com")
	ck := cookieHdr(c)
	// raw sends a request with an explicit Content-Type ("" = none), no body.
	raw := func(method, path, ct string) int {
		req, _ := http.NewRequest(method, a.URL+path, nil)
		req.AddCookie(c)
		if ct != "" {
			req.Header.Set("Content-Type", ct)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if got := raw("POST", "/app/logout", ""); got != 415 {
		t.Fatalf("logout without content-type: %d", got)
	}
	if got := raw("POST", "/app/resend-verification", ""); got != 415 {
		t.Fatalf("resend without content-type: %d", got)
	}
	_, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "k"}, ck...)
	id, _ := m["id"].(string)
	if got := raw("DELETE", "/app/api-keys/"+id, "text/plain"); got != 415 {
		t.Fatalf("delete with text/plain: %d", got)
	}
	if got := raw("GET", "/app/me", ""); got != 200 {
		t.Fatalf("GET needs no content-type: %d", got)
	}
	if got := raw("POST", "/app/logout", "application/json"); got != 204 {
		t.Fatalf("logout with json and empty body: %d", got)
	}
}
