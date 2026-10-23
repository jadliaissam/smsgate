package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestHealthcheck(t *testing.T) {
	ok := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(200) }))
	defer ok.Close()
	if err := healthcheck(ok.URL, time.Second); err != nil {
		t.Fatal(err)
	}
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(500) }))
	defer bad.Close()
	if err := healthcheck(bad.URL, time.Second); err == nil {
		t.Fatal("500 must fail")
	}
	ok.Close()
	if err := healthcheck(ok.URL, time.Second); err == nil {
		t.Fatal("closed server must fail")
	}
}
