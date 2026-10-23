// Package testdb provides per-test isolated Postgres databases cloned from a migrated template.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"os"
	"sync"
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
)

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
func New(t *testing.T) *pgxpool.Pool {
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
	err := admin(ctx, base, func(c *pgx.Conn) error {
		_, err := c.Exec(ctx, fmt.Sprintf("CREATE DATABASE %s TEMPLATE %s", name, tmpl))
		return err
	})
	if err != nil {
		t.Fatalf("create test db: %v", err)
	}
	pool, err := pgxpool.New(ctx, withDB(base, name))
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
