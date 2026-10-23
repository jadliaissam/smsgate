package push

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/oauth2"

	"github.com/smsgate/backend/internal/config"
)

func testFCM(t *testing.T, h http.HandlerFunc) *FCM {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	cfg := config.Config{FCMEndpoint: srv.URL + "/", WakeTimeout: 2 * time.Second, FCMMaxIdlePerHost: 4}
	return newFCMWithTokenSource("proj-1", cfg, NewHTTPClient(cfg), oauth2.StaticTokenSource(&oauth2.Token{AccessToken: "test-token"}))
}

func TestFCMPayload(t *testing.T) {
	var path, auth, ctype string
	var raw []byte
	f := testFCM(t, func(w http.ResponseWriter, r *http.Request) {
		path, auth, ctype = r.URL.Path, r.Header.Get("Authorization"), r.Header.Get("Content-Type")
		raw, _ = io.ReadAll(r.Body)
		w.Write([]byte(`{"name":"x"}`))
	})
	mid := uuid.New()
	err := f.Wake(context.Background(), WakeTarget{DeviceID: uuid.New(), FCMToken: "tok", MessageID: mid, ExpiresAt: time.Now().Add(90*time.Second + 500*time.Millisecond)})
	if err != nil {
		t.Fatal(err)
	}
	if path != "/v1/projects/proj-1/messages:send" {
		t.Fatalf("path %q", path)
	}
	if auth != "Bearer test-token" || ctype != "application/json" {
		t.Fatalf("headers %q %q", auth, ctype)
	}
	var p struct {
		Message struct {
			Token   string
			Data    map[string]string
			Android struct{ Priority, TTL string }
		}
	}
	if err := json.Unmarshal(raw, &p); err != nil {
		t.Fatal(err)
	}
	m := p.Message
	if m.Token != "tok" || m.Android.Priority != "HIGH" {
		t.Fatalf("payload %s", raw)
	}
	if m.Android.TTL != "90s" && m.Android.TTL != "89s" {
		t.Fatalf("ttl %q", m.Android.TTL)
	}
	if len(m.Data) != 2 || m.Data["type"] != "new" || m.Data["message_id"] != mid.String() {
		t.Fatalf("data %v", m.Data)
	}
	s := string(raw)
	if strings.Contains(s, "body") || strings.Contains(s, `"to"`) {
		t.Fatalf("forbidden field in %s", s)
	}
}

func TestFCMExpiredTTLIsZero(t *testing.T) {
	var raw []byte
	f := testFCM(t, func(w http.ResponseWriter, r *http.Request) { raw, _ = io.ReadAll(r.Body) })
	if err := f.Wake(context.Background(), WakeTarget{FCMToken: "t", ExpiresAt: time.Now().Add(-time.Hour)}); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(raw), `"ttl":"0s"`) {
		t.Fatalf("%s", raw)
	}
}

func TestFCMUnregistered(t *testing.T) {
	cases := map[string]struct {
		status int
		body   string
		want   bool
	}{
		"404":          {404, `{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}`, true},
		"404 empty":    {404, ``, true},
		"400 unreg":    {400, `{"error":{"status":"INVALID_ARGUMENT","details":[{"errorCode":"UNREGISTERED"}]}}`, true},
		"400 badtoken": {400, `{"error":{"status":"INVALID_ARGUMENT","message":"The registration token is not a valid FCM registration token"}}`, true},
		"400 other":    {400, `{"error":{"status":"INVALID_ARGUMENT","message":"Invalid JSON payload"}}`, false},
		"500":          {500, `oops`, false},
		"403":          {403, `{"error":{"status":"PERMISSION_DENIED"}}`, false},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			f := testFCM(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(c.status)
				w.Write([]byte(c.body))
			})
			err := f.Wake(context.Background(), WakeTarget{FCMToken: "t"})
			if err == nil {
				t.Fatal("want error")
			}
			if errors.Is(err, ErrTokenInvalid) != c.want {
				t.Fatalf("Is(ErrTokenInvalid)=%v err=%v", !c.want, err)
			}
			if !c.want && !strings.Contains(err.Error(), http.StatusText(c.status)) && !strings.Contains(err.Error(), "status") {
				t.Fatalf("error lacks status: %v", err)
			}
		})
	}
}

func TestNoop(t *testing.T) {
	if err := (Noop{}).Wake(context.Background(), WakeTarget{}); err != nil {
		t.Fatal(err)
	}
}

func TestNewFCMCredentialsErrors(t *testing.T) {
	cfg := config.Config{WakeTimeout: time.Second}
	cfg.FCMCredentialsFile = filepath.Join(t.TempDir(), "missing.json")
	if _, err := NewFCM(context.Background(), cfg, NewHTTPClient(cfg)); err == nil || !strings.Contains(err.Error(), "FCM_CREDENTIALS_FILE") {
		t.Fatalf("missing: %v", err)
	}
	bad := filepath.Join(t.TempDir(), "bad.json")
	os.WriteFile(bad, []byte(`{"nope":1}`), 0o600)
	cfg.FCMCredentialsFile = bad
	if _, err := NewFCM(context.Background(), cfg, NewHTTPClient(cfg)); err == nil || !strings.Contains(err.Error(), "FCM_CREDENTIALS_FILE") {
		t.Fatalf("invalid: %v", err)
	}
}

func TestNewHTTPClient(t *testing.T) {
	hc := NewHTTPClient(config.Config{WakeTimeout: 3 * time.Second, FCMMaxIdlePerHost: 7})
	tr := hc.Transport.(*http.Transport)
	if hc.Timeout != 3*time.Second || tr.MaxIdleConnsPerHost != 7 || !tr.ForceAttemptHTTP2 {
		t.Fatalf("%+v %+v", hc, tr)
	}
}
