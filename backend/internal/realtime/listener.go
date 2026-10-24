package realtime

import (
	"context"
	"errors"
	"log/slog"
	"math/rand/v2"
	"strconv"
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
	ChanWS     = "smsgate_ws"     // "<device_id>:<conn_id>:<seq>" (socket opened)
)

// Listener bridges Postgres notifications to a Hub over one dedicated
// connection (outside the pool). It reconnects with jittered exponential
// backoff. Notifications sent while it was down are lost, so after each
// re-LISTEN it has the hub replay wakes for messages queued since the outage
// began (Hub.Resync); FCM and the device's own pending sync are the backstops.
type Listener struct {
	cfg          *pgx.ConnConfig
	hub          *Hub
	log          *slog.Logger
	retryMax     time.Duration
	pingInterval time.Duration

	readyOnce sync.Once
	ready     chan struct{}
	// alive is the last time the connection was known healthy (LISTEN, a
	// notification or a ping reply); lostAt is where the next Resync starts: the
	// last healthy moment before the connection was lost, or the listener's
	// creation until the first LISTEN. Zero once replayed. Listener goroutine only.
	alive, lostAt time.Time
}

// NewListener builds a listener; cfg is copied.
func NewListener(cfg *pgx.ConnConfig, hub *Hub, log *slog.Logger, retryMax, pingInterval time.Duration) *Listener {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	now := hub.now()
	return &Listener{cfg: cfg.Copy(), hub: hub, log: log, retryMax: retryMax, pingInterval: pingInterval, ready: make(chan struct{}), alive: now, lostAt: now}
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
		if l.lostAt.IsZero() { // anything sent after the last healthy moment may be lost
			l.lostAt = l.alive
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
	l.alive = l.hub.now()
	if !l.lostAt.IsZero() {
		rctx, cancel := context.WithTimeout(ctx, 30*time.Second)
		err := l.hub.Resync(rctx, l.lostAt)
		cancel()
		if err != nil {
			l.log.Warn("ws resync failed, retrying after the next reconnect", "err", err) // lostAt stays
		} else {
			l.lostAt = time.Time{}
		}
	}
	for {
		wctx, cancel := context.WithTimeout(ctx, l.pingInterval)
		n, err := conn.WaitForNotification(wctx)
		cancel()
		switch {
		case err == nil:
			l.alive = l.hub.now()
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
			l.alive = l.hub.now()
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
		dev, rest, ok := strings.Cut(payload, ":")
		connID, seq, ok2 := strings.Cut(rest, ":")
		d, err1 := uuid.Parse(dev)
		c, err2 := uuid.Parse(connID)
		n, err3 := strconv.ParseInt(seq, 10, 64)
		if ok && ok2 && err1 == nil && err2 == nil && err3 == nil {
			l.hub.Connected(d, c, n)
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
