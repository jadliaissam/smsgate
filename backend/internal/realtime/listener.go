package realtime

import (
	"context"
	"errors"
	"log/slog"
	"math/rand/v2"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

// Notification channels (payloads in parentheses). All are sent from SQL in
// the statement that causes them, so they fire exactly when it commits.
const (
	ChanWake   = "smsgate_wake"   // "<device_id>:<message_id>" (message created)
	ChanRevoke = "smsgate_revoke" // "<device_id>" (device revoked)
	ChanWS     = "smsgate_ws"     // "<device_id>:<conn_id>" (socket opened)
)

// Listener bridges Postgres notifications to a Hub over one dedicated
// connection (outside the pool). It reconnects with jittered exponential
// backoff and, after a reconnect, delivers nothing retroactively: devices
// catch up through GET /device/v1/messages/pending when they (re)connect, and
// the FCM fallback covers sockets that missed a wake.
type Listener struct {
	cfg          *pgx.ConnConfig
	hub          *Hub
	log          *slog.Logger
	retryMax     time.Duration
	pingInterval time.Duration

	readyOnce sync.Once
	ready     chan struct{}
}

// NewListener builds a listener; cfg is copied.
func NewListener(cfg *pgx.ConnConfig, hub *Hub, log *slog.Logger, retryMax, pingInterval time.Duration) *Listener {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &Listener{cfg: cfg.Copy(), hub: hub, log: log, retryMax: retryMax, pingInterval: pingInterval, ready: make(chan struct{})}
}

// Ready is closed once the first LISTEN succeeded.
func (l *Listener) Ready() <-chan struct{} { return l.ready }

// Run listens until ctx is done.
func (l *Listener) Run(ctx context.Context) {
	const retryMin = 500 * time.Millisecond
	backoff := min(retryMin, l.retryMax)
	for {
		listened, err := l.session(ctx)
		if ctx.Err() != nil {
			return
		}
		if listened {
			backoff = min(retryMin, l.retryMax)
		}
		wait := backoff/2 + rand.N(backoff/2+1)
		l.log.Warn("notify listener disconnected, reconnecting", "err", err, "retry_in", wait)
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
		backoff = min(backoff*2, l.retryMax)
	}
}

// session runs one connection: LISTEN, then dispatch notifications, pinging
// the server whenever it has been idle for pingInterval so a dead connection
// is noticed. It reports whether LISTEN succeeded.
func (l *Listener) session(ctx context.Context) (bool, error) {
	conn, err := pgx.ConnectConfig(ctx, l.cfg)
	if err != nil {
		return false, err
	}
	defer func() {
		cctx, cancel := context.WithTimeout(context.Background(), time.Second)
		_ = conn.Close(cctx)
		cancel()
	}()
	for _, ch := range []string{ChanWake, ChanRevoke, ChanWS} {
		if _, err := conn.Exec(ctx, "LISTEN "+ch); err != nil {
			return false, err
		}
	}
	l.readyOnce.Do(func() { close(l.ready) })
	l.log.Info("notify listener ready")
	for {
		wctx, cancel := context.WithTimeout(ctx, l.pingInterval)
		n, err := conn.WaitForNotification(wctx)
		cancel()
		switch {
		case err == nil:
			l.dispatch(n.Channel, n.Payload)
		case ctx.Err() != nil:
			return true, ctx.Err()
		case errors.Is(err, context.DeadlineExceeded) || isTimeout(err): // idle: check the connection
			pctx, cancel := context.WithTimeout(ctx, l.pingInterval)
			err = conn.Ping(pctx)
			cancel()
			if err != nil {
				return true, err
			}
		default:
			return true, err
		}
	}
}

func isTimeout(err error) bool {
	var t interface{ Timeout() bool }
	return errors.As(err, &t) && t.Timeout()
}

// dispatch routes one notification to the hub; malformed payloads are ignored.
func (l *Listener) dispatch(channel, payload string) {
	switch channel {
	case ChanWake:
		dev, msg, ok := pair(payload)
		if ok {
			l.hub.Wake(dev, msg)
		}
	case ChanRevoke:
		if dev, err := uuid.Parse(payload); err == nil {
			l.hub.Revoke(dev)
		}
	case ChanWS:
		dev, connID, ok := pair(payload)
		if ok {
			l.hub.Connected(dev, connID)
		}
	}
}

func pair(s string) (uuid.UUID, uuid.UUID, bool) {
	a, b, ok := strings.Cut(s, ":")
	if !ok {
		return uuid.Nil, uuid.Nil, false
	}
	x, err1 := uuid.Parse(a)
	y, err2 := uuid.Parse(b)
	return x, y, err1 == nil && err2 == nil
}
