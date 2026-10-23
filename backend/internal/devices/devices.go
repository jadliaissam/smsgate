// Package devices implements Android device pairing, registration, heartbeat,
// listing and revocation, and the RequireDevice middleware.
package devices

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
)

const (
	maxName       = 64
	maxSIMs       = 4
	maxCarrier    = 64
	maxNumber     = 32
	maxAppVersion = 32
	maxFCMToken   = 4096
)

// SIM describes one SIM slot reported by the phone.
type SIM struct {
	Slot           int    `json:"slot"`
	SubscriptionID int    `json:"subscription_id"`
	Carrier        string `json:"carrier"`
	Number         string `json:"number,omitempty"`
}

// Deps are what the device handlers need.
type Deps struct {
	Pool *pgxpool.Pool
	Q    *dbq.Queries
	Auth *accounts.Authenticator
	Cfg  config.Config
	Now  func() time.Time
	// AuthLimit (per client IP) wraps POST /device/v1/register; DeviceLimit
	// (per device, applied after RequireDevice) the other /device/v1 routes.
	AuthLimit, DeviceLimit func(http.Handler) http.Handler
}

type service struct{ Deps }

// Routes registers the session (/app), API-key (/v1) and device (/device/v1)
// endpoints.
func Routes(mux *http.ServeMux, d Deps) {
	s := &service{d}
	sess := accounts.RequireSession(d.Q, d.Cfg.SessionCookie, d.Now)
	mux.Handle("POST /app/pairing-codes", sess(handle(s.createPairingCode)))
	mux.Handle("GET /app/devices", sess(handle(s.list)))
	mux.Handle("PATCH /app/devices/{id}", sess(handle(s.rename)))
	mux.Handle("DELETE /app/devices/{id}", sess(handle(s.revoke)))

	key := accounts.RequireAPIKey(d.Q, d.Cfg.APIKeyTouchInterval, d.Now)
	mux.Handle("GET /v1/devices", key(handle(s.list)))

	authRL, devRL := d.AuthLimit, d.DeviceLimit
	if authRL == nil {
		authRL = accounts.Identity
	}
	if devRL == nil {
		devRL = accounts.Identity
	}
	mux.Handle("POST /device/v1/register", authRL(handle(s.register)))
	dev := RequireDevice(d.Q)
	mux.Handle("PUT /device/v1/fcm-token", dev(devRL(handle(s.setFCMToken))))
	mux.Handle("POST /device/v1/heartbeat", dev(devRL(handle(s.heartbeat))))
}

func handle(h httpx.HandlerFunc) http.Handler { return httpx.Handle(h) }

func isNoRows(err error) bool { return errors.Is(err, pgx.ErrNoRows) }

func invalidName() *httpx.Error {
	return httpx.Unprocessable("invalid_name", "name must be 1-64 characters")
}

func invalidSIMs() *httpx.Error {
	return httpx.Unprocessable("invalid_sims", "sims must have at most 4 entries with unique slots 0-3")
}

func validName(n string) bool {
	c := utf8.RuneCountInString(n)
	return c >= 1 && c <= maxName
}

func validSIMs(sims []SIM) bool {
	if len(sims) > maxSIMs {
		return false
	}
	var seen [maxSIMs]bool
	for _, s := range sims {
		if s.Slot < 0 || s.Slot >= maxSIMs || seen[s.Slot] ||
			utf8.RuneCountInString(s.Carrier) > maxCarrier || utf8.RuneCountInString(s.Number) > maxNumber {
			return false
		}
		seen[s.Slot] = true
	}
	return true
}

// encodeSIMs marshals sims for the jsonb column (nil becomes []).
func encodeSIMs(sims []SIM) ([]byte, error) {
	if sims == nil {
		return []byte("[]"), nil
	}
	return json.Marshal(sims)
}

func decodeSIMs(raw []byte) []SIM {
	sims := []SIM{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &sims) // column is written only by encodeSIMs
	}
	return sims
}

type deviceJSON struct {
	ID         uuid.UUID  `json:"id"`
	Name       string     `json:"name"`
	Online     bool       `json:"online"`
	SIMs       []SIM      `json:"sims"`
	Battery    *int16     `json:"battery"`
	Charging   *bool      `json:"charging"`
	LastSeenAt *time.Time `json:"last_seen_at"`
}

// normalizeCode canonicalises user-typed pairing codes: uppercase, drop spaces
// and dashes, fold Crockford look-alikes (O->0, I/L->1).
func normalizeCode(in string) string {
	b := make([]byte, 0, len(in))
	for i := 0; i < len(in); i++ {
		c := in[i]
		switch {
		case c == ' ' || c == '-' || c == '\t' || c == '\r' || c == '\n':
			continue
		case c >= 'a' && c <= 'z':
			c -= 'a' - 'A'
		}
		switch c {
		case 'O':
			c = '0'
		case 'I', 'L':
			c = '1'
		}
		b = append(b, c)
	}
	return string(b)
}
