// Package config reads all runtime settings from environment variables.
// It is the only package allowed to call os.Getenv.
package config

import (
	"errors"
	"fmt"
	"log/slog"
	"math"
	"net"
	"net/mail"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/smsgate/backend/internal/secret"
)

// Config holds every tunable of the service. Build it with Load.
type Config struct {
	Addr        string
	DatabaseURL string
	Env         string
	Revision    string // build revision (git sha) reported by /healthz
	PublicURL   string
	LogLevel    slog.Level

	DBMaxConns    int32
	DBMinConns    int32
	DBMaxConnIdle time.Duration

	MigrateLockTimeout time.Duration

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
	PurgeInterval time.Duration

	Argon2      secret.ArgonParams
	PasswordMin int
	PasswordMax int

	SessionTTL    time.Duration
	SessionCookie string
	CookieSecure  bool

	EmailTokenTTL       time.Duration
	ResendInterval      time.Duration // min gap between verification emails per user
	APIKeyTouchInterval time.Duration

	FCMCredentialsFile string
	FCMCredentialsJSON string // raw service-account JSON; wins over the file
	FCMEndpoint        string
	WakeConcurrency    int
	WakeTimeout        time.Duration
	FCMMaxIdlePerHost  int

	Mailer       string // "smtp" or "log"
	SMTPHost     string
	SMTPPort     int
	SMTPUsername string
	SMTPPassword string
	SMTPFrom     string
	SMTPTimeout  time.Duration

	// Rate limits (requests per minute, burst equal; 0 disables), the bound
	// on tracked keys per limiter, and the proxies whose X-Forwarded-For is
	// believed when determining the client IP.
	RLAuthPerMin   int
	RLSendPerMin   int
	RLDevicePerMin int
	RLMaxKeys      int
	TrustedProxies []netip.Prefix
}

const defaultTrustedProxies = "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.1/32"

// devPublicURL is the PUBLIC_URL default under APP_ENV=dev; elsewhere it is required.
const devPublicURL = "http://localhost:8080"

// Default returns all defaults; DatabaseURL and PublicURL are empty.
func Default() Config {
	return Config{
		Addr:     ":8080",
		Env:      "prod",
		Revision: "dev",
		LogLevel: slog.LevelInfo,

		DBMaxConns:    10,
		DBMinConns:    1,
		DBMaxConnIdle: 5 * time.Minute,

		MigrateLockTimeout: 5 * time.Minute,

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
		PurgeInterval: 10 * time.Minute,

		Argon2:      secret.ArgonParams{MemoryKiB: 19456, Time: 2, Threads: 1, MaxConcurrency: 2, MaxQueue: 32},
		PasswordMin: 10,
		PasswordMax: 128,

		SessionTTL:    720 * time.Hour,
		SessionCookie: "sg_session",
		CookieSecure:  true,

		EmailTokenTTL:       24 * time.Hour,
		ResendInterval:      time.Minute,
		APIKeyTouchInterval: time.Minute,

		FCMEndpoint:       "https://fcm.googleapis.com",
		WakeConcurrency:   64,
		WakeTimeout:       5 * time.Second,
		FCMMaxIdlePerHost: 16,

		Mailer:      "smtp",
		SMTPPort:    587,
		SMTPTimeout: 10 * time.Second,

		RLAuthPerMin:   10,
		RLSendPerMin:   60,
		RLDevicePerMin: 120,
		RLMaxKeys:      100000,
		TrustedProxies: mustPrefixes(defaultTrustedProxies),
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
	c.Revision = l.str("APP_REVISION", c.Revision)
	defPublic := c.PublicURL
	if c.Env == "dev" {
		defPublic = devPublicURL
	}
	c.PublicURL = strings.TrimSuffix(l.str("PUBLIC_URL", defPublic), "/")
	c.LogLevel = l.level("LOG_LEVEL", c.LogLevel)

	c.DBMaxConns = int32(l.intn("DB_MAX_CONNS", int64(c.DBMaxConns), math.MaxInt32))
	c.DBMinConns = int32(l.intn("DB_MIN_CONNS", int64(c.DBMinConns), math.MaxInt32))
	c.DBMaxConnIdle = l.dur("DB_MAX_CONN_IDLE_TIME", c.DBMaxConnIdle)
	c.MigrateLockTimeout = l.dur("MIGRATE_LOCK_TIMEOUT", c.MigrateLockTimeout)

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
	c.PurgeInterval = l.dur("PURGE_INTERVAL", c.PurgeInterval)

	c.Argon2.MemoryKiB = uint32(l.intn("ARGON2_MEMORY_KIB", int64(c.Argon2.MemoryKiB), math.MaxUint32))
	c.Argon2.Time = uint32(l.intn("ARGON2_TIME", int64(c.Argon2.Time), math.MaxUint32))
	c.Argon2.Threads = uint8(l.intn("ARGON2_THREADS", int64(c.Argon2.Threads), math.MaxUint8))
	c.Argon2.MaxConcurrency = l.integer("ARGON2_MAX_CONCURRENCY", c.Argon2.MaxConcurrency)
	c.Argon2.MaxQueue = l.integer("ARGON2_MAX_QUEUE", c.Argon2.MaxQueue)
	c.PasswordMin = l.integer("PASSWORD_MIN_LEN", c.PasswordMin)
	c.PasswordMax = l.integer("PASSWORD_MAX_LEN", c.PasswordMax)

	c.SessionTTL = l.dur("SESSION_TTL", c.SessionTTL)
	c.SessionCookie = l.str("SESSION_COOKIE_NAME", c.SessionCookie)
	c.CookieSecure = l.boolean("COOKIE_SECURE", c.Env != "dev")

	c.EmailTokenTTL = l.dur("EMAIL_TOKEN_TTL", c.EmailTokenTTL)
	c.ResendInterval = l.dur("RESEND_VERIFICATION_INTERVAL", c.ResendInterval)
	c.APIKeyTouchInterval = l.dur("APIKEY_TOUCH_INTERVAL", c.APIKeyTouchInterval)

	c.FCMCredentialsFile = l.str("FCM_CREDENTIALS_FILE", c.FCMCredentialsFile)
	c.FCMCredentialsJSON = l.str("FCM_CREDENTIALS_JSON", c.FCMCredentialsJSON)
	c.FCMEndpoint = l.str("FCM_ENDPOINT", c.FCMEndpoint)
	c.WakeConcurrency = l.integer("WAKE_CONCURRENCY", c.WakeConcurrency)
	c.WakeTimeout = l.dur("WAKE_TIMEOUT", c.WakeTimeout)
	c.FCMMaxIdlePerHost = l.integer("FCM_HTTP_MAX_IDLE_PER_HOST", c.FCMMaxIdlePerHost)

	defMailer := c.Mailer
	if c.Env == "dev" {
		defMailer = "log"
	}
	c.Mailer = l.str("MAILER", defMailer)
	c.SMTPHost = l.str("SMTP_HOST", c.SMTPHost)
	c.SMTPPort = l.integer("SMTP_PORT", c.SMTPPort)
	c.SMTPUsername = l.str("SMTP_USERNAME", c.SMTPUsername)
	c.SMTPPassword = l.str("SMTP_PASSWORD", c.SMTPPassword)
	c.SMTPFrom = l.str("SMTP_FROM", c.SMTPFrom)
	c.SMTPTimeout = l.dur("SMTP_TIMEOUT", c.SMTPTimeout)

	c.RLAuthPerMin = l.integer("RL_AUTH_PER_MIN", c.RLAuthPerMin)
	c.RLSendPerMin = l.integer("RL_SEND_PER_MIN", c.RLSendPerMin)
	c.RLDevicePerMin = l.integer("RL_DEVICE_PER_MIN", c.RLDevicePerMin)
	c.RLMaxKeys = l.integer("RL_MAX_KEYS", c.RLMaxKeys)
	c.TrustedProxies = l.prefixes("TRUSTED_PROXIES", c.TrustedProxies)

	if l.record {
		return c, l.err
	}
	if c.DatabaseURL == "" && l.err == nil {
		l.err = errors.New("DATABASE_URL is required")
	}
	if l.err == nil {
		if err := c.Validate(); err != nil {
			l.err = err
		}
	}
	if l.err != nil {
		return Config{}, l.err
	}
	return c, nil
}

// Validate checks cross-field and range constraints. Errors name the env var.
func (c Config) Validate() error {
	var errs []error
	min1 := func(env string, v int64) {
		if v < 1 {
			errs = append(errs, fmt.Errorf("%s: must be >= 1, got %d", env, v))
		}
	}
	pos := func(env string, v time.Duration) {
		if v <= 0 {
			errs = append(errs, fmt.Errorf("%s: must be > 0, got %s", env, v))
		}
	}
	min1("DB_MAX_CONNS", int64(c.DBMaxConns))
	min1("DB_MIN_CONNS", int64(c.DBMinConns))
	if c.DBMinConns > c.DBMaxConns {
		errs = append(errs, fmt.Errorf("DB_MIN_CONNS: %d exceeds DB_MAX_CONNS %d", c.DBMinConns, c.DBMaxConns))
	}
	min1("SWEEP_BATCH", int64(c.SweepBatch))
	min1("PENDING_LIMIT", int64(c.PendingLimit))
	min1("LIST_LIMIT_DEFAULT", int64(c.ListLimitDefault))
	min1("LIST_LIMIT_MAX", int64(c.ListLimitMax))
	if c.ListLimitDefault > c.ListLimitMax {
		errs = append(errs, fmt.Errorf("LIST_LIMIT_DEFAULT: %d exceeds LIST_LIMIT_MAX %d", c.ListLimitDefault, c.ListLimitMax))
	}
	min1("WAKE_CONCURRENCY", int64(c.WakeConcurrency))
	min1("FCM_HTTP_MAX_IDLE_PER_HOST", int64(c.FCMMaxIdlePerHost))
	min1("MSG_MAX_SEGMENTS", int64(c.MaxSegments))
	min1("RL_MAX_KEYS", int64(c.RLMaxKeys))
	if c.Argon2.MemoryKiB < 8 {
		errs = append(errs, fmt.Errorf("ARGON2_MEMORY_KIB: must be >= 8, got %d", c.Argon2.MemoryKiB))
	}
	min1("ARGON2_TIME", int64(c.Argon2.Time))
	min1("ARGON2_THREADS", int64(c.Argon2.Threads))
	min1("ARGON2_MAX_CONCURRENCY", int64(c.Argon2.MaxConcurrency))
	min1("PASSWORD_MIN_LEN", int64(c.PasswordMin))
	if c.PasswordMax < c.PasswordMin {
		errs = append(errs, fmt.Errorf("PASSWORD_MAX_LEN: %d is below PASSWORD_MIN_LEN %d", c.PasswordMax, c.PasswordMin))
	}
	if c.MaxBodyBytes < 1024 {
		errs = append(errs, fmt.Errorf("MAX_BODY_BYTES: must be >= 1024, got %d", c.MaxBodyBytes))
	}

	pos("DB_MAX_CONN_IDLE_TIME", c.DBMaxConnIdle)
	pos("MIGRATE_LOCK_TIMEOUT", c.MigrateLockTimeout)
	pos("HTTP_READ_HEADER_TIMEOUT", c.ReadHeaderTimeout)
	pos("HTTP_READ_TIMEOUT", c.ReadTimeout)
	pos("HTTP_WRITE_TIMEOUT", c.WriteTimeout)
	pos("HTTP_IDLE_TIMEOUT", c.IdleTimeout)
	pos("SHUTDOWN_TIMEOUT", c.ShutdownTimeout)
	pos("MSG_TTL_DEFAULT", c.TTLDefault)
	pos("MSG_TTL_MIN", c.TTLMin)
	pos("MSG_TTL_MAX", c.TTLMax)
	pos("PAIRING_CODE_TTL", c.PairingTTL)
	pos("DEVICE_ONLINE_WINDOW", c.OnlineWindow)
	pos("DEVICE_LOST_AFTER", c.DeviceLostAfter)
	pos("SWEEP_INTERVAL", c.SweepInterval)
	pos("PURGE_INTERVAL", c.PurgeInterval)
	pos("SESSION_TTL", c.SessionTTL)
	pos("EMAIL_TOKEN_TTL", c.EmailTokenTTL)
	pos("RESEND_VERIFICATION_INTERVAL", c.ResendInterval)
	pos("APIKEY_TOUCH_INTERVAL", c.APIKeyTouchInterval)
	pos("WAKE_TIMEOUT", c.WakeTimeout)
	pos("SMTP_TIMEOUT", c.SMTPTimeout)
	if c.TTLMin > c.TTLDefault {
		errs = append(errs, fmt.Errorf("MSG_TTL_MIN: %s exceeds MSG_TTL_DEFAULT %s", c.TTLMin, c.TTLDefault))
	}
	if c.TTLDefault > c.TTLMax {
		errs = append(errs, fmt.Errorf("MSG_TTL_DEFAULT: %s exceeds MSG_TTL_MAX %s", c.TTLDefault, c.TTLMax))
	}

	if err := validPublicURL(c.PublicURL); err != nil {
		errs = append(errs, fmt.Errorf("PUBLIC_URL: %w", err))
	} else if c.PublicURL == "" && c.Env != "dev" {
		errs = append(errs, errors.New("PUBLIC_URL: required outside APP_ENV=dev (the web app's https origin)"))
	}

	switch c.Mailer {
	case "smtp":
		if c.SMTPHost == "" {
			errs = append(errs, errors.New("SMTP_HOST: required when MAILER=smtp"))
		}
		if c.SMTPFrom == "" {
			errs = append(errs, errors.New("SMTP_FROM: required when MAILER=smtp"))
		} else if _, err := mail.ParseAddress(c.SMTPFrom); err != nil {
			errs = append(errs, fmt.Errorf("SMTP_FROM: %w", err))
		}
		if c.SMTPPort < 1 || c.SMTPPort > 65535 {
			errs = append(errs, fmt.Errorf("SMTP_PORT: must be 1..65535, got %d", c.SMTPPort))
		}
	case "log":
	default:
		errs = append(errs, fmt.Errorf("MAILER: must be smtp or log, got %q", c.Mailer))
	}
	return errors.Join(errs...)
}

// validPublicURL accepts "" (checked separately) or an absolute http(s) URL
// with a host and no user info, query, fragment or trailing slash.
func validPublicURL(s string) error {
	if s == "" {
		return nil
	}
	u, err := url.Parse(s)
	if err != nil {
		return err
	}
	switch {
	case !strings.EqualFold(u.Scheme, "http") && !strings.EqualFold(u.Scheme, "https"):
		return fmt.Errorf("%q: scheme must be http or https", s)
	case u.Host == "":
		return fmt.Errorf("%q: host is missing", s)
	case u.User != nil:
		return fmt.Errorf("%q: user info is not allowed", s)
	case u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || strings.ContainsAny(s, "?#"):
		return fmt.Errorf("%q: query and fragment are not allowed", s)
	case strings.HasSuffix(s, "/"):
		return fmt.Errorf("%q: must not end with /", s)
	}
	return nil
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

// prefixes parses a comma-separated list of CIDRs or bare IPs (a single
// address); "none" means an empty list.
func (l *loader) prefixes(key string, def []netip.Prefix) []netip.Prefix {
	v, ok := l.lookup(key)
	if !ok {
		return def
	}
	if strings.TrimSpace(v) == "none" {
		return []netip.Prefix{}
	}
	ps, err := parsePrefixes(v)
	if err != nil {
		l.fail(key, err)
		return def
	}
	return ps
}

func parsePrefixes(v string) ([]netip.Prefix, error) {
	var out []netip.Prefix
	for _, f := range strings.Split(v, ",") {
		f = strings.TrimSpace(f)
		if f == "" {
			continue
		}
		if !strings.Contains(f, "/") {
			a, err := netip.ParseAddr(f)
			if err != nil {
				return nil, err
			}
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			continue
		}
		p, err := netip.ParsePrefix(f)
		if err != nil {
			return nil, err
		}
		out = append(out, p.Masked())
	}
	return out, nil
}

func mustPrefixes(v string) []netip.Prefix {
	ps, err := parsePrefixes(v)
	if err != nil {
		panic(err)
	}
	return ps
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

// HealthURL returns the loopback URL of /healthz derived from HTTP_ADDR only,
// so the -healthcheck probe works without the rest of the configuration.
func HealthURL() string { return healthURL(os.Getenv("HTTP_ADDR")) }

func healthURL(addr string) string {
	if addr == "" {
		addr = Default().Addr
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "http://127.0.0.1:8080/healthz"
	}
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = "127.0.0.1"
	}
	return "http://" + net.JoinHostPort(host, port) + "/healthz"
}
