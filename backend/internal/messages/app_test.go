package messages_test

import (
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/apptest"
)

func (f *fixture) appDo(method, path string, body any, extra ...string) (int, map[string]any) {
	r, m := f.app.Do(method, path, body, append([]string{"Cookie", f.cookie.Name + "=" + f.cookie.Value}, extra...)...)
	return r.StatusCode, m
}

func TestAppMessagesListAndGet(t *testing.T) {
	f := setup(t, "app1@example.com")
	st, created := f.create(f.valid())
	if st != 202 {
		t.Fatalf("create %d %v", st, created)
	}
	id := created["id"].(string)

	st, m := f.appDo("GET", "/app/messages", nil)
	data, _ := m["data"].([]any)
	if st != 200 || len(data) != 1 || data[0].(map[string]any)["id"] != id {
		t.Fatalf("list %d %v", st, m)
	}
	st, m = f.appDo("GET", "/app/messages/"+id, nil)
	if st != 200 || m["id"] != id || m["status"] != "queued" {
		t.Fatalf("get %d %v", st, m)
	}

	// Another user's cookie cannot see it.
	other := f.app.Signup(t, "app2@example.com")
	r, m := f.app.Do("GET", "/app/messages/"+id, nil, "Cookie", other.Name+"="+other.Value)
	if r.StatusCode != 404 || errCode(m) != "message_not_found" {
		t.Fatalf("other get %d %v", r.StatusCode, m)
	}
	r, m = f.app.Do("GET", "/app/messages", nil, "Cookie", other.Name+"="+other.Value)
	if d, _ := m["data"].([]any); r.StatusCode != 200 || len(d) != 0 {
		t.Fatalf("other list %d %v", r.StatusCode, m)
	}
	st, m = f.appDo("GET", "/app/messages/"+uuid.NewString(), nil)
	if st != 404 {
		t.Fatalf("unknown id %d %v", st, m)
	}
}

func TestAppSendMessage(t *testing.T) {
	f := setup(t, "app3@example.com")
	st, m := f.appDo("POST", "/app/messages", f.valid(), "Idempotency-Key", "k1")
	if st != 202 || m["status"] != "queued" {
		t.Fatalf("send %d %v", st, m)
	}
	calls := f.app.Waker.WaitCalls(1, 2*time.Second)
	if len(calls) != 1 || calls[0].MessageID.String() != m["id"] {
		t.Fatalf("wake calls %+v", calls)
	}
	st, m2 := f.appDo("POST", "/app/messages", f.valid(), "Idempotency-Key", "k1")
	if st != 200 || m2["id"] != m["id"] {
		t.Fatalf("idempotent replay %d %v", st, m2)
	}
}

func TestAppMessagesRequireSession(t *testing.T) {
	f := setup(t, "app4@example.com")
	for _, c := range []struct{ method, path string }{
		{"GET", "/app/messages"},
		{"GET", "/app/messages/" + uuid.NewString()},
		{"POST", "/app/messages"},
	} {
		var body any
		if c.method == "POST" {
			body = f.valid()
		}
		r, m := f.app.Do(c.method, c.path, body)
		if r.StatusCode != 401 {
			t.Fatalf("%s %s: %d %v", c.method, c.path, r.StatusCode, m)
		}
		// An API key is not a session.
		r, _ = f.app.Do(c.method, c.path, body, f.hdr...)
		if r.StatusCode != 401 {
			t.Fatalf("%s %s with api key: %d", c.method, c.path, r.StatusCode)
		}
	}
}

func TestAppSendRequiresVerifiedEmail(t *testing.T) {
	app := apptest.New(t)
	const email = "unverified@example.com"
	if r, m := app.Do("POST", "/app/signup", map[string]string{"email": email, "password": apptest.Password}); r.StatusCode != 201 {
		t.Fatalf("signup %d %v", r.StatusCode, m)
	}
	r, m := app.Do("POST", "/app/login", map[string]string{"email": email, "password": apptest.Password})
	if r.StatusCode != 204 {
		t.Fatalf("login %d %v", r.StatusCode, m)
	}
	var cookie *http.Cookie
	for _, c := range r.Cookies() {
		if c.Name == app.Cfg.SessionCookie {
			cookie = c
		}
	}
	if cookie == nil {
		t.Fatal("no session cookie")
	}
	ck := []string{"Cookie", cookie.Name + "=" + cookie.Value}
	// A random device id would be 404 device_not_found; 403 proves the
	// verification check runs before the request is looked at.
	body := map[string]any{"device_id": uuid.NewString(), "to": "+37060000000", "body": "hello"}
	r, m = app.Do("POST", "/app/messages", body, ck...)
	if r.StatusCode != 403 || errCode(m) != "email_not_verified" {
		t.Fatalf("unverified send %d %v", r.StatusCode, m)
	}
	// Reading stays allowed.
	r, m = app.Do("GET", "/app/messages", nil, ck...)
	if d, ok := m["data"].([]any); r.StatusCode != 200 || !ok || len(d) != 0 {
		t.Fatalf("unverified list %d %v", r.StatusCode, m)
	}

	if r, m := app.Do("POST", "/app/verify", map[string]string{"token": app.Token(email)}); r.StatusCode != 204 {
		t.Fatalf("verify %d %v", r.StatusCode, m)
	}
	dev, _ := app.PairDevice(t, cookie)
	body["device_id"] = dev.String()
	r, m = app.Do("POST", "/app/messages", body, ck...)
	if r.StatusCode != 202 || m["status"] != "queued" {
		t.Fatalf("verified send %d %v", r.StatusCode, m)
	}
}
