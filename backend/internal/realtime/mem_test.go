package realtime_test

import (
	"bufio"
	"context"
	"fmt"
	"net"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/apptest"
	"github.com/smsgate/backend/internal/secret"
)

// TestWSMemoryPerIdleConn measures the server-side cost of an idle device
// socket. Clients are raw TCP connections that only perform the handshake, so
// they add little beyond a file descriptor; the figure is an upper bound.
func TestWSMemoryPerIdleConn(t *testing.T) {
	if testing.Short() {
		t.Skip("short")
	}
	const n = 400
	a := apptest.New(t)
	f := setup(t, a)
	var uid uuid.UUID
	if err := a.Pool.QueryRow(context.Background(), "SELECT user_id FROM devices WHERE id = $1", f.dev).Scan(&uid); err != nil {
		t.Fatal(err)
	}
	toks := make([]string, n)
	rows := make([][]any, n)
	for i := range toks {
		toks[i] = fmt.Sprintf("dt_mem_%d", i)
		rows[i] = []any{uuid.Must(uuid.NewV7()), uid, "m", secret.Hash(toks[i])}
	}
	if _, err := a.Pool.CopyFrom(context.Background(), pgx.Identifier{"devices"}, []string{"id", "user_id", "name", "token_hash"}, pgx.CopyFromRows(rows)); err != nil {
		t.Fatal(err)
	}
	host := strings.TrimPrefix(a.URL, "http://")
	open := func(tok string) net.Conn {
		c, err := net.Dial("tcp", host)
		if err != nil {
			t.Fatal(err)
		}
		fmt.Fprintf(c, "GET /device/v1/ws HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
			"Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer %s\r\n\r\n", host, tok)
		br := bufio.NewReaderSize(c, 512)
		line, err := br.ReadString('\n')
		if err != nil || !strings.Contains(line, " 101 ") {
			t.Fatalf("handshake: %q %v", line, err)
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
		return c
	}
	// Warm up pools (DB connections, buffers) before measuring.
	warm := open(toks[0])
	eventually(t, "warm socket", func() bool { return a.Hub.Len() == 1 })

	measure := func() (heap, stack uint64, g int) {
		runtime.GC()
		runtime.GC()
		var ms runtime.MemStats
		runtime.ReadMemStats(&ms)
		return ms.HeapInuse, ms.StackInuse, runtime.NumGoroutine()
	}
	time.Sleep(50 * time.Millisecond)
	h0, s0, g0 := measure()
	conns := make([]net.Conn, 0, n-1)
	for _, tok := range toks[1:] {
		conns = append(conns, open(tok))
	}
	eventually(t, "all sockets registered", func() bool { return a.Hub.Len() == n })
	time.Sleep(100 * time.Millisecond) // let connect-time goroutines (drain, DB) finish
	h1, s1, g1 := measure()
	k := float64(n - 1)
	heapPer, stackPer := float64(int64(h1)-int64(h0))/k, float64(int64(s1)-int64(s0))/k
	gPer := float64(g1-g0) / k
	t.Logf("per idle socket: heap %.0f B, stack %.0f B, total %.1f KiB, goroutines %.2f (10k sockets ~ %.0f MiB)",
		heapPer, stackPer, (heapPer+stackPer)/1024, gPer, (heapPer+stackPer)*10000/(1<<20))
	if gPer > 1.05 {
		t.Errorf("%.2f goroutines per idle socket, want 1 (the reader)", gPer)
	}
	if heapPer+stackPer > 14<<10 && !raceEnabled {
		t.Errorf("%.0f bytes per idle socket, want <= 14 KiB", heapPer+stackPer)
	}
	for _, c := range conns {
		_ = c.Close()
	}
	_ = warm.Close()
	eventually(t, "sockets released", func() bool { return a.Hub.Len() == 0 })
}
