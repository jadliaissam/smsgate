// Package realtime holds device WebSockets (GET /device/v1/ws) and routes
// wake-ups and revocations to them across instances through Postgres
// LISTEN/NOTIFY.
//
// Frames are JSON text messages. Server to device:
//
//	{"type":"new","message_id":"<uuid>"}
//	{"type":"revoked"}                       (then close 4003)
//	{"type":"pong","server_time":"<RFC 3339>"}
//	{"type":"error","code":"...","message":"..."}
//
// Device to server: {"type":"ping"} or {"type":"heartbeat", ...} (the frame
// handler is supplied by the devices package).
//
// Memory per idle connection is one goroutine (the reader) plus the
// connection's buffers; writes go through a small buffered queue drained by a
// short-lived goroutine started only while frames are pending. There are no
// per-connection timers: one reaper closes sockets silent for longer than the
// ping timeout.
package realtime

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
)

// Close codes sent to devices.
const (
	CloseIdle     websocket.StatusCode = 4000 // no frame within the ping timeout
	CloseReplaced websocket.StatusCode = 4001 // a newer connection of the same device took over
	CloseRevoked  websocket.StatusCode = 4003 // the device was revoked
)

// maxQueue bounds the frames waiting to be written to one socket. The queue
// grows on demand (an idle socket holds none), so a burst of wakes for one
// device is delivered in order; a device that falls this far behind is
// disconnected and catches up via pending.
const maxQueue = 128

// bufSize is the read and write buffer of a hijacked socket. Device frames
// are small; net/http's 4 KiB pair would otherwise be most of an idle
// socket's memory.
const bufSize = 512

// FrameHandler handles one text frame from a device and returns the reply
// frame, or nil for none. ctx is bounded by Options.FrameTimeout.
type FrameHandler func(ctx context.Context, data []byte) []byte

// Options configure a Hub.
type Options struct {
	Q   *dbq.Queries
	Log *slog.Logger
	Now func() time.Time // app clock (ws_connected_at, ping timeout)

	PingTimeout  time.Duration // close a socket silent for longer
	WriteTimeout time.Duration // per-frame write budget
	StaleAfter   time.Duration // must exceed PingTimeout
	FrameTimeout time.Duration // DB work per received frame
	MaxConns     int
	ReadLimit    int64         // max frame size in bytes
	ResyncMargin time.Duration // Resync looks back this far before the outage began
}

// Hub is the registry of this instance's device sockets.
type Hub struct {
	opt Options
	// touchEvery throttles ws_connected_at refreshes: with at most this gap
	// between writes plus at most PingTimeout of client silence before the
	// socket is closed, a live socket's mark never gets older than StaleAfter.
	touchEvery time.Duration

	mu      sync.Mutex
	conns   map[uuid.UUID]*conn // by device id
	closing bool
	readers sync.WaitGroup
}

type outFrame struct {
	data   []byte               // written first when non-nil
	code   websocket.StatusCode // then close with this code when non-zero
	reason string
}

type conn struct {
	hub    *Hub
	id     uuid.UUID // UUIDv7: orders connections of one device across instances
	device uuid.UUID
	ws     *websocket.Conn

	qmu      sync.Mutex
	q        []outFrame   // frames waiting to be written, guarded by qmu
	draining bool         // a drain goroutine is running, guarded by qmu
	closed   bool         // socket closing or closed: accept nothing more, guarded by qmu
	lastSeen atomic.Int64 // unix nanos of the last received frame (hub clock)
	reaped   bool         // guarded by Hub.mu

	// seq is the DB-assigned order of this connection among the device's
	// connections (0 until WSConnect returns); rival is the newest sequence
	// announced by another connection meanwhile. Both guarded by Hub.mu.
	seq, rival int64

	touched time.Time // ws_connected_at last written; reader goroutine only
}

// NewHub builds a hub. Call Run to start the reaper and Shutdown to stop.
func NewHub(o Options) *Hub {
	if o.Now == nil {
		o.Now = time.Now
	}
	if o.Log == nil {
		o.Log = slog.New(slog.DiscardHandler)
	}
	return &Hub{opt: o, touchEvery: o.StaleAfter - o.PingTimeout, conns: make(map[uuid.UUID]*conn)}
}

// now returns the hub clock truncated to Postgres' microsecond precision (so
// a written ws_connected_at compares equal when read back).
func (h *Hub) now() time.Time { return h.opt.Now().Truncate(time.Microsecond) }

func busy(code, msg string) *httpx.Error {
	return &httpx.Error{Status: http.StatusServiceUnavailable, Code: code, Message: msg}
}

// Serve upgrades an authenticated device request. Errors returned before the
// upgrade (capacity, shutdown) are rendered by httpx; after the upgrade it
// returns nil and the socket lives on in its reader goroutine.
func (h *Hub) Serve(w http.ResponseWriter, r *http.Request, device uuid.UUID, fh FrameHandler) error {
	if err := h.admit(device); err != nil {
		return err
	}
	ws, err := websocket.Accept(smallBuffers{w}, r, &websocket.AcceptOptions{
		// Devices authenticate with a bearer token that browsers cannot attach
		// to a WebSocket, so a cross-origin page gains nothing: no Origin check.
		InsecureSkipVerify: true,
		CompressionMode:    websocket.CompressionDisabled,
	})
	if err != nil {
		return nil // Accept has written the error response
	}
	ws.SetReadLimit(h.opt.ReadLimit)
	now := h.now()
	c := &conn{hub: h, id: uuid.Must(uuid.NewV7()), device: device, ws: ws, touched: now}
	c.lastSeen.Store(now.UnixNano())

	h.mu.Lock()
	old := h.conns[device]
	switch {
	case h.closing:
		h.mu.Unlock()
		go ws.Close(websocket.StatusGoingAway, "server shutting down") //nolint:errcheck
		return nil
	case old == nil && len(h.conns) >= h.opt.MaxConns:
		h.mu.Unlock()
		go ws.Close(websocket.StatusTryAgainLater, "too many connections") //nolint:errcheck
		return nil
	}
	h.conns[device] = c
	h.readers.Add(1)
	h.mu.Unlock()
	if old != nil {
		old.enqueue(outFrame{code: CloseReplaced, reason: "replaced"})
	}

	// Mark the device connected and announce this connection so an older one
	// on another instance closes. Registered first: a revoke committed after
	// this finds the socket; one committed before makes this update miss.
	ctx, cancel := context.WithTimeout(context.Background(), h.opt.FrameTimeout)
	seq, err := h.opt.Q.WSConnect(ctx, dbq.WSConnectParams{ID: device, At: c.touched, ConnID: c.id.String()})
	cancel()
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		c.enqueue(outFrame{data: frameRevoked, code: CloseRevoked, reason: "revoked"})
	case err != nil:
		h.opt.Log.Error("ws connect: mark device", "device_id", device, "err", err)
		c.enqueue(outFrame{code: websocket.StatusInternalError, reason: "internal error"})
	default:
		h.setSeq(c, seq)
	}
	go h.read(c, fh)
	return nil
}

// smallBuffers hands the hijacked connection small bufio buffers.
type smallBuffers struct{ http.ResponseWriter }

func (s smallBuffers) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	c, brw, err := http.NewResponseController(s.ResponseWriter).Hijack()
	if err != nil {
		return nil, nil, err
	}
	n := brw.Reader.Buffered() // bytes the client sent before the hijack
	if n == 0 {
		return c, bufio.NewReadWriter(bufio.NewReaderSize(c, bufSize), bufio.NewWriterSize(c, bufSize)), nil
	}
	early, _ := brw.Reader.Peek(n)
	br := bufio.NewReaderSize(io.MultiReader(bytes.NewReader(bytes.Clone(early)), c), max(bufSize, n))
	// Accept re-wraps the reader around its Buffered() bytes and the raw
	// connection, so the early bytes must sit in the buffer, not in r.
	if _, err := br.Peek(n); err != nil {
		_ = c.Close()
		return nil, nil, err
	}
	return c, bufio.NewReadWriter(br, bufio.NewWriterSize(c, bufSize)), nil
}

// admit rejects an upgrade before it happens when shutting down or full.
func (h *Hub) admit(device uuid.UUID) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.closing {
		return busy("shutting_down", "server is shutting down, reconnect")
	}
	if _, replacing := h.conns[device]; !replacing && len(h.conns) >= h.opt.MaxConns {
		return busy("ws_capacity", "too many connections, retry later")
	}
	return nil
}

// read is the connection's only long-lived goroutine.
func (h *Hub) read(c *conn, fh FrameHandler) {
	defer h.readers.Done()
	defer h.cleanup(c)
	defer func() {
		if p := recover(); p != nil {
			h.opt.Log.Error("ws reader panic", "device_id", c.device, "panic", p)
		}
	}()
	for {
		// No context deadline: the reaper enforces the ping timeout, so an idle
		// socket carries no timer.
		typ, data, err := c.ws.Read(context.Background())
		if err != nil {
			return
		}
		now := h.now()
		c.lastSeen.Store(now.UnixNano())
		ctx, cancel := context.WithTimeout(context.Background(), h.opt.FrameTimeout)
		if now.Sub(c.touched) >= h.touchEvery {
			h.touch(ctx, c, now)
		}
		var reply []byte
		if typ == websocket.MessageText {
			reply = fh(ctx, data)
		} else {
			reply = ErrorFrame("unsupported_frame", "frames must be JSON text")
		}
		cancel()
		if reply != nil {
			c.enqueue(outFrame{data: reply})
		}
	}
}

// touch refreshes ws_connected_at; a revoked device is told and closed.
func (h *Hub) touch(ctx context.Context, c *conn, now time.Time) {
	n, err := h.opt.Q.WSTouch(ctx, dbq.WSTouchParams{ID: c.device, Seq: &c.seq, At: now})
	if err != nil {
		h.opt.Log.Warn("ws touch", "device_id", c.device, "err", err)
		return
	}
	c.touched = now
	if n == 0 {
		c.enqueue(outFrame{data: frameRevoked, code: CloseRevoked, reason: "revoked"})
	}
}

// cleanup unregisters c and clears ws_connected_at unless a newer connection
// owns it (best effort: WS_STALE_AFTER covers crashes).
func (h *Hub) cleanup(c *conn) {
	_ = c.ws.CloseNow()
	h.mu.Lock()
	if h.conns[c.device] == c {
		delete(h.conns, c.device)
	}
	h.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), h.opt.FrameTimeout)
	defer cancel()
	if err := h.opt.Q.WSDisconnect(ctx, dbq.WSDisconnectParams{ID: c.device, Seq: &c.seq}); err != nil {
		h.opt.Log.Warn("ws disconnect: clear mark", "device_id", c.device, "err", err)
	}
}

// enqueue queues f without blocking and reports whether the frame will be
// written. A full queue means the device is not reading: it is disconnected
// and will catch up through pending.
func (c *conn) enqueue(f outFrame) bool {
	c.qmu.Lock()
	if c.closed {
		c.qmu.Unlock()
		return false
	}
	if len(c.q) >= maxQueue {
		c.closed = true
		c.q = nil
		c.qmu.Unlock()
		go c.ws.CloseNow() //nolint:errcheck
		return false
	}
	c.q = append(c.q, f)
	start := !c.draining
	c.draining = true
	c.qmu.Unlock()
	if start {
		go c.drain()
	}
	return true
}

// drain writes queued frames in order and exits when the queue is empty, so
// an idle socket has no writer goroutine and no queue memory.
func (c *conn) drain() {
	for {
		c.qmu.Lock()
		if len(c.q) == 0 {
			c.q = nil
			c.draining = false
			c.qmu.Unlock()
			return
		}
		f := c.q[0]
		c.q[0] = outFrame{}
		c.q = c.q[1:]
		c.qmu.Unlock()
		if !c.write(f) {
			c.qmu.Lock()
			c.closed = true
			c.q = nil
			c.qmu.Unlock()
			return // draining stays set so nothing restarts
		}
	}
}

func (c *conn) write(f outFrame) bool {
	if f.data != nil {
		ctx, cancel := context.WithTimeout(context.Background(), c.hub.opt.WriteTimeout)
		err := c.ws.Write(ctx, websocket.MessageText, f.data)
		cancel()
		if err != nil {
			_ = c.ws.CloseNow()
			return false
		}
	}
	if f.code != 0 {
		_ = c.ws.Close(f.code, f.reason)
		return false
	}
	return true
}

// Run reaps silent sockets until ctx is done.
func (h *Hub) Run(ctx context.Context) {
	t := time.NewTicker(max(h.opt.PingTimeout/8, time.Second))
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			h.Reap()
		}
	}
}

// Reap closes (4000) every socket that sent nothing for PingTimeout.
func (h *Hub) Reap() {
	cutoff := h.opt.Now().Add(-h.opt.PingTimeout).UnixNano()
	var stale []*conn
	h.mu.Lock()
	for _, c := range h.conns {
		if !c.reaped && c.lastSeen.Load() < cutoff {
			c.reaped = true
			stale = append(stale, c)
		}
	}
	h.mu.Unlock()
	for _, c := range stale {
		c.enqueue(outFrame{code: CloseIdle, reason: "ping timeout"})
	}
}

// Len returns the number of sockets held.
func (h *Hub) Len() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns)
}

func (h *Hub) get(device uuid.UUID) *conn {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.conns[device]
}

// Wake sends {"type":"new"} if this instance holds the device's socket and
// reports whether it did.
func (h *Hub) Wake(device, message uuid.UUID) bool {
	c := h.get(device)
	if c == nil {
		return false
	}
	return c.enqueue(outFrame{data: newFrame(message)})
}

// Revoke sends {"type":"revoked"} and closes with 4003 if this instance holds
// the device's socket.
func (h *Hub) Revoke(device uuid.UUID) {
	if c := h.get(device); c != nil {
		c.enqueue(outFrame{data: frameRevoked, code: CloseRevoked, reason: "revoked"})
	}
}

// Connected handles the announcement of connection connID, numbered seq by
// the database, for device (from any instance): a held connection with a lower
// number is closed with 4001. The database numbers connections in commit order
// of the device row, so every instance agrees on which one survives, whatever
// its clock says.
func (h *Hub) Connected(device, connID uuid.UUID, seq int64) {
	h.mu.Lock()
	c := h.conns[device]
	replace := false
	switch {
	case c == nil || c.id == connID:
	case c.seq == 0:
		c.rival = max(c.rival, seq) // WSConnect still in flight: settled by setSeq
	default:
		replace = c.seq < seq
	}
	h.mu.Unlock()
	if replace {
		c.enqueue(outFrame{code: CloseReplaced, reason: "replaced"})
	}
}

// setSeq records c's sequence number; c is closed if a newer connection was
// announced before WSConnect returned.
func (h *Hub) setSeq(c *conn, seq int64) {
	h.mu.Lock()
	c.seq = seq
	replace := c.rival > seq
	h.mu.Unlock()
	if replace {
		c.enqueue(outFrame{code: CloseReplaced, reason: "replaced"})
	}
}

// Resync wakes the sockets held here for messages still queued that were
// created at or after since (minus ResyncMargin). Notifications sent while the
// LISTEN connection was down are lost; a device deduplicates repeats by id.
func (h *Hub) Resync(ctx context.Context, since time.Time) {
	h.mu.Lock()
	devices := make([]uuid.UUID, 0, len(h.conns))
	for d := range h.conns {
		devices = append(devices, d)
	}
	h.mu.Unlock()
	if len(devices) == 0 {
		return
	}
	rows, err := h.opt.Q.ListWakeReplay(ctx, dbq.ListWakeReplayParams{
		DeviceIds: devices, Since: since.Add(-h.opt.ResyncMargin), RowLimit: int32(min(h.opt.MaxConns, 1<<20)) * 10,
	})
	if err != nil {
		h.opt.Log.Warn("ws resync", "err", err)
		return
	}
	for _, r := range rows {
		h.Wake(r.DeviceID, r.ID)
	}
	h.opt.Log.Info("ws resync", "devices", len(devices), "wakes", len(rows))
}

// Shutdown closes every socket with 1001 and waits for the readers to finish
// (or ctx to end, after which the rest are dropped without a handshake).
func (h *Hub) Shutdown(ctx context.Context) {
	h.mu.Lock()
	h.closing = true
	all := make([]*conn, 0, len(h.conns))
	for _, c := range h.conns {
		all = append(all, c)
	}
	h.mu.Unlock()
	for _, c := range all {
		c.enqueue(outFrame{code: websocket.StatusGoingAway, reason: "server shutting down"})
	}
	done := make(chan struct{})
	go func() { h.readers.Wait(); close(done) }()
	select {
	case <-done:
	case <-ctx.Done():
		for _, c := range all {
			_ = c.ws.CloseNow()
		}
		<-done
	}
}

var frameRevoked = []byte(`{"type":"revoked"}`)

func newFrame(id uuid.UUID) []byte {
	b := make([]byte, 0, 64)
	b = append(b, `{"type":"new","message_id":"`...)
	b = append(b, id.String()...)
	return append(b, `"}`...)
}

// PongFrame is {"type":"pong","server_time":...}.
func PongFrame(serverTime time.Time) []byte {
	b, _ := json.Marshal(struct {
		Type       string    `json:"type"`
		ServerTime time.Time `json:"server_time"`
	}{"pong", serverTime.UTC()})
	return b
}

// ErrorFrame is {"type":"error","code":...,"message":...}.
func ErrorFrame(code, message string) []byte {
	b, _ := json.Marshal(struct {
		Type    string `json:"type"`
		Code    string `json:"code"`
		Message string `json:"message"`
	}{"error", code, message})
	return b
}
