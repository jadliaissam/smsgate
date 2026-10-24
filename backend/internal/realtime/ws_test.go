package realtime_test

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/realtime"
)

// noFallback keeps the FCM fallback out of tests that assert "no FCM".
func noFallback(c *config.Config) { c.WSFCMFallback = time.Hour }

type fixture struct {
	a      *apptest.App
	cookie *http.Cookie
	key    string
	dev    uuid.UUID
	tok    string
}

func setup(t *testing.T, a *apptest.App) fixture {
	t.Helper()
	c := a.Signup(t, "ws@example.com")
	key := a.APIKey(t, c)
	dev, tok := a.PairDevice(t, c)
	return fixture{a: a, cookie: c, key: key, dev: dev, tok: tok}
}

func wsURL(a *apptest.App) string { return "ws" + strings.TrimPrefix(a.URL, "http") + "/device/v1/ws" }

func dialRaw(t *testing.T, a *apptest.App, tok string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	h := http.Header{}
	if tok != "" {
		h.Set("Authorization", "Bearer "+tok)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return websocket.Dial(ctx, wsURL(a), &websocket.DialOptions{HTTPHeader: h})
}

// dial connects and completes one ping/pong, so the server has registered the
// socket and written ws_connected_at.
func dial(t *testing.T, a *apptest.App, tok string) *websocket.Conn {
	t.Helper()
	c, _, err := dialRaw(t, a, tok)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	if f := roundTrip(t, c, `{"type":"ping"}`); f["type"] != "pong" {
		t.Fatalf("ping: %v", f)
	}
	return c
}

func send(t *testing.T, c *websocket.Conn, frame string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageText, []byte(frame)); err != nil {
		t.Fatalf("write: %v", err)
	}
}

func read(t *testing.T, c *websocket.Conn) map[string]any {
	t.Helper()
	m, err := tryRead(c, 5*time.Second)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return m
}

func tryRead(c *websocket.Conn, d time.Duration) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(context.Background(), d)
	defer cancel()
	_, b, err := c.Read(ctx)
	if err != nil {
		return nil, err
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		return nil, err
	}
	return m, nil
}

func roundTrip(t *testing.T, c *websocket.Conn, frame string) map[string]any {
	t.Helper()
	send(t, c, frame)
	return read(t, c)
}

// closedWith reads until the socket closes and returns the close code.
func closedWith(t *testing.T, c *websocket.Conn) websocket.StatusCode {
	t.Helper()
	for {
		_, err := tryRead(c, 5*time.Second)
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) {
				t.Fatal("socket not closed")
			}
			return websocket.CloseStatus(err)
		}
	}
}

func (f fixture) createMessage(t *testing.T, a *apptest.App) string {
	t.Helper()
	r, m := a.Do("POST", "/v1/messages", map[string]any{"device_id": f.dev.String(), "to": "+37060000000", "body": "code 1234"},
		"Authorization", "Bearer "+f.key)
	if r.StatusCode != 202 {
		t.Fatalf("create: %d %v", r.StatusCode, m)
	}
	return m["id"].(string)
}

func (f fixture) wsConnectedAt(t *testing.T) *time.Time {
	t.Helper()
	var at *time.Time
	if err := f.a.Pool.QueryRow(context.Background(), "SELECT ws_connected_at FROM devices WHERE id = $1", f.dev).Scan(&at); err != nil {
		t.Fatal(err)
	}
	return at
}

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestWSNewFrameSameInstance(t *testing.T) {
	a := apptest.New(t, noFallback)
	f := setup(t, a)
	c := dial(t, a, f.tok)
	if f.wsConnectedAt(t) == nil {
		t.Fatal("ws_connected_at not set")
	}
	id := f.createMessage(t, a)
	if m := read(t, c); m["type"] != "new" || m["message_id"] != id || len(m) != 2 {
		t.Fatalf("frame %v", m)
	}
	time.Sleep(50 * time.Millisecond)
	if calls := a.Waker.Calls(); len(calls) != 0 {
		t.Fatalf("FCM sent while connected: %v", calls)
	}
}

func TestWSCrossInstance(t *testing.T) {
	a := apptest.New(t, noFallback)
	b := a.Peer(t)
	f := setup(t, a)
	c := dial(t, b, f.tok) // socket held by b
	id := f.createMessage(t, a)
	if m := read(t, c); m["type"] != "new" || m["message_id"] != id {
		t.Fatalf("frame %v", m)
	}
	if len(a.Waker.Calls())+len(b.Waker.Calls()) != 0 {
		t.Fatal("FCM sent while connected on the peer")
	}
}

func TestWSFCMUsedAfterDisconnect(t *testing.T) {
	a := apptest.New(t, noFallback)
	f := setup(t, a)
	c := dial(t, a, f.tok)
	f.createMessage(t, a)
	read(t, c)
	if err := c.Close(websocket.StatusNormalClosure, ""); err != nil {
		t.Fatal(err)
	}
	eventually(t, "ws_connected_at cleared", func() bool { return f.wsConnectedAt(t) == nil })
	id := f.createMessage(t, a)
	calls := a.Waker.WaitCalls(1, 5*time.Second)
	if len(calls) != 1 || calls[0].MessageID.String() != id || calls[0].FCMToken != "fcm-test" {
		t.Fatalf("calls %v", calls)
	}
}

func TestWSStaleMarkUsesFCM(t *testing.T) {
	a := apptest.New(t, noFallback)
	f := setup(t, a)
	// A crashed instance left the mark behind; past WS_STALE_AFTER it no
	// longer suppresses FCM.
	old := a.Now().Add(-a.Cfg.WSStaleAfter - time.Second)
	if _, err := a.Pool.Exec(context.Background(), "UPDATE devices SET ws_connected_at = $2 WHERE id = $1", f.dev, old); err != nil {
		t.Fatal(err)
	}
	f.createMessage(t, a)
	if len(a.Waker.WaitCalls(1, 5*time.Second)) != 1 {
		t.Fatal("no FCM for a stale socket mark")
	}
}

func TestWSFCMFallback(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.WSFCMFallback = 30 * time.Millisecond })
	f := setup(t, a)
	c := dial(t, a, f.tok)

	// Claimed in time: no FCM.
	id := f.createMessage(t, a)
	read(t, c)
	if r, m := a.Do("POST", "/device/v1/messages/"+id+"/claim", nil, "Authorization", "Bearer "+f.tok); r.StatusCode != 200 {
		t.Fatalf("claim: %d %v", r.StatusCode, m)
	}
	time.Sleep(150 * time.Millisecond)
	if n := len(a.Waker.Calls()); n != 0 {
		t.Fatalf("FCM for a claimed message: %d", n)
	}

	// Not claimed (socket half-dead): FCM follows.
	id = f.createMessage(t, a)
	calls := a.Waker.WaitCalls(1, 5*time.Second)
	if len(calls) != 1 || calls[0].MessageID.String() != id {
		t.Fatalf("calls %v", calls)
	}
}

func TestWSReplaced(t *testing.T) {
	a := apptest.New(t, noFallback)
	f := setup(t, a)
	c1 := dial(t, a, f.tok)
	c2 := dial(t, a, f.tok)
	if code := closedWith(t, c1); code != realtime.CloseReplaced {
		t.Fatalf("old socket closed with %d", code)
	}
	id := f.createMessage(t, a)
	if m := read(t, c2); m["message_id"] != id {
		t.Fatalf("frame %v", m)
	}
	if a.Hub.Len() != 1 {
		t.Fatalf("hub holds %d", a.Hub.Len())
	}
}

func TestWSReplacedAcrossInstances(t *testing.T) {
	a := apptest.New(t, noFallback)
	b := a.Peer(t)
	f := setup(t, a)
	c1 := dial(t, a, f.tok)
	c2 := dial(t, b, f.tok)
	if code := closedWith(t, c1); code != realtime.CloseReplaced {
		t.Fatalf("old socket closed with %d", code)
	}
	eventually(t, "old instance released the socket", func() bool { return a.Hub.Len() == 0 })
	// The old instance's cleanup must not clear the new connection's mark.
	if f.wsConnectedAt(t) == nil {
		t.Fatal("mark cleared by the replaced connection")
	}
	f.createMessage(t, a)
	if m := read(t, c2); m["type"] != "new" {
		t.Fatalf("frame %v", m)
	}
}

func TestWSRevoke(t *testing.T) {
	for _, via := range []string{"dashboard", "unregister"} {
		t.Run(via, func(t *testing.T) {
			a := apptest.New(t, noFallback)
			b := a.Peer(t)
			f := setup(t, a)
			c := dial(t, b, f.tok) // revocation on a reaches the socket on b
			var r *http.Response
			if via == "dashboard" {
				r, _ = a.Do("DELETE", "/app/devices/"+f.dev.String(), nil, "Cookie", f.cookie.Name+"="+f.cookie.Value)
			} else {
				r, _ = a.Do("POST", "/device/v1/unregister", nil, "Authorization", "Bearer "+f.tok)
			}
			if r.StatusCode != 204 {
				t.Fatalf("revoke: %d", r.StatusCode)
			}
			if m := read(t, c); m["type"] != "revoked" || len(m) != 1 {
				t.Fatalf("frame %v", m)
			}
			if code := closedWith(t, c); code != realtime.CloseRevoked {
				t.Fatalf("closed with %d", code)
			}
		})
	}
}

func TestWSUnauthorizedBeforeUpgrade(t *testing.T) {
	a := apptest.New(t)
	f := setup(t, a)
	if r, _ := a.Do("POST", "/device/v1/unregister", nil, "Authorization", "Bearer "+f.tok); r.StatusCode != 204 {
		t.Fatal("unregister")
	}
	for name, tok := range map[string]string{"missing": "", "invalid": "dt_nope", "revoked": f.tok} {
		_, resp, err := dialRaw(t, a, tok)
		if err == nil || resp == nil || resp.StatusCode != 401 {
			t.Fatalf("%s token: err=%v resp=%v", name, err, resp)
		}
	}
	if a.Hub.Len() != 0 {
		t.Fatal("socket registered")
	}
}

func TestWSRateLimitedUpgrade(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLDevicePerMin = 2 })
	f := setup(t, a)
	dial(t, a, f.tok) // upgrade + the ping frame use the 2 tokens
	_, resp, err := dialRaw(t, a, f.tok)
	if err == nil || resp == nil || resp.StatusCode != 429 {
		t.Fatalf("err=%v resp=%v", err, resp)
	}
}

func TestWSFrameRateLimited(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.RLDevicePerMin = 3 })
	f := setup(t, a)
	c := dial(t, a, f.tok) // upgrade + ping
	if m := roundTrip(t, c, `{"type":"ping"}`); m["type"] != "pong" {
		t.Fatalf("%v", m)
	}
	if m := roundTrip(t, c, `{"type":"ping"}`); m["type"] != "error" || m["code"] != "rate_limited" {
		t.Fatalf("%v", m)
	}
}

func TestWSCapacity(t *testing.T) {
	a := apptest.New(t, func(c *config.Config) { c.WSMaxConns = 1 })
	f := setup(t, a)
	dial(t, a, f.tok)
	_, tok2 := a.PairDevice(t, f.cookie)
	_, resp, err := dialRaw(t, a, tok2)
	if err == nil || resp == nil || resp.StatusCode != 503 {
		t.Fatalf("err=%v resp=%v", err, resp)
	}
	dial(t, a, f.tok) // replacing the device's own socket is still allowed
}

func TestWSPingTimeout(t *testing.T) {
	a := apptest.New(t)
	base := a.Now()
	a.SetNow(func() time.Time { return base })
	f := setup(t, a)
	c := dial(t, a, f.tok)

	// Active within the timeout: kept.
	a.SetNow(func() time.Time { return base.Add(a.Cfg.WSPingTimeout - time.Minute) })
	roundTrip(t, c, `{"type":"ping"}`)
	a.SetNow(func() time.Time { return base.Add(a.Cfg.WSPingTimeout + time.Second) })
	a.Hub.Reap()
	if m := roundTrip(t, c, `{"type":"ping"}`); m["type"] != "pong" {
		t.Fatalf("%v", m)
	}

	// Silent past the timeout: closed.
	a.SetNow(func() time.Time { return base.Add(3 * a.Cfg.WSPingTimeout) })
	a.Hub.Reap()
	if code := closedWith(t, c); code != realtime.CloseIdle {
		t.Fatalf("closed with %d", code)
	}
}

func TestWSHeartbeat(t *testing.T) {
	a := apptest.New(t)
	f := setup(t, a)
	c := dial(t, a, f.tok)
	m := roundTrip(t, c, `{"type":"heartbeat","battery":55,"charging":true,"signal":3,"app_version":"2.0",
		"sims":[{"slot":1,"subscription_id":7,"carrier":"Tele2"}]}`)
	if m["type"] != "pong" || len(m) != 2 {
		t.Fatalf("%v", m)
	}
	if _, err := time.Parse(time.RFC3339Nano, m["server_time"].(string)); err != nil {
		t.Fatal(err)
	}
	var battery, signal int16
	var charging bool
	var appVersion, sims string
	var seen *time.Time
	if err := a.Pool.QueryRow(context.Background(), "SELECT battery, charging, signal, app_version, sims::text, last_seen_at FROM devices WHERE id = $1", f.dev).
		Scan(&battery, &charging, &signal, &appVersion, &sims, &seen); err != nil {
		t.Fatal(err)
	}
	if battery != 55 || !charging || signal != 3 || appVersion != "2.0" || !strings.Contains(sims, "Tele2") || seen == nil {
		t.Fatalf("row %d %v %d %s %s %v", battery, charging, signal, appVersion, sims, seen)
	}

	for frame, code := range map[string]string{
		`{"type":"heartbeat","battery":101}`: "invalid_heartbeat",
		`{"type":"heartbeat","bogus":1}`:     "invalid_json",
		`not json`:                           "invalid_json",
		`{"type":"subscribe"}`:               "unknown_type",
	} {
		if m := roundTrip(t, c, frame); m["type"] != "error" || m["code"] != code || m["message"] == "" {
			t.Fatalf("%s: %v", frame, m)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.Write(ctx, websocket.MessageBinary, []byte{1}); err != nil {
		t.Fatal(err)
	}
	if m := read(t, c); m["code"] != "unsupported_frame" {
		t.Fatalf("%v", m)
	}
}

func TestWSOnlineStatus(t *testing.T) {
	a := apptest.New(t)
	f := setup(t, a)
	online := func() bool {
		_, m := a.Do("GET", "/app/devices", nil, "Cookie", f.cookie.Name+"="+f.cookie.Value)
		return m["data"].([]any)[0].(map[string]any)["online"].(bool)
	}
	if online() {
		t.Fatal("online before any heartbeat or socket")
	}
	c := dial(t, a, f.tok)
	if !online() {
		t.Fatal("offline with a live socket")
	}
	// A socket whose mark went stale (instance crash) no longer counts.
	a.Advance(a.Cfg.WSStaleAfter + time.Minute)
	if online() {
		t.Fatal("online with a stale socket mark")
	}
	// The next frame refreshes it.
	roundTrip(t, c, `{"type":"ping"}`)
	if !online() {
		t.Fatal("frame did not refresh the mark")
	}
	_ = c.Close(websocket.StatusNormalClosure, "")
	eventually(t, "offline after disconnect", func() bool { return !online() })
}

func TestWSShutdown(t *testing.T) {
	a := apptest.New(t)
	f := setup(t, a)
	c := dial(t, a, f.tok)
	go a.Hub.Shutdown(context.Background())
	if code := closedWith(t, c); code != websocket.StatusGoingAway {
		t.Fatalf("closed with %d", code)
	}
	eventually(t, "mark cleared", func() bool { return f.wsConnectedAt(t) == nil })
	_, resp, err := dialRaw(t, a, f.tok)
	if err == nil || resp == nil || resp.StatusCode != 503 {
		t.Fatalf("after shutdown: err=%v resp=%v", err, resp)
	}
}

func TestWSListenerReconnects(t *testing.T) {
	a := apptest.New(t, noFallback, func(c *config.Config) { c.WSListenRetryMax = 50 * time.Millisecond })
	f := setup(t, a)
	c := dial(t, a, f.tok)
	listener := func() (pid int32) {
		_ = a.Pool.QueryRow(context.Background(),
			`SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND query LIKE 'LISTEN %' AND pid <> pg_backend_pid()`).Scan(&pid)
		return pid
	}
	old := listener()
	if old == 0 {
		t.Fatal("no listener connection")
	}
	if _, err := a.Pool.Exec(context.Background(), "SELECT pg_terminate_backend($1)", old); err != nil {
		t.Fatal(err)
	}
	// LISTEN on smsgate_ws is the last statement of a new session.
	eventually(t, "listener reconnected", func() bool {
		var n int
		_ = a.Pool.QueryRow(context.Background(),
			`SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND query = 'LISTEN smsgate_ws' AND pid <> $1`, old).Scan(&n)
		return n == 1
	})
	id := f.createMessage(t, a)
	if m := read(t, c); m["message_id"] != id {
		t.Fatalf("frame %v", m)
	}
}

// A client may send its first frame right behind the upgrade request, before
// the server has hijacked the connection; that frame must not be lost.
func TestWSFramePipelinedWithHandshake(t *testing.T) {
	a := apptest.New(t)
	f := setup(t, a)
	host := strings.TrimPrefix(a.URL, "http://")
	c, err := net.Dial("tcp", host)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ping := []byte(`{"type":"ping"}`)
	frame := append([]byte{0x81, 0x80 | byte(len(ping)), 0, 0, 0, 0}, ping...) // masked, zero key
	req := fmt.Sprintf("GET /device/v1/ws HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer %s\r\n\r\n", host, f.tok)
	if _, err := c.Write(append([]byte(req), frame...)); err != nil {
		t.Fatal(err)
	}
	_ = c.SetReadDeadline(time.Now().Add(5 * time.Second))
	br := bufio.NewReader(c)
	if line, _ := br.ReadString('\n'); !strings.Contains(line, " 101 ") {
		t.Fatalf("status %q", line)
	}
	for {
		l, err := br.ReadString('\n')
		if err != nil {
			t.Fatal(err)
		}
		if l == "\r\n" {
			break
		}
	}
	hdr := make([]byte, 2)
	if _, err := io.ReadFull(br, hdr); err != nil {
		t.Fatalf("no reply frame: %v", err)
	}
	body := make([]byte, hdr[1]&0x7f)
	if _, err := io.ReadFull(br, body); err != nil {
		t.Fatal(err)
	}
	if hdr[0] != 0x81 || !strings.Contains(string(body), `"type":"pong"`) {
		t.Fatalf("reply %x %s", hdr, body)
	}
}

// A burst of wakes for one device (bulk sends) must all arrive in order and
// must not cost the device its socket.
func TestWSWakeBurst(t *testing.T) {
	a := apptest.New(t, noFallback)
	f := setup(t, a)
	c := dial(t, a, f.tok)
	const n = 100
	ids := make([]string, n)
	for i := range ids {
		ids[i] = uuid.Must(uuid.NewV7()).String()
	}
	// One statement: the listener receives all n notifications at once.
	if _, err := a.Pool.Exec(context.Background(),
		`SELECT pg_notify('smsgate_wake', $1 || ':' || id) FROM unnest($2::text[]) AS id`, f.dev.String(), ids); err != nil {
		t.Fatal(err)
	}
	for i, want := range ids {
		if m := read(t, c); m["type"] != "new" || m["message_id"] != want {
			t.Fatalf("frame %d: %v, want message %s", i, m, want)
		}
	}
	if m := roundTrip(t, c, `{"type":"ping"}`); m["type"] != "pong" {
		t.Fatalf("socket not usable after burst: %v", m)
	}
}
