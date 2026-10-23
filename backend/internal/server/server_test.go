package server_test

import (
	"testing"

	"github.com/smsgate/backend/internal/apptest"
)

func TestHealthz(t *testing.T) {
	a := apptest.New(t)
	resp, body := a.Do("GET", "/healthz", nil)
	if resp.StatusCode != 200 || body["ok"] != true {
		t.Fatalf("%d %v", resp.StatusCode, body)
	}
	if resp.Header.Get("X-Request-Id") == "" {
		t.Fatal("no request id")
	}
}
