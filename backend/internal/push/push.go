// Package push wakes devices (FCM) when a message is waiting.
package push

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/google/uuid"
)

// WakeTarget identifies the device and message to wake for.
type WakeTarget struct {
	DeviceID  uuid.UUID
	FCMToken  string
	MessageID uuid.UUID
	// ExpiresAt is when the message expires; it bounds the push's time-to-live.
	ExpiresAt time.Time
}

// ErrTokenInvalid means the push service rejected the device's FCM token
// (unregistered or malformed); callers should drop it.
var ErrTokenInvalid = errors.New("push: fcm token invalid")

// Noop is a Waker that does nothing (used when FCM is not configured).
type Noop struct{}

// Wake returns nil.
func (Noop) Wake(context.Context, WakeTarget) error { return nil }

// Waker sends a wake-up push to a device.
type Waker interface {
	Wake(ctx context.Context, d WakeTarget) error
}

// Recorder is a test Waker that records calls. Err, if set, is returned by Wake
// (the call is still recorded). Set Err before use; it is not synchronised.
type Recorder struct {
	Err error

	mu    sync.Mutex
	calls []WakeTarget
	ch    chan struct{} // closed and replaced on every call
}

// Wake records the call and returns Err.
func (r *Recorder) Wake(_ context.Context, d WakeTarget) error {
	r.mu.Lock()
	r.calls = append(r.calls, d)
	if r.ch != nil {
		close(r.ch)
		r.ch = nil
	}
	r.mu.Unlock()
	return r.Err
}

// Calls returns a copy of the recorded calls.
func (r *Recorder) Calls() []WakeTarget {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]WakeTarget(nil), r.calls...)
}

// WaitCalls blocks until at least n calls are recorded or timeout elapses,
// then returns the calls recorded so far.
func (r *Recorder) WaitCalls(n int, timeout time.Duration) []WakeTarget {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	for {
		r.mu.Lock()
		if len(r.calls) >= n {
			out := append([]WakeTarget(nil), r.calls...)
			r.mu.Unlock()
			return out
		}
		if r.ch == nil {
			r.ch = make(chan struct{})
		}
		ch := r.ch
		r.mu.Unlock()
		select {
		case <-ch:
		case <-timer.C:
			return r.Calls()
		}
	}
}
