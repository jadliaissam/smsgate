// Package testdb provides per-test isolated Postgres databases cloned from a migrated template.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/db"
)

var (
	once    sync.Once
	tmplErr error
	tmpl    string
	// cloneMu serialises CREATE DATABASE ... TEMPLATE: Postgres rejects concurrent clones of one template.
	cloneMu sync.Mutex
)

// dropStale removes templates left behind by earlier runs whose process is gone.
func dropStale(ctx context.Context, c *pgx.Conn, self string) {
	rows, err := c.Query(ctx, `SELECT datname FROM pg_database WHERE datname LIKE 'smsgate\_tmpl\_%'`)
	if err != nil {
		return
	}
	var stale []string
	for rows.Next() {
		var n string
		if rows.Scan(&n) == nil && n != self {
			stale = append(stale, n)
		}
	}
	rows.Close()
	for _, n := range stale {
		pid, err := strconv.Atoi(strings.TrimPrefix(n, "smsgate_tmpl_"))
		if err == nil && syscall.Kill(pid, 0) == nil {
			continue // owner still running (parallel package test binary)
		}
		_, _ = c.Exec(ctx, "DROP DATABASE IF EXISTS "+n+" WITH (FORCE)")
	}
}

func withDB(base, name string) string {
	u, err := url.Parse(base)
	if err != nil {
		return base
	}
	u.Path = "/" + name
	return u.String()
}

func admin(ctx context.Context, base string, fn func(*pgx.Conn) error) error {
	c, err := pgx.Connect(ctx, base)
	if err != nil {
		return err
	}
	defer c.Close(ctx)
	return fn(c)
}

func setup(ctx context.Context, base string) error {
	tmpl = fmt.Sprintf("smsgate_tmpl_%d", os.Getpid())
	err := admin(ctx, base, func(c *pgx.Conn) error {
		dropStale(ctx, c, tmpl)
		if _, err := c.Exec(ctx, "DROP DATABASE IF EXISTS "+tmpl); err != nil {
			return err
		}
		_, err := c.Exec(ctx, "CREATE DATABASE "+tmpl)
		return err
	})
	if err != nil {
		return err
	}
	return db.Migrate(ctx, withDB(base, tmpl))
}

// New returns a pool on a fresh migrated database, dropped at test cleanup.
// The test is skipped when TEST_DATABASE_URL is unset.
func New(t *testing.T) *pgxpool.Pool { t.Helper(); return NewMax(t, 0) }

// NewMax is New with the pool capped at maxConns connections (0 = pgx default).
func NewMax(t *testing.T, maxConns int32) *pgxpool.Pool {
	t.Helper()
	base := os.Getenv("TEST_DATABASE_URL")
	if base == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	once.Do(func() { tmplErr = setup(ctx, base) })
	if tmplErr != nil {
		t.Fatalf("testdb template: %v", tmplErr)
	}
	var rnd [6]byte
	_, _ = rand.Read(rnd[:])
	name := "t_" + hex.EncodeToString(rnd[:])
	cloneMu.Lock()
	err := admin(ctx, base, func(c *pgx.Conn) error {
		_, err := c.Exec(ctx, fmt.Sprintf("CREATE DATABASE %s TEMPLATE %s", name, tmpl))
		return err
	})
	cloneMu.Unlock()
	if err != nil {
		t.Fatalf("create test db: %v", err)
	}
	pcfg, err := pgxpool.ParseConfig(withDB(base, name))
	if err != nil {
		t.Fatalf("parse test db url: %v", err)
	}
	if maxConns > 0 {
		pcfg.MaxConns = maxConns
	}
	pool, err := pgxpool.NewWithConfig(ctx, pcfg)
	if err != nil {
		t.Fatalf("open test db: %v", err)
	}
	t.Cleanup(func() {
		pool.Close()
		dctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		_ = admin(dctx, base, func(c *pgx.Conn) error {
			_, err := c.Exec(dctx, "DROP DATABASE IF EXISTS "+name+" WITH (FORCE)")
			return err
		})
	})
	return pool
}
