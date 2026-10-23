package config

import (
	"log/slog"
	"os"
	"slices"
	"strings"
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("MAILER", "log")
	t.Setenv("PUBLIC_URL", "https://sms.example.com")
	c, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	d := Default()
	d.DatabaseURL = "postgres://x"
	d.Mailer = "log"
	d.PublicURL = "https://sms.example.com"
	if c != d {
		t.Fatalf("Load != Default+URL:\n%+v\n%+v", c, d)
	}
	checks := []struct {
		name      string
		got, want any
	}{
		{"Addr", c.Addr, ":8080"},
		{"Env", c.Env, "prod"},
		{"PublicURL", c.PublicURL, "https://sms.example.com"},
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
		{"Argon2.MaxQueue", c.Argon2.MaxQueue, 32},
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
	t.Setenv("PUBLIC_URL", "https://sms.example.com")
	t.Setenv("MAILER", "log")
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
	if len(v) != 50 {
		t.Fatalf("only %d vars", len(v))
	}
}

func TestValidate(t *testing.T) {
	cases := []struct {
		env string
		mut func(*Config)
	}{
		{"DB_MAX_CONNS", func(c *Config) { c.DBMaxConns = 0 }},
		{"DB_MIN_CONNS", func(c *Config) { c.DBMinConns = 20 }},
		{"SWEEP_BATCH", func(c *Config) { c.SweepBatch = 0 }},
		{"PENDING_LIMIT", func(c *Config) { c.PendingLimit = 0 }},
		{"LIST_LIMIT_DEFAULT", func(c *Config) { c.ListLimitDefault = 0 }},
		{"LIST_LIMIT_MAX", func(c *Config) { c.ListLimitMax = 0 }},
		{"WAKE_CONCURRENCY", func(c *Config) { c.WakeConcurrency = 0 }},
		{"MSG_MAX_SEGMENTS", func(c *Config) { c.MaxSegments = 0 }},
		{"ARGON2_MEMORY_KIB", func(c *Config) { c.Argon2.MemoryKiB = 7 }},
		{"ARGON2_TIME", func(c *Config) { c.Argon2.Time = 0 }},
		{"ARGON2_THREADS", func(c *Config) { c.Argon2.Threads = 0 }},
		{"ARGON2_MAX_CONCURRENCY", func(c *Config) { c.Argon2.MaxConcurrency = 0 }},
		{"PASSWORD_MIN_LEN", func(c *Config) { c.PasswordMin = 0 }},
		{"PASSWORD_MAX_LEN", func(c *Config) { c.PasswordMax = 5 }},
		{"MAX_BODY_BYTES", func(c *Config) { c.MaxBodyBytes = 1023 }},
		{"HTTP_READ_TIMEOUT", func(c *Config) { c.ReadTimeout = 0 }},
		{"SHUTDOWN_TIMEOUT", func(c *Config) { c.ShutdownTimeout = 0 }},
		{"SWEEP_INTERVAL", func(c *Config) { c.SweepInterval = 0 }},
		{"SESSION_TTL", func(c *Config) { c.SessionTTL = 0 }},
		{"MSG_TTL_MIN", func(c *Config) { c.TTLMin = time.Hour }},
		{"MSG_TTL_DEFAULT", func(c *Config) { c.TTLDefault = 2 * time.Hour }},
		{"MAILER", func(c *Config) { c.Mailer = "pigeon" }},
		{"SMTP_HOST", func(c *Config) { c.Mailer = "smtp"; c.SMTPHost = ""; c.SMTPFrom = "a@b" }},
		{"SMTP_FROM", func(c *Config) { c.Mailer = "smtp"; c.SMTPHost = "h"; c.SMTPFrom = "" }},
		{"SMTP_PORT", func(c *Config) { c.Mailer = "smtp"; c.SMTPHost = "h"; c.SMTPFrom = "a@b"; c.SMTPPort = 70000 }},
		{"PUBLIC_URL", func(c *Config) { c.PublicURL = "" }},
	}
	for _, tc := range cases {
		t.Run(tc.env, func(t *testing.T) {
			c := Default()
			c.Mailer = "log"
			c.PublicURL = "https://sms.example.com"
			tc.mut(&c)
			err := c.Validate()
			if err == nil || !strings.Contains(err.Error(), tc.env) {
				t.Fatalf("err = %v, want mention of %s", err, tc.env)
			}
		})
	}
	c := Default()
	c.Mailer = "log"
	c.PublicURL = "https://sms.example.com"
	if err := c.Validate(); err != nil {
		t.Fatalf("defaults invalid: %v", err)
	}
}

func TestLoadRunsValidate(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("MAILER", "log")
	t.Setenv("SWEEP_BATCH", "0")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "SWEEP_BATCH") {
		t.Fatalf("err = %v", err)
	}
}

func TestMailerDefaults(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "SMTP_HOST") {
		t.Fatalf("prod default smtp without host: err = %v", err)
	}
	t.Setenv("APP_ENV", "dev")
	c, err := Load()
	if err != nil || c.Mailer != "log" {
		t.Fatalf("dev: %v %q", err, c.Mailer)
	}
	t.Setenv("APP_ENV", "prod")
	t.Setenv("MAILER", "log")
	t.Setenv("PUBLIC_URL", "https://sms.example.com")
	if c, err = Load(); err != nil || c.Mailer != "log" {
		t.Fatalf("explicit log in prod: %v", err)
	}
	t.Setenv("MAILER", "smtp")
	t.Setenv("SMTP_HOST", "mail.example.com")
	t.Setenv("SMTP_FROM", "SMSGate <no-reply@example.com>")
	if c, err = Load(); err != nil || c.SMTPPort != 587 || c.SMTPHost != "mail.example.com" {
		t.Fatalf("smtp: %v %+v", err, c)
	}
}

func TestEnvExampleComplete(t *testing.T) {
	data, err := os.ReadFile("../../.env.example")
	if err != nil {
		t.Fatal(err)
	}
	have := map[string]bool{}
	for _, line := range strings.Split(string(data), "\n") {
		line = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(line), "#"))
		if k, _, ok := strings.Cut(line, "="); ok && k != "" && !strings.ContainsAny(k, " \t") {
			have[k] = true
		}
	}
	for _, n := range EnvVars() {
		if !have[n] {
			t.Errorf(".env.example is missing %s", n)
		}
	}
}

func TestHealthURL(t *testing.T) {
	for in, want := range map[string]string{
		"":              "http://127.0.0.1:8080/healthz",
		":9000":         "http://127.0.0.1:9000/healthz",
		"0.0.0.0:80":    "http://127.0.0.1:80/healthz",
		"[::]:7000":     "http://127.0.0.1:7000/healthz",
		"10.1.2.3:8081": "http://10.1.2.3:8081/healthz",
		"garbage":       "http://127.0.0.1:8080/healthz",
	} {
		if got := healthURL(in); got != want {
			t.Errorf("healthURL(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestPublicURL(t *testing.T) {
	t.Setenv("DATABASE_URL", "postgres://x")
	t.Setenv("MAILER", "log")
	if _, err := Load(); err == nil || !strings.Contains(err.Error(), "PUBLIC_URL") {
		t.Fatalf("prod without PUBLIC_URL: err = %v", err)
	}
	t.Setenv("APP_ENV", "dev")
	if c, err := Load(); err != nil || c.PublicURL != "http://localhost:8080" {
		t.Fatalf("dev default: %q %v", c.PublicURL, err)
	}
	t.Setenv("APP_ENV", "prod")
	for in, want := range map[string]string{
		"https://sms.example.com":  "https://sms.example.com",
		"https://sms.example.com/": "https://sms.example.com",
		"https://example.com/sms/": "https://example.com/sms",
		"http://10.0.0.5:8080":     "http://10.0.0.5:8080",
		"HTTPS://Sms.Example.com/": "HTTPS://Sms.Example.com",
	} {
		t.Setenv("PUBLIC_URL", in)
		c, err := Load()
		if err != nil || c.PublicURL != want {
			t.Errorf("%q: got %q, %v; want %q", in, c.PublicURL, err, want)
		}
	}
	for _, bad := range []string{
		"sms.example.com", "ftp://sms.example.com", "https://", "https:///path",
		"https://sms.example.com/?x=1", "https://sms.example.com/#frag", "https://sms.example.com//",
		"://nope", "https://user:pw@sms.example.com",
	} {
		t.Setenv("PUBLIC_URL", bad)
		if _, err := Load(); err == nil || !strings.Contains(err.Error(), "PUBLIC_URL") {
			t.Errorf("%q accepted: %v", bad, err)
		}
	}
}
