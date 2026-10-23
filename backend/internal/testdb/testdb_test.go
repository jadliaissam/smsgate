package testdb_test

import (
	"context"
	"fmt"
	"testing"

	"github.com/smsgate/backend/internal/testdb"
)

func TestNewGivesMigratedIsolatedDB(t *testing.T) {
	ctx := context.Background()
	a := testdb.New(t)
	b := testdb.New(t)
	var n int
	if err := a.QueryRow(ctx, "SELECT count(*) FROM plans").Scan(&n); err != nil || n != 1 {
		t.Fatalf("a plans: %d %v", n, err)
	}
	if err := b.QueryRow(ctx, "SELECT count(*) FROM plans").Scan(&n); err != nil || n != 1 {
		t.Fatalf("b plans: %d %v", n, err)
	}
	if _, err := a.Exec(ctx, "INSERT INTO users (id,email,password_hash) VALUES (gen_random_uuid(),'a@b.c','x')"); err != nil {
		t.Fatal(err)
	}
	if err := b.QueryRow(ctx, "SELECT count(*) FROM users").Scan(&n); err != nil || n != 0 {
		t.Fatalf("b users: %d %v", n, err)
	}
}

func TestParallelClones(t *testing.T) {
	for i := range 4 {
		t.Run(fmt.Sprint("p", i), func(t *testing.T) {
			t.Parallel()
			p := testdb.New(t)
			var n int
			if err := p.QueryRow(context.Background(), "SELECT 1").Scan(&n); err != nil {
				t.Fatal(err)
			}
		})
	}
}
