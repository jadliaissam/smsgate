package db

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
)

// Several instances starting at once (e.g. a rolling deploy with pending
// migrations) must not race on migrations: goose's Postgres session advisory
// lock serialises them. The database is first brought to version 1 so the
// version table exists and goose's own create-table retry does not mask the race.
func TestConcurrentMigrate(t *testing.T) {
	base := os.Getenv("TEST_DATABASE_URL")
	if base == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	var rnd [6]byte
	_, _ = rand.Read(rnd[:])
	name := "t_mig_" + hex.EncodeToString(rnd[:])
	exec := func(sql string) {
		t.Helper()
		c, err := pgx.Connect(ctx, base)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close(ctx)
		if _, err := c.Exec(ctx, sql); err != nil {
			t.Fatal(err)
		}
	}
	exec("CREATE DATABASE " + name)
	t.Cleanup(func() { exec("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)") })
	u, _ := url.Parse(base)
	u.Path = "/" + name

	pc, err := pgxpool.ParseConfig(u.String())
	if err != nil {
		t.Fatal(err)
	}
	sqlDB := stdlib.OpenDB(*pc.ConnConfig)
	p, err := goose.NewProvider(goose.DialectPostgres, sqlDB, mustSub())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.UpTo(ctx, 1); err != nil {
		t.Fatal(err)
	}
	sqlDB.Close()

	const n = 3
	errs := make([]error, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Add(1)
		go func() { defer wg.Done(); errs[i] = Migrate(ctx, u.String(), time.Minute) }()
	}
	wg.Wait()
	for i, err := range errs {
		if err != nil {
			t.Errorf("migrate %d: %v", i, err)
		}
	}
	c, err := pgx.Connect(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close(ctx)
	var total, distinct int
	if err := c.QueryRow(ctx, "SELECT count(*), count(DISTINCT version_id) FROM goose_db_version WHERE version_id > 0").Scan(&total, &distinct); err != nil {
		t.Fatal(err)
	}
	if total != distinct {
		t.Fatalf("migrations applied more than once: %d rows for %d versions", total, distinct)
	}
}
