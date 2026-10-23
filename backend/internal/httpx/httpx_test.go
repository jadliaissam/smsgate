package httpx_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/smsgate/backend/internal/httpx"
)

type body struct {
	A string `json:"a"`
}

func decodeErr(t *testing.T, payload string, max int64) *httpx.Error {
	t.Helper()
	r := httptest.NewRequest("POST", "/", strings.NewReader(payload))
	r.Header.Set("Content-Type", "application/json")
	var dst body
	err := httpx.Decode(httptest.NewRecorder(), r, &dst, max)
	var he *httpx.Error
	if !errors.As(err, &he) {
		t.Fatalf("want *Error, got %v", err)
	}
	return he
}

func TestDecodeOK(t *testing.T) {
	for _, ct := range []string{"application/json", "application/json; charset=utf-8", "Application/JSON;charset=UTF-8", " application/json "} {
		r := httptest.NewRequest("POST", "/", strings.NewReader(`{"a":"x"}`))
		r.Header.Set("Content-Type", ct)
		var dst body
		if err := httpx.Decode(httptest.NewRecorder(), r, &dst, 100); err != nil || dst.A != "x" {
			t.Fatalf("%q: %v %+v", ct, err, dst)
		}
	}
}

// A cross-site form or fetch without preflight can only send "simple" content
// types; requiring application/json makes cookie-authenticated POSTs CSRF-safe.
func TestDecodeRequiresJSONContentType(t *testing.T) {
	for _, ct := range []string{"", "text/plain", "text/plain; charset=utf-8", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x", "application/jsonp", "application/json-seq"} {
		r := httptest.NewRequest("POST", "/", strings.NewReader(`{"a":"x"}`))
		if ct != "" {
			r.Header.Set("Content-Type", ct)
		}
		var dst body
		err := httpx.Decode(httptest.NewRecorder(), r, &dst, 100)
		var he *httpx.Error
		if !errors.As(err, &he) || he.Status != 415 || he.Code != "unsupported_media_type" || dst.A != "" {
			t.Fatalf("%q: %v %+v", ct, err, dst)
		}
	}
}

func TestDecodeTooLarge(t *testing.T) {
	big := `{"a":"` + strings.Repeat("x", 17*1024) + `"}`
	he := decodeErr(t, big, 16384)
	if he.Status != 413 || he.Code != "body_too_large" {
		t.Fatalf("%+v", he)
	}
}

func TestDecodeUnknownField(t *testing.T) {
	he := decodeErr(t, `{"b":1}`, 100)
	if he.Status != 400 || he.Code != "invalid_json" {
		t.Fatalf("%+v", he)
	}
}

func TestDecodeTrailingAndEmpty(t *testing.T) {
	for _, p := range []string{`{"a":"x"}{"a":"y"}`, `{"a":"x"} junk`, ``, `{`} {
		if he := decodeErr(t, p, 100); he.Code != "invalid_json" {
			t.Fatalf("%q: %+v", p, he)
		}
	}
}

func TestHandleMapsError(t *testing.T) {
	h := httpx.Handle(func(w http.ResponseWriter, r *http.Request) error { return httpx.NotFound("device_not_found") })
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 404 || !strings.Contains(rec.Body.String(), `"error":{"code":"device_not_found"`) {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		t.Fatal(ct)
	}
}

func TestHandleHidesInternalError(t *testing.T) {
	var logs bytes.Buffer
	log := slog.New(slog.NewTextHandler(&logs, nil))
	h := httpx.RequestID(httpx.Recover(log)(httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
		return errors.New("secret db detail")
	})))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "secret") || !strings.Contains(rec.Body.String(), `"internal"`) {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	id := rec.Header().Get("X-Request-Id")
	if id == "" || !strings.Contains(logs.String(), "secret db detail") || !strings.Contains(logs.String(), id) {
		t.Fatalf("log missing detail/request id: %s", logs.String())
	}
}

func TestHandlePanicRecovered(t *testing.T) {
	var logs bytes.Buffer
	log := slog.New(slog.NewTextHandler(&logs, nil))
	h := httpx.RequestID(httpx.Recover(log)(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { panic("kaboom") })))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
	if rec.Code != 500 || strings.Contains(rec.Body.String(), "kaboom") || strings.Contains(rec.Body.String(), "goroutine") {
		t.Fatalf("%d %s", rec.Code, rec.Body)
	}
	// The stack trace is logged (never sent) and points at the panicking frame.
	if l := logs.String(); !strings.Contains(l, "kaboom") || !strings.Contains(l, "stack=") || !strings.Contains(l, "TestHandlePanicRecovered") {
		t.Fatalf("log lacks stack: %s", l)
	}
}

func TestRequestID(t *testing.T) {
	h := httpx.RequestID(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", "/", nil))
	if len(rec.Header().Get("X-Request-Id")) < 16 {
		t.Fatal("not generated")
	}
	req := httptest.NewRequest("GET", "/", nil)
	req.Header.Set("X-Request-Id", "abc-123")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Header().Get("X-Request-Id") != "abc-123" {
		t.Fatal("not propagated")
	}
	req.Header.Set("X-Request-Id", "bad\nid")
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Header().Get("X-Request-Id") == "bad\nid" {
		t.Fatal("unsafe id accepted")
	}
}

func TestAccessLogUsesPattern(t *testing.T) {
	var logs bytes.Buffer
	log := slog.New(slog.NewTextHandler(&logs, nil))
	mux := http.NewServeMux()
	mux.Handle("GET /items/{id}", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(201) }))
	h := httpx.AccessLog(log)(mux)
	h.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("GET", "/items/42", nil))
	out := logs.String()
	if !strings.Contains(out, "GET /items/{id}") || strings.Contains(out, "/items/42") || !strings.Contains(out, "status=201") {
		t.Fatal(out)
	}
}

func TestHandleCancelledRequestNotLoggedAsError(t *testing.T) {
	var logs bytes.Buffer
	log := slog.New(slog.NewTextHandler(&logs, nil)) // info level: debug dropped
	h := httpx.Recover(log)(httpx.Handle(func(w http.ResponseWriter, r *http.Request) error {
		return r.Context().Err()
	}))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("POST", "/", nil).WithContext(ctx))
	if rec.Code != 503 || logs.Len() != 0 {
		t.Fatalf("%d %q", rec.Code, logs.String())
	}
}
