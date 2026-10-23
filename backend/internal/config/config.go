// Package config reads all runtime settings from environment variables.
// It is the only package allowed to call os.Getenv.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"math"
	"os"
	"strconv"
	"time"

	"github.com/smsgate/backend/internal/secret"
)

// Config holds every tunable of the service. Build it with Load.
type Config struct {
	Addr        string
	DatabaseURL string
	Env         string
	PublicURL   string
	LogLevel    slog.Level

	DBMaxConns    int32
	DBMinConns    int32
	DBMaxConnIdle time.Duration

	ReadHeaderTimeout time.Duration
	ReadTimeout       time.Duration
	WriteTimeout      time.Duration
	IdleTimeout       time.Duration
	ShutdownTimeout   time.Duration
	MaxBodyBytes      int64

	TTLDefault  time.Duration
	TTLMin      time.Duration
	TTLMax      time.Duration
	MaxSegments int

	ListLimitDefault int
	ListLimitMax     int
	PendingLimit     int

	PairingTTL      time.Duration
	OnlineWindow    time.Duration
	DeviceLostAfter time.Duration

	SweepInterval time.Duration
	SweepBatch    int

	Argon2      secret.ArgonParams
	PasswordMin int
	PasswordMax int

	SessionTTL    time.Duration
	SessionCookie string
	CookieSecure  bool

	EmailTokenTTL       time.Duration
	APIKeyTouchInterval time.Duration

	FCMCredentialsFile string
	FCMEndpoint        string
	WakeConcurrency    int
	WakeTimeout        time.Duration
	FCMMaxIdlePerHost  int
}

// Default returns all defaults; DatabaseURL is empty.
func Default() Config {
	return Config{
		Addr:      ":8080",
		Env:       "prod",
		PublicURL: "http://localhost:8080",
		LogLevel:  slog.LevelInfo,

		DBMaxConns:    10,
		DBMinConns:    1,
		DBMaxConnIdle: 5 * time.Minute,

		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       120 * time.Second,
		ShutdownTimeout:   10 * time.Second,
		MaxBodyBytes:      16384,

		TTLDefault:  300 * time.Second,
		TTLMin:      10 * time.Second,
		TTLMax:      3600 * time.Second,
		MaxSegments: 3,

		ListLimitDefault: 50,
		ListLimitMax:     200,
		PendingLimit:     50,

		PairingTTL:      5 * time.Minute,
		OnlineWindow:    2 * time.Minute,
		DeviceLostAfter: 15 * time.Minute,

		SweepInterval: 2 * time.Second,
		SweepBatch:    500,

		Argon2:      secret.ArgonParams{MemoryKiB: 19456, Time: 2, Threads: 1, MaxConcurrency: 2},
		PasswordMin: 10,
		PasswordMax: 128,

		SessionTTL:    720 * time.Hour,
		SessionCookie: "sg_session",
		CookieSecure:  true,

		EmailTokenTTL:       24 * time.Hour,
		APIKeyTouchInterval: time.Minute,

		FCMEndpoint:       "https://fcm.googleapis.com",
		WakeConcurrency:   64,
		WakeTimeout:       5 * time.Second,
		FCMMaxIdlePerHost: 16,
	}
}

// Load returns Default() overlaid with the process environment. Empty
// variables count as unset. The first invalid value is reported by name.
func Load() (Config, error) {
	l := loader{get: os.Getenv}
	return l.config()
}

// EnvVars lists every environment variable name Load reads.
func EnvVars() []string {
	l := loader{get: func(string) string { return "" }, record: true}
	_, _ = l.config()
	return l.names
}

type loader struct {
	get    func(string) string
	record bool
	names  []string
	err    error
}

func (l *loader) config() (Config, error) {
	c := Default()
	c.Addr = l.str("HTTP_ADDR", c.Addr)
	c.DatabaseURL = l.str("DATABASE_URL", "")
	c.Env = l.str("APP_ENV", c.Env)
	c.PublicURL = l.str("PUBLIC_URL", c.PublicURL)
	c.LogLevel = l.level("LOG_LEVEL", c.LogLevel)

	c.DBMaxConns = int32(l.intn("DB_MAX_CONNS", int64(c.DBMaxConns), math.MaxInt32))
	c.DBMinConns = int32(l.intn("DB_MIN_CONNS", int64(c.DBMinConns), math.MaxInt32))
	c.DBMaxConnIdle = l.dur("DB_MAX_CONN_IDLE_TIME", c.DBMaxConnIdle)

	c.ReadHeaderTimeout = l.dur("HTTP_READ_HEADER_TIMEOUT", c.ReadHeaderTimeout)
	c.ReadTimeout = l.dur("HTTP_READ_TIMEOUT", c.ReadTimeout)
	c.WriteTimeout = l.dur("HTTP_WRITE_TIMEOUT", c.WriteTimeout)
	c.IdleTimeout = l.dur("HTTP_IDLE_TIMEOUT", c.IdleTimeout)
	c.ShutdownTimeout = l.dur("SHUTDOWN_TIMEOUT", c.ShutdownTimeout)
	c.MaxBodyBytes = l.intn("MAX_BODY_BYTES", c.MaxBodyBytes, math.MaxInt64)

	c.TTLDefault = l.dur("MSG_TTL_DEFAULT", c.TTLDefault)
	c.TTLMin = l.dur("MSG_TTL_MIN", c.TTLMin)
	c.TTLMax = l.dur("MSG_TTL_MAX", c.TTLMax)
	c.MaxSegments = l.integer("MSG_MAX_SEGMENTS", c.MaxSegments)

	c.ListLimitDefault = l.integer("LIST_LIMIT_DEFAULT", c.ListLimitDefault)
	c.ListLimitMax = l.integer("LIST_LIMIT_MAX", c.ListLimitMax)
	c.PendingLimit = l.integer("PENDING_LIMIT", c.PendingLimit)

	c.PairingTTL = l.dur("PAIRING_CODE_TTL", c.PairingTTL)
	c.OnlineWindow = l.dur("DEVICE_ONLINE_WINDOW", c.OnlineWindow)
	c.DeviceLostAfter = l.dur("DEVICE_LOST_AFTER", c.DeviceLostAfter)

	c.SweepInterval = l.dur("SWEEP_INTERVAL", c.SweepInterval)
	c.SweepBatch = l.integer("SWEEP_BATCH", c.SweepBatch)

	c.Argon2.MemoryKiB = uint32(l.intn("ARGON2_MEMORY_KIB", int64(c.Argon2.MemoryKiB), math.MaxUint32))
	c.Argon2.Time = uint32(l.intn("ARGON2_TIME", int64(c.Argon2.Time), math.MaxUint32))
	c.Argon2.Threads = uint8(l.intn("ARGON2_THREADS", int64(c.Argon2.Threads), math.MaxUint8))
	c.Argon2.MaxConcurrency = l.integer("ARGON2_MAX_CONCURRENCY", c.Argon2.MaxConcurrency)
	c.PasswordMin = l.integer("PASSWORD_MIN_LEN", c.PasswordMin)
	c.PasswordMax = l.integer("PASSWORD_MAX_LEN", c.PasswordMax)

	c.SessionTTL = l.dur("SESSION_TTL", c.SessionTTL)
	c.SessionCookie = l.str("SESSION_COOKIE_NAME", c.SessionCookie)
	c.CookieSecure = l.boolean("COOKIE_SECURE", c.Env != "dev")

	c.EmailTokenTTL = l.dur("EMAIL_TOKEN_TTL", c.EmailTokenTTL)
	c.APIKeyTouchInterval = l.dur("APIKEY_TOUCH_INTERVAL", c.APIKeyTouchInterval)

	c.FCMCredentialsFile = l.str("FCM_CREDENTIALS_FILE", c.FCMCredentialsFile)
	c.FCMEndpoint = l.str("FCM_ENDPOINT", c.FCMEndpoint)
	c.WakeConcurrency = l.integer("WAKE_CONCURRENCY", c.WakeConcurrency)
	c.WakeTimeout = l.dur("WAKE_TIMEOUT", c.WakeTimeout)
	c.FCMMaxIdlePerHost = l.integer("FCM_HTTP_MAX_IDLE_PER_HOST", c.FCMMaxIdlePerHost)

	if l.record {
		return c, l.err
	}
	if c.DatabaseURL == "" && l.err == nil {
		l.err = errors.New("DATABASE_URL is required")
	}
	if l.err != nil {
		return Config{}, l.err
	}
	return c, nil
}

// lookup returns the trimmed-free raw value; empty means unset.
func (l *loader) lookup(key string) (string, bool) {
	if l.record {
		l.names = append(l.names, key)
	}
	v := l.get(key)
	return v, v != ""
}

func (l *loader) fail(key string, err error) {
	if l.err == nil {
		l.err = fmt.Errorf("%s: %w", key, err)
	}
}

func (l *loader) str(key, def string) string {
	if v, ok := l.lookup(key); ok {
		return v
	}
	return def
}

func (l *loader) dur(key string, def time.Duration) time.Duration {
	v, ok := l.lookup(key)
	if !ok {
		return def
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		l.fail(key, err)
		return def
	}
	return d
}

// intn parses a non-negative-or-negative int64 and rejects values above max.
func (l *loader) intn(key string, def, max int64) int64 {
	v, ok := l.lookup(key)
	if !ok {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err == nil && (n > max || n < 0) {
		err = fmt.Errorf("value %d out of range 0..%d", n, max)
	}
	if err != nil {
		l.fail(key, err)
		return def
	}
	return n
}

func (l *loader) integer(key string, def int) int {
	return int(l.intn(key, int64(def), math.MaxInt32))
}

func (l *loader) boolean(key string, def bool) bool {
	v, ok := l.lookup(key)
	if !ok {
		return def
	}
	b, err := strconv.ParseBool(v)
	if err != nil {
		l.fail(key, err)
		return def
	}
	return b
}

func (l *loader) level(key string, def slog.Level) slog.Level {
	v, ok := l.lookup(key)
	if !ok {
		return def
	}
	var lv slog.Level
	if err := lv.UnmarshalText([]byte(v)); err != nil {
		l.fail(key, err)
		return def
	}
	return lv
}
