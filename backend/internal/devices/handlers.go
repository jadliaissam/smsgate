package devices

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/secret"
)

func (s *service) createPairingCode(w http.ResponseWriter, r *http.Request) error {
	ctx := r.Context()
	uid := accounts.UserID(ctx)
	u, err := s.Q.GetUser(ctx, uid)
	if err != nil {
		return err
	}
	if err := accounts.RequireVerified(u); err != nil {
		return err
	}
	code := secret.PairingCode()
	exp := s.Now().Add(s.Cfg.PairingTTL)
	if err := s.Q.CreatePairingCode(ctx, dbq.CreatePairingCodeParams{CodeHash: secret.Hash(code), UserID: uid, ExpiresAt: exp}); err != nil {
		return err
	}
	return httpx.JSON(w, http.StatusCreated, struct {
		Code      string    `json:"code"`
		ExpiresAt time.Time `json:"expires_at"`
		QRPayload string    `json:"qr_payload"`
	}{code, exp, "smsgate://pair?server=" + url.QueryEscape(s.Cfg.PublicURL) + "&code=" + code})
}

type registerReq struct {
	PairingCode string `json:"pairing_code"`
	Email       string `json:"email"`
	Password    string `json:"password"`
	Name        string `json:"name"`
	SIMs        []SIM  `json:"sims"`
	FCMToken    string `json:"fcm_token"`
	AppVersion  string `json:"app_version"`
}

func (s *service) register(w http.ResponseWriter, r *http.Request) error {
	var in registerReq
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	in.Email = strings.TrimSpace(in.Email)
	code := normalizeCode(in.PairingCode)
	useCode := code != ""
	useLogin := in.Email != "" || in.Password != ""
	if useCode == useLogin || (useLogin && (in.Email == "" || in.Password == "")) {
		return httpx.Unprocessable("invalid_credentials_input", "provide either pairing_code or email and password")
	}
	name := strings.TrimSpace(in.Name)
	if !validName(name) {
		return invalidName()
	}
	if !validSIMs(in.SIMs) {
		return invalidSIMs()
	}
	if utf8.RuneCountInString(in.AppVersion) > maxAppVersion {
		return httpx.Unprocessable("invalid_app_version", "app_version must be at most 32 characters")
	}
	if len(in.FCMToken) > maxFCMToken {
		return httpx.Unprocessable("invalid_fcm_token", "fcm_token is too long")
	}
	sims, err := encodeSIMs(in.SIMs)
	if err != nil {
		return err
	}
	ctx := r.Context()

	// No hashing (argon2 waits on a semaphore and needs its own DB conn) may
	// happen while a transaction/conn is held, or concurrent registrations
	// deadlock the pool. So the login path authenticates before any Begin.
	var (
		uid uuid.UUID
		q   = s.Q
		tx  pgx.Tx
	)
	if useLogin {
		u, err := s.Auth.Authenticate(ctx, in.Email, in.Password)
		if err != nil {
			return err
		}
		if err := accounts.RequireVerified(u); err != nil {
			return err
		}
		uid = u.ID
	} else {
		// Consume the code and insert the device atomically.
		tx, err = s.Pool.Begin(ctx)
		if err != nil {
			return err
		}
		defer tx.Rollback(ctx) //nolint:errcheck
		q = s.Q.WithTx(tx)
		now := s.Now()
		uid, err = q.ConsumePairingCode(ctx, dbq.ConsumePairingCodeParams{CodeHash: secret.Hash(code), Now: &now})
		if err != nil {
			if isNoRows(err) {
				return httpx.BadRequest("invalid_pairing_code", "pairing code is invalid or expired")
			}
			return err
		}
	}

	token := secret.Token(tokenPrefix)
	id := uuid.Must(uuid.NewV7())
	p := dbq.CreateDeviceParams{ID: id, UserID: uid, Name: name, TokenHash: secret.Hash(token), Sims: sims}
	if in.FCMToken != "" {
		p.FcmToken = &in.FCMToken
	}
	if in.AppVersion != "" {
		p.AppVersion = &in.AppVersion
	}
	if err := q.CreateDevice(ctx, p); err != nil {
		return err
	}
	if tx != nil {
		if err := tx.Commit(ctx); err != nil {
			return err
		}
	}
	return httpx.JSON(w, http.StatusCreated, struct {
		DeviceID    uuid.UUID `json:"device_id"`
		DeviceToken string    `json:"device_token"`
	}{id, token})
}

func (s *service) setFCMToken(w http.ResponseWriter, r *http.Request) error {
	var in struct {
		FCMToken string `json:"fcm_token"`
	}
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	if in.FCMToken == "" || len(in.FCMToken) > maxFCMToken {
		return httpx.Unprocessable("invalid_fcm_token", "fcm_token must be 1-4096 characters")
	}
	if err := s.Q.UpdateFCMToken(r.Context(), dbq.UpdateFCMTokenParams{ID: FromContext(r.Context()).ID, FcmToken: &in.FCMToken}); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// heartbeatReq is the heartbeat payload, shared by POST /device/v1/heartbeat
// and the WebSocket "heartbeat" frame.
type heartbeatReq struct {
	Battery    *int16 `json:"battery"`
	Charging   *bool  `json:"charging"`
	Signal     *int16 `json:"signal"`
	SIMs       []SIM  `json:"sims"` // nil = unchanged
	AppVersion string `json:"app_version"`
}

func (s *service) heartbeat(w http.ResponseWriter, r *http.Request) error {
	var in heartbeatReq
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	serverTime, err := s.applyHeartbeat(r.Context(), FromContext(r.Context()).ID, in)
	if err != nil {
		return err
	}
	return httpx.JSON(w, http.StatusOK, struct {
		ServerTime time.Time `json:"server_time"`
	}{serverTime})
}

// applyHeartbeat validates in and records it, returning the DB clock (UTC).
// Validation failures are 422 invalid_heartbeat; a revoked device is 401.
func (s *service) applyHeartbeat(ctx context.Context, deviceID uuid.UUID, in heartbeatReq) (time.Time, error) {
	bad := func(msg string) error { return httpx.Unprocessable("invalid_heartbeat", msg) }
	if in.Battery != nil && (*in.Battery < 0 || *in.Battery > 100) {
		return time.Time{}, bad("battery must be 0-100")
	}
	if in.Signal != nil && (*in.Signal < 0 || *in.Signal > 4) {
		return time.Time{}, bad("signal must be 0-4")
	}
	if !validSIMs(in.SIMs) {
		return time.Time{}, bad("sims must have at most 4 entries with unique slots 0-3")
	}
	if utf8.RuneCountInString(in.AppVersion) > maxAppVersion {
		return time.Time{}, bad("app_version must be at most 32 characters")
	}
	p := dbq.HeartbeatParams{ID: deviceID, Battery: in.Battery, Charging: in.Charging, Signal: in.Signal}
	if in.SIMs != nil {
		b, err := encodeSIMs(in.SIMs)
		if err != nil {
			return time.Time{}, err
		}
		p.Sims = b
	}
	if in.AppVersion != "" {
		p.AppVersion = &in.AppVersion
	}
	now := s.Now()
	p.Now = &now
	serverTime, err := s.Q.Heartbeat(ctx, p)
	if err != nil {
		if isNoRows(err) { // revoked between authentication and update
			return time.Time{}, httpx.Unauthorized()
		}
		return time.Time{}, err
	}
	return serverTime.UTC(), nil
}

// onlineParams returns the cut-offs for "online": a heartbeat within the
// online window, or a WebSocket refreshed within WS_STALE_AFTER.
func (s *service) onlineParams() (heartbeatAfter, wsAfter *time.Time) {
	now := s.Now()
	h, w := now.Add(-s.Cfg.OnlineWindow), now.Add(-s.Cfg.WSStaleAfter)
	return &h, &w
}

// toJSON renders a device row; a live socket counts as seen, so last_seen_at
// is the later of the heartbeat time and the socket refresh time.
func toJSON(id uuid.UUID, name string, sims []byte, battery *int16, charging *bool, seen, ws *time.Time, online bool) deviceJSON {
	if ws != nil && (seen == nil || ws.After(*seen)) {
		seen = ws
	}
	return deviceJSON{ID: id, Name: name, Online: online, SIMs: decodeSIMs(sims), Battery: battery, Charging: charging, LastSeenAt: seen}
}

func (s *service) list(w http.ResponseWriter, r *http.Request) error {
	hb, ws := s.onlineParams()
	rows, err := s.Q.ListDevices(r.Context(), dbq.ListDevicesParams{UserID: accounts.UserID(r.Context()), OnlineAfter: hb, WsAfter: ws})
	if err != nil {
		return err
	}
	out := make([]deviceJSON, len(rows))
	for i, d := range rows {
		out[i] = toJSON(d.ID, d.Name, d.Sims, d.Battery, d.Charging, d.LastSeenAt, d.WsConnectedAt, d.Online)
	}
	return httpx.JSON(w, http.StatusOK, struct {
		Data []deviceJSON `json:"data"`
	}{out})
}

func (s *service) rename(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return notFound()
	}
	var in struct {
		Name string `json:"name"`
	}
	if err := httpx.Decode(w, r, &in, s.Cfg.MaxBodyBytes); err != nil {
		return err
	}
	name := strings.TrimSpace(in.Name)
	if !validName(name) {
		return invalidName()
	}
	hb, ws := s.onlineParams()
	d, err := s.Q.RenameDevice(r.Context(), dbq.RenameDeviceParams{ID: id, UserID: accounts.UserID(r.Context()), Name: name, OnlineAfter: hb, WsAfter: ws})
	if err != nil {
		if isNoRows(err) {
			return notFound()
		}
		return err
	}
	return httpx.JSON(w, http.StatusOK, toJSON(d.ID, d.Name, d.Sims, d.Battery, d.Charging, d.LastSeenAt, d.WsConnectedAt, d.Online))
}

func (s *service) revoke(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return notFound()
	}
	if ok, err := s.revokeDevice(r.Context(), id, accounts.UserID(r.Context())); err != nil {
		return err
	} else if !ok {
		return notFound()
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func notFound() *httpx.Error { return httpx.NotFound("device_not_found") }

// me tells the app which account and name it is paired as.
func (s *service) me(w http.ResponseWriter, r *http.Request) error {
	d := FromContext(r.Context())
	row, err := s.Q.GetDeviceIdentity(r.Context(), d.ID)
	if err != nil {
		if isNoRows(err) { // revoked between authentication and lookup
			return httpx.Unauthorized()
		}
		return err
	}
	return httpx.JSON(w, http.StatusOK, struct {
		DeviceID     uuid.UUID `json:"device_id"`
		Name         string    `json:"name"`
		AccountEmail string    `json:"account_email"`
		ServerTime   time.Time `json:"server_time"`
	}{d.ID, row.Name, row.Email, row.ServerTime.UTC()})
}

// unregister revokes the calling device (same effect as the dashboard's revoke).
func (s *service) unregister(w http.ResponseWriter, r *http.Request) error {
	d := FromContext(r.Context())
	ok, err := s.revokeDevice(r.Context(), d.ID, d.UserID)
	if err != nil {
		return err
	}
	if !ok { // already revoked concurrently
		return httpx.Unauthorized()
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// revokeDevice revokes a device owned by userID; false when it does not exist
// or is already revoked. Single place for everything revocation triggers: the
// RevokeDevice statement also clears ws_connected_at and NOTIFYs
// smsgate_revoke, so whichever instance holds the device's WebSocket sends
// {"type":"revoked"} and closes it with 4003.
func (s *service) revokeDevice(ctx context.Context, deviceID, userID uuid.UUID) (bool, error) {
	now := s.Now()
	n, err := s.Q.RevokeDevice(ctx, dbq.RevokeDeviceParams{ID: deviceID, UserID: userID, Now: &now})
	return n > 0, err
}
