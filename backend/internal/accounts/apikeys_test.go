package accounts_test

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/db/dbq"
)

func echoUser(a *apptest.App, interval time.Duration) http.Handler {
	return accounts.RequireAPIKey(dbq.New(a.Pool), interval, a.Now)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(accounts.UserID(r.Context()).String()))
	}))
}

func callKey(h http.Handler, authz string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("GET", "/", nil)
	if authz != "" {
		req.Header.Set("Authorization", authz)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func lastUsed(t *testing.T, p *pgxpool.Pool, id string) *time.Time {
	t.Helper()
	var ts *time.Time
	if err := p.QueryRow(t.Context(), `SELECT last_used_at FROM api_keys WHERE id=$1`, id).Scan(&ts); err != nil {
		t.Fatal(err)
	}
	return ts
}

func TestCreateAndUseAPIKey(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "k@example.com")
	resp, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "prod"}, cookieHdr(c)...)
	if resp.StatusCode != 201 {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
	key, _ := m["key"].(string)
	prefix, _ := m["prefix"].(string)
	if !strings.HasPrefix(key, "sk_live_") || len(prefix) != 12 || !strings.HasPrefix(key, prefix) || m["name"] != "prod" || m["id"] == "" || m["created_at"] == nil {
		t.Fatalf("%v", m)
	}
	_, me := a.Do("GET", "/app/me", nil, cookieHdr(c)...)
	h := echoUser(a, time.Minute)
	for _, authz := range []string{"Bearer " + key, "bearer " + key, "BEARER " + key} {
		rec := callKey(h, authz)
		if rec.Code != 200 || rec.Body.String() != me["id"] {
			t.Fatalf("%q: %d %s", authz, rec.Code, rec.Body)
		}
	}
	for _, authz := range []string{"", "Bearer ", "Bearer sk_live_nope", "Bearer " + strings.Replace(key, "sk_live_", "sk_test_", 1), "Basic " + key, key, "Bearer"} {
		rec := callKey(h, authz)
		if rec.Code != 401 || !strings.Contains(rec.Body.String(), `"code":"unauthorized"`) {
			t.Fatalf("%q: %d %s", authz, rec.Code, rec.Body)
		}
	}
}

func TestRevokedKeyRejected(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "r@example.com")
	_, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "x"}, cookieHdr(c)...)
	key := m["key"].(string)
	h := echoUser(a, time.Minute)
	if rec := callKey(h, "Bearer "+key); rec.Code != 200 {
		t.Fatal(rec.Code)
	}
	if resp, _ := a.Do("DELETE", "/app/api-keys/"+m["id"].(string), nil, cookieHdr(c)...); resp.StatusCode != 204 {
		t.Fatal(resp.StatusCode)
	}
	if rec := callKey(h, "Bearer "+key); rec.Code != 401 {
		t.Fatalf("revoked key: %d", rec.Code)
	}
}

func TestUnverifiedCannotCreateKey(t *testing.T) {
	a := apptest.New(t)
	a.Do("POST", "/app/signup", map[string]string{"email": "u@example.com", "password": apptest.Password})
	resp, _ := a.Do("POST", "/app/login", map[string]string{"email": "u@example.com", "password": apptest.Password})
	c := resp.Cookies()[0]
	resp, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "x"}, cookieHdr(c)...)
	if resp.StatusCode != 403 || !strings.Contains(m["error"].(map[string]any)["code"].(string), "email_not_verified") {
		t.Fatalf("%d %v", resp.StatusCode, m)
	}
}

func TestAPIKeysRequireSession(t *testing.T) {
	a := apptest.New(t)
	for _, r := range [][2]string{{"POST", "/app/api-keys"}, {"GET", "/app/api-keys"}, {"DELETE", "/app/api-keys/x"}} {
		if resp, _ := a.Do(r[0], r[1], nil); resp.StatusCode != 401 {
			t.Fatalf("%v: %d", r, resp.StatusCode)
		}
	}
}

func TestCreateKeyNameValidation(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "n@example.com")
	for _, name := range []string{"", "   ", strings.Repeat("x", 65)} {
		resp, m := a.Do("POST", "/app/api-keys", map[string]string{"name": name}, cookieHdr(c)...)
		if resp.StatusCode != 422 || m["error"].(map[string]any)["code"] != "invalid_name" {
			t.Fatalf("%q: %d %v", name, resp.StatusCode, m)
		}
	}
	if resp, _ := a.Do("POST", "/app/api-keys", map[string]string{"name": strings.Repeat("é", 64)}, cookieHdr(c)...); resp.StatusCode != 201 {
		t.Fatalf("64 runes: %d", resp.StatusCode)
	}
}

func TestCannotRevokeOthersKey(t *testing.T) {
	a := apptest.New(t)
	c1 := a.Signup(t, "a@example.com")
	c2 := a.Signup(t, "b@example.com")
	_, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "x"}, cookieHdr(c1)...)
	id := m["id"].(string)
	if resp, _ := a.Do("DELETE", "/app/api-keys/"+id, nil, cookieHdr(c2)...); resp.StatusCode != 404 {
		t.Fatalf("%d", resp.StatusCode)
	}
	if resp, _ := a.Do("DELETE", "/app/api-keys/not-a-uuid", nil, cookieHdr(c2)...); resp.StatusCode != 404 {
		t.Fatalf("bad id: %d", resp.StatusCode)
	}
	if rec := callKey(echoUser(a, time.Minute), "Bearer "+m["key"].(string)); rec.Code != 200 {
		t.Fatal("key must still work")
	}
	// Revoking twice: second is 404.
	a.Do("DELETE", "/app/api-keys/"+id, nil, cookieHdr(c1)...)
	if resp, _ := a.Do("DELETE", "/app/api-keys/"+id, nil, cookieHdr(c1)...); resp.StatusCode != 404 {
		t.Fatalf("double revoke: %d", resp.StatusCode)
	}
}

func TestListNeverReturnsKey(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "l@example.com")
	c2 := a.Signup(t, "other@example.com")
	a.Do("POST", "/app/api-keys", map[string]string{"name": "one"}, cookieHdr(c)...)
	_, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "two"}, cookieHdr(c)...)
	a.Do("POST", "/app/api-keys", map[string]string{"name": "theirs"}, cookieHdr(c2)...)
	a.Do("DELETE", "/app/api-keys/"+m["id"].(string), nil, cookieHdr(c)...)

	req, _ := http.NewRequest("GET", a.URL+"/app/api-keys", nil)
	req.AddCookie(c)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var buf strings.Builder
	b := make([]byte, 4096)
	for {
		n, err := resp.Body.Read(b)
		buf.Write(b[:n])
		if err != nil {
			break
		}
	}
	body := buf.String()
	if resp.StatusCode != 200 || strings.Contains(body, `"key":`) || strings.Contains(body, "theirs") || strings.Contains(body, "key_hash") {
		t.Fatalf("%d %s", resp.StatusCode, body)
	}
	for _, want := range []string{`"name":"one"`, `"name":"two"`, `"prefix":"sk_live_`, `"revoked_at":"`, `"last_used_at":null`, `"created_at"`} {
		if !strings.Contains(body, want) {
			t.Fatalf("missing %s in %s", want, body)
		}
	}
	if strings.Index(body, `"two"`) > strings.Index(body, `"one"`) {
		t.Fatalf("want newest first: %s", body)
	}
}

func TestLastUsedThrottled(t *testing.T) {
	a := apptest.New(t)
	c := a.Signup(t, "t@example.com")
	_, m := a.Do("POST", "/app/api-keys", map[string]string{"name": "x"}, cookieHdr(c)...)
	id, auth := m["id"].(string), "Bearer "+m["key"].(string)
	h := echoUser(a, time.Minute)
	if lastUsed(t, a.Pool, id) != nil {
		t.Fatal("last_used_at should start null")
	}
	callKey(h, auth)
	first := lastUsed(t, a.Pool, id)
	if first == nil {
		t.Fatal("first use must set last_used_at")
	}
	a.Advance(30 * time.Second)
	callKey(h, auth)
	if got := lastUsed(t, a.Pool, id); !got.Equal(*first) {
		t.Fatalf("touched within interval: %v -> %v", first, got)
	}
	a.Advance(31 * time.Second)
	callKey(h, auth)
	if got := lastUsed(t, a.Pool, id); !got.After(*first) {
		t.Fatalf("not touched after interval: %v", got)
	}
}

func TestKeyOfDeletedUserRejected(t *testing.T) {
	a := apptest.New(t)
	key := a.APIKey(t, a.Signup(t, "d@example.com"))
	h := echoUser(a, time.Minute)
	if rec := callKey(h, "Bearer "+key); rec.Code != 200 {
		t.Fatal(rec.Code)
	}
	if _, err := a.Pool.Exec(t.Context(), `DELETE FROM users WHERE email='d@example.com'`); err != nil {
		t.Fatal(err)
	}
	if rec := callKey(h, "Bearer "+key); rec.Code != 401 {
		t.Fatal(rec.Code)
	}
}
