package messages_test

import (
	"testing"
	"time"

	"github.com/google/uuid"
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
