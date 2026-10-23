// Package messages implements the public SMS API: queueing a message on a
// device (create), reading it back (get) and listing with keyset pagination.
package messages

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/push"
	"github.com/smsgate/backend/internal/sms"
)

const (
	maxIdemKey   = 255
	maxClientRef = 128
)

// Statuses is the closed set of message statuses (matches the DB CHECK).
var statuses = map[string]bool{
	"queued": true, "dispatched": true, "sent": true, "delivered": true, "failed": true, "expired": true,
}

// Message is the public JSON representation. The body is never included.
type Message struct {
	ID           uuid.UUID  `json:"id"`
	DeviceID     uuid.UUID  `json:"device_id"`
	SIMSlot      *int16     `json:"sim_slot"`
	To           string     `json:"to"`
	Status       string     `json:"status"`
	ClientRef    *string    `json:"client_ref"`
	ErrorCode    *string    `json:"error_code"`
	ErrorMessage *string    `json:"error_message"`
	CreatedAt    time.Time  `json:"created_at"`
	ExpiresAt    time.Time  `json:"expires_at"`
	DispatchedAt *time.Time `json:"dispatched_at"`
	SentAt       *time.Time `json:"sent_at"`
	DeliveredAt  *time.Time `json:"delivered_at"`
	FailedAt     *time.Time `json:"failed_at"`
}

// pubRow is a message row as the public reads select it (no body, idempotency
// key or request hash); every body-less sqlc row type converts to it.
type pubRow struct {
	ID           uuid.UUID
	UserID       uuid.UUID
	DeviceID     uuid.UUID
	SimSlot      *int16
	ToNumber     string
	Status       string
	ExpiresAt    time.Time
	ClientRef    *string
	ErrorCode    *string
	ErrorMessage *string
	CreatedAt    time.Time
	DispatchedAt *time.Time
	SentAt       *time.Time
	DeliveredAt  *time.Time
	FailedAt     *time.Time
}

func fromPub(m pubRow) Message {
	return Message{
		ID: m.ID, DeviceID: m.DeviceID, SIMSlot: m.SimSlot, To: m.ToNumber, Status: m.Status,
		ClientRef: m.ClientRef, ErrorCode: m.ErrorCode, ErrorMessage: m.ErrorMessage,
		CreatedAt: m.CreatedAt.UTC(), ExpiresAt: m.ExpiresAt.UTC(),
		DispatchedAt: utc(m.DispatchedAt), SentAt: utc(m.SentAt), DeliveredAt: utc(m.DeliveredAt), FailedAt: utc(m.FailedAt),
	}
}

// FromRow converts a database row; timestamps are rendered as UTC.
func FromRow(m dbq.Message) Message {
	return Message{
		ID: m.ID, DeviceID: m.DeviceID, SIMSlot: m.SimSlot, To: m.ToNumber, Status: m.Status,
		ClientRef: m.ClientRef, ErrorCode: m.ErrorCode, ErrorMessage: m.ErrorMessage,
		CreatedAt: m.CreatedAt.UTC(), ExpiresAt: m.ExpiresAt.UTC(),
		DispatchedAt: utc(m.DispatchedAt), SentAt: utc(m.SentAt), DeliveredAt: utc(m.DeliveredAt), FailedAt: utc(m.FailedAt),
	}
}

func utc(t *time.Time) *time.Time {
	if t == nil {
		return nil
	}
	u := t.UTC()
	return &u
}

// CreateInput is a validated-on-entry request to queue one SMS.
type CreateInput struct {
	DeviceID   uuid.UUID
	SIMSlot    *int
	To, Body   string
	TTLSeconds *int // nil = configured default; explicit values must be within bounds
	ClientRef  string
}

// Service creates messages and wakes devices.
type Service struct {
	q     *dbq.Queries
	waker push.Waker
	cfg   config.Config
	log   *slog.Logger

	// OnStatus, when set, is called synchronously after each real device status
	// transition (never for no-op retries). Set it before serving requests.
	OnStatus func(ctx context.Context, m Message)

	sem chan struct{} // bounds concurrent wake goroutines (cfg.WakeConcurrency)
	wg  sync.WaitGroup
}

// NewService builds a Service. The wake semaphore size is cfg.WakeConcurrency.
func NewService(q *dbq.Queries, waker push.Waker, cfg config.Config, log *slog.Logger) *Service {
	n := max(cfg.WakeConcurrency, 1)
	return &Service{q: q, waker: waker, cfg: cfg, log: log, sem: make(chan struct{}, n)}
}

// Wait blocks until every in-flight wake goroutine has finished. Call it on
// shutdown (and in tests before dropping the database), after the HTTP server
// has stopped accepting requests.
func (s *Service) Wait() { s.wg.Wait() }

// Create validates in and queues the message, returning created=false (and the
// original message) for an idempotent replay. Validation order, first failure
// wins: idempotency key, client_ref, device (404), to, body, ttl, sim_slot.
// All failures are *httpx.Error.
//
// The happy path is two round-trips: GetDeviceForUser (also yields the FCM
// token and SIMs) and one CTE statement that inserts the message and its
// 'queued' event. A single statement is atomic, so no explicit transaction
// (BEGIN/COMMIT would add two round-trips) is needed.
func (s *Service) Create(ctx context.Context, userID uuid.UUID, in CreateInput, idemKey string) (Message, bool, error) {
	if len(idemKey) > maxIdemKey || !utf8.ValidString(idemKey) {
		return Message{}, false, httpx.Unprocessable("invalid_idempotency_key", "Idempotency-Key must be at most 255 valid UTF-8 characters")
	}
	if utf8.RuneCountInString(in.ClientRef) > maxClientRef || !utf8.ValidString(in.ClientRef) || strings.IndexByte(in.ClientRef, 0) >= 0 {
		return Message{}, false, httpx.Unprocessable("invalid_client_ref", "client_ref must be at most 128 characters")
	}
	dev, err := s.q.GetDeviceForUser(ctx, dbq.GetDeviceForUserParams{ID: in.DeviceID, UserID: userID})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return Message{}, false, httpx.NotFound("device_not_found")
		}
		return Message{}, false, err
	}
	if !sms.ValidE164(in.To) {
		return Message{}, false, httpx.Unprocessable("invalid_to", "to must be an E.164 number such as +37060000000")
	}
	if in.Body == "" {
		return Message{}, false, httpx.Unprocessable("body_empty", "body must not be empty")
	}
	if strings.IndexByte(in.Body, 0) >= 0 {
		return Message{}, false, httpx.Unprocessable("invalid_body", "body must not contain NUL characters")
	}
	if seg, _ := sms.Segments(in.Body); seg > s.cfg.MaxSegments {
		return Message{}, false, httpx.Unprocessable("body_too_long", "body exceeds the maximum number of SMS segments")
	}
	ttl := int(s.cfg.TTLDefault / time.Second)
	// Compare in seconds: converting a huge client value to time.Duration would overflow.
	if in.TTLSeconds != nil {
		ttl = *in.TTLSeconds
		if int64(ttl) < int64(s.cfg.TTLMin/time.Second) || int64(ttl) > int64(s.cfg.TTLMax/time.Second) {
			return Message{}, false, httpx.Unprocessable("invalid_ttl", "ttl_seconds is outside the allowed range")
		}
	}
	var slot *int16
	if in.SIMSlot != nil {
		if !hasSlot(dev.Sims, *in.SIMSlot) {
			return Message{}, false, httpx.Unprocessable("invalid_sim_slot", "sim_slot is not a SIM of this device")
		}
		v := int16(*in.SIMSlot)
		slot = &v
	}

	hash := requestHash(in, ttl)
	p := dbq.InsertMessageParams{
		ID: uuid.Must(uuid.NewV7()), UserID: userID, DeviceID: in.DeviceID, SimSlot: slot,
		ToNumber: in.To, Body: &in.Body, TtlSeconds: float64(ttl), RequestHash: &hash,
	}
	if in.ClientRef != "" {
		p.ClientRef = &in.ClientRef
	}
	if idemKey != "" {
		p.IdempotencyKey = &idemKey
	}
	row, err := s.q.InsertMessage(ctx, p)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) || idemKey == "" {
			return Message{}, false, err
		}
		// Conflict on (user_id, idempotency_key): the winner is committed and
		// visible (a concurrent insert makes ON CONFLICT wait for it).
		old, err := s.q.GetMessageByIdem(ctx, dbq.GetMessageByIdemParams{UserID: userID, IdempotencyKey: &idemKey})
		if err != nil {
			return Message{}, false, err
		}
		if old.RequestHash == nil || *old.RequestHash != hash {
			return Message{}, false, httpx.Conflict("idempotency_mismatch", "Idempotency-Key was already used with a different request")
		}
		return fromPub(pubRow{
			ID: old.ID, UserID: old.UserID, DeviceID: old.DeviceID, SimSlot: old.SimSlot, ToNumber: old.ToNumber,
			Status: old.Status, ExpiresAt: old.ExpiresAt, ClientRef: old.ClientRef, ErrorCode: old.ErrorCode,
			ErrorMessage: old.ErrorMessage, CreatedAt: old.CreatedAt, DispatchedAt: old.DispatchedAt, SentAt: old.SentAt,
			DeliveredAt: old.DeliveredAt, FailedAt: old.FailedAt,
		}), false, nil
	}
	m := fromPub(pubRow(row))
	if dev.FcmToken != nil && *dev.FcmToken != "" {
		s.wake(push.WakeTarget{DeviceID: in.DeviceID, FCMToken: *dev.FcmToken, MessageID: m.ID, ExpiresAt: m.ExpiresAt})
	}
	return m, true, nil
}

// wake sends the push asynchronously. It never blocks the request: when all
// WakeConcurrency slots are busy the wake is skipped (debug-logged); the device
// still receives the message through its pending poll or a later wake.
// The goroutine uses a context detached from the request (cancelled when the
// response is written) bounded by cfg.WakeTimeout.
func (s *Service) wake(t push.WakeTarget) {
	select {
	case s.sem <- struct{}{}:
	default:
		s.log.Debug("wake skipped: concurrency limit reached", "device_id", t.DeviceID, "message_id", t.MessageID)
		return
	}
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer func() { <-s.sem }()
		ctx, cancel := context.WithTimeout(context.Background(), s.cfg.WakeTimeout)
		defer cancel()
		if err := s.waker.Wake(ctx, t); err != nil {
			s.onWakeErr(ctx, t, err)
		}
	}()
}

// onWakeErr is the seam for wake failures (never surfaced to the API caller).
// A rejected FCM token is cleared (only if still the one used) so we stop
// pushing to it; the device sets a new one on its next registration/refresh.
func (s *Service) onWakeErr(ctx context.Context, t push.WakeTarget, err error) {
	if errors.Is(err, push.ErrTokenInvalid) {
		tok := t.FCMToken
		if cerr := s.q.ClearFCMToken(ctx, dbq.ClearFCMTokenParams{ID: t.DeviceID, FcmToken: &tok}); cerr != nil {
			s.log.Warn("clear fcm token failed", "device_id", t.DeviceID, "err", cerr)
			return
		}
		s.log.Info("fcm token invalid, cleared", "device_id", t.DeviceID)
		return
	}
	s.log.Warn("wake failed", "device_id", t.DeviceID, "message_id", t.MessageID, "err", err)
}

func hasSlot(raw []byte, slot int) bool {
	var sims []struct {
		Slot int `json:"slot"`
	}
	if json.Unmarshal(raw, &sims) != nil {
		return false
	}
	for _, s := range sims {
		if s.Slot == slot {
			return true
		}
	}
	return false
}

// requestHash is sha256 over a canonical, collision-free encoding of the
// request: every string is length-prefixed, sim_slot has a presence byte, ttl
// is the effective seconds. Returned as hex.
func requestHash(in CreateInput, ttl int) string {
	h := sha256.New()
	var b [8]byte
	str := func(s string) {
		binary.BigEndian.PutUint64(b[:], uint64(len(s)))
		h.Write(b[:])
		h.Write([]byte(s))
	}
	h.Write(in.DeviceID[:])
	if in.SIMSlot == nil {
		h.Write([]byte{0})
	} else {
		h.Write([]byte{1})
		binary.BigEndian.PutUint64(b[:], uint64(*in.SIMSlot))
		h.Write(b[:])
	}
	str(in.To)
	str(in.Body)
	binary.BigEndian.PutUint64(b[:], uint64(ttl))
	h.Write(b[:])
	str(in.ClientRef)
	var sum [sha256.Size]byte
	return hex.EncodeToString(h.Sum(sum[:0]))
}
