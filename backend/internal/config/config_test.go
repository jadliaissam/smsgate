package config

import (
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	d := Default()
	d.DatabaseURL = "postgres://x"
	if c != d {
		t.Fatalf("Load != Default+URL:\n%+v\n%+v", c, d)
	}
	checks := []struct {
		name      string
		got, want any
	}{
		{"Addr", c.Addr, ":8080"},
		{"Env", c.Env, "prod"},
		{"PublicURL", c.PublicURL, "http://localhost:8080"},
		{"LogLevel", c.LogLevel, slog.LevelInfo},
		{"DBMaxConns", c.DBMaxConns, int32(10)},
		{"DBMinConns", c.DBMinConns, int32(1)},
		{"DBMaxConnIdle", c.DBMaxConnIdle, 5 * time.Minute},
		{"ReadHeaderTimeout", c.ReadHeaderTimeout, 5 * time.Second},
		{"ReadTimeout", c.ReadTimeout, 15 * time.Second},
		{"WriteTimeout", c.WriteTimeout, 15 * time.Second},
		{"IdleTimeout", c.IdleTimeout, 120 * time.Second},
		{"ShutdownTimeout", c.ShutdownTimeout, 10 * time.Second},
		{"MaxBodyBytes", c.MaxBodyBytes, int64(16384)},
		{"TTLDefault", c.TTLDefault, 300 * time.Second},
		{"TTLMin", c.TTLMin, 10 * time.Second},
		{"TTLMax", c.TTLMax, 3600 * time.Second},
		{"MaxSegments", c.MaxSegments, 3},
		{"ListLimitDefault", c.ListLimitDefault, 50},
		{"ListLimitMax", c.ListLimitMax, 200},
		{"PendingLimit", c.PendingLimit, 50},
		{"PairingTTL", c.PairingTTL, 5 * time.Minute},
		{"OnlineWindow", c.OnlineWindow, 2 * time.Minute},
		{"DeviceLostAfter", c.DeviceLostAfter, 15 * time.Minute},
		{"SweepInterval", c.SweepInterval, 2 * time.Second},
		{"SweepBatch", c.SweepBatch, 500},
		{"Argon2.MemoryKiB", c.Argon2.MemoryKiB, uint32(19456)},
		{"Argon2.Time", c.Argon2.Time, uint32(2)},
		{"Argon2.Threads", c.Argon2.Threads, uint8(1)},
		{"Argon2.MaxConcurrency", c.Argon2.MaxConcurrency, 2},
		{"PasswordMin", c.PasswordMin, 10},
		{"PasswordMax", c.PasswordMax, 128},
		{"SessionTTL", c.SessionTTL, 720 * time.Hour},
		{"SessionCookie", c.SessionCookie, "sg_session"},
		{"CookieSecure", c.CookieSecure, true},
		{"EmailTokenTTL", c.EmailTokenTTL, 24 * time.Hour},
		{"APIKeyTouchInterval", c.APIKeyTouchInterval, time.Minute},
		{"FCMCredentialsFile", c.FCMCredentialsFile, ""},
		{"FCMEndpoint", c.FCMEndpoint, "https://fcm.googleapis.com"},
		{"WakeConcurrency", c.WakeConcurrency, 64},
		{"WakeTimeout", c.WakeTimeout, 5 * time.Second},
		{"FCMMaxIdlePerHost", c.FCMMaxIdlePerHost, 16},
	}
	for _, k := range checks {
		if k.got != k.want {
			t.Errorf("%s = %v, want %v", k.name, k.got, k.want)
		}
	}
}

func TestLoadOverrides(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("MSG_TTL_MAX", "2h")
	t.Setenv("SWEEP_BATCH", "10")
	t.Setenv("COOKIE_SECURE", "false")
	t.Setenv("LOG_LEVEL", "debug")
	t.Setenv("ARGON2_THREADS", "4")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.TTLMax != 2*time.Hour || c.SweepBatch != 10 || c.CookieSecure || c.LogLevel != slog.LevelDebug || c.Argon2.Threads != 4 {
		t.Fatalf("overrides not applied: %+v", c)
	}
}

func TestDevDisablesSecureCookie(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("APP_ENV", "dev")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if c.CookieSecure {
		t.Fatal("CookieSecure should default to false in dev")
	}
	t.Setenv("COOKIE_SECURE", "true")
	c, _ = Load()
	if !c.CookieSecure {
		t.Fatal("explicit COOKIE_SECURE=true must win in dev")
	}
}

func TestLoadMissingDatabaseURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "")
	if _, err := Load(); err == nil {
		t.Fatal("expected error")
	}
}

func TestLoadInvalid(t *testing.T) {
	for _, kv := range [][2]string{
		{"SWEEP_INTERVAL", "abc"},
		{"SWEEP_BATCH", "x"},
		{"COOKIE_SECURE", "maybe"},
		{"LOG_LEVEL", "loud"},
		{"MAX_BODY_BYTES", "1.5"},
		{"ARGON2_THREADS", "999"},
	} {
		t.Run(kv[0], func(t *testing.T) {
			t.Setenv("DATABASE_URL", "postgres://x")
			t.Setenv(kv[0], kv[1])
			_, err := Load()
			if err == nil || !strings.Contains(err.Error(), kv[0]) {
				t.Fatalf("err = %v, want mention of %s", err, kv[0])
			}
		})
	}
}

func TestEnvVars(t *testing.T) {
	v := EnvVars()
	seen := map[string]bool{}
	for _, n := range v {
		if seen[n] {
			t.Errorf("duplicate %s", n)
		}
		seen[n] = true
	}
	if !slices.Contains(v, "DATABASE_URL") {
		t.Fatal("missing DATABASE_URL")
	}
	if len(v) != 42 {
		t.Fatalf("only %d vars", len(v))
	}
}
