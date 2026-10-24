// Package db opens the connection pool and applies embedded migrations.
package db

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/lock"

	"github.com/smsgate/backend/internal/config"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Open creates a pool using the pool settings from cfg and verifies connectivity.
func Open(ctx context.Context, cfg config.Config) (*pgxpool.Pool, error) {
	pc, err := pgxpool.ParseConfig(cfg.DatabaseURL)
	if err != nil {
		return nil, fmt.Errorf("parse database url: %w", err)
	}
	pc.MaxConns = cfg.DBMaxConns
	pc.MinConns = cfg.DBMinConns
	pc.MaxConnIdleTime = cfg.DBMaxConnIdle
	pool, err := pgxpool.NewWithConfig(ctx, pc)
	if err != nil {
		return nil, fmt.Errorf("open pool: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping database: %w", err)
	}
	return pool, nil
}

// Migrate applies all embedded goose migrations to the database at url. A
// Postgres session advisory lock serialises concurrent callers, so several
// instances may start against one database at once; a waiting caller polls the
// lock every second and gives up after lockTimeout (rounded up to seconds).
func Migrate(ctx context.Context, url string, lockTimeout time.Duration) error {
	pc, err := pgxpool.ParseConfig(url)
	if err != nil {
		return fmt.Errorf("parse database url: %w", err)
	}
	sqlDB := stdlib.OpenDB(*pc.ConnConfig)
	defer sqlDB.Close()
	tries := max(uint64((lockTimeout+time.Second-1)/time.Second), 1)
	locker, err := lock.NewPostgresSessionLocker(lock.WithLockTimeout(1, tries))
	if err != nil {
		return fmt.Errorf("goose locker: %w", err)
	}
	provider, err := goose.NewProvider(goose.DialectPostgres, sqlDB, mustSub(), goose.WithSessionLocker(locker))
	if err != nil {
		return fmt.Errorf("goose provider: %w", err)
	}
	if _, err := provider.Up(ctx); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	return nil
}

func mustSub() fs.FS {
	sub, err := fs.Sub(migrations, "migrations")
	if err != nil {
		panic(err)
	}
	return sub
}
