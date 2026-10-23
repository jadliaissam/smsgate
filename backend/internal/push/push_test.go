package push_test

import (
	"errors"
	"testing"
	"time"

	"github.com/smsgate/backend/internal/push"
)

func TestRecorderWaitCalls(t *testing.T) {
	r := &push.Recorder{Err: errors.New("boom")}
	go func() { time.Sleep(10 * time.Millisecond); _ = r.Wake(t.Context(), push.WakeTarget{FCMToken: "a"}) }()
	got := r.WaitCalls(1, time.Second)
	if len(got) != 1 || got[0].FCMToken != "a" {
		t.Fatalf("got %v", got)
	}
	if err := r.Wake(t.Context(), push.WakeTarget{}); err == nil {
		t.Fatal("want Err returned")
	}
	if len(r.Calls()) != 2 {
		t.Fatal("call not recorded when Err set")
	}
	if got := r.WaitCalls(5, 20*time.Millisecond); len(got) != 2 {
		t.Fatalf("timeout got %d", len(got))
	}
}
