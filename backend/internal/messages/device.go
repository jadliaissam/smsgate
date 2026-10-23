package messages

import (
	"errors"
	"net/http"
	"regexp"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/devices"
	"github.com/smsgate/backend/internal/httpx"
)

const (
	maxErrorCode    = 64
	maxErrorMessage = 256
)

var errorCodeRe = regexp.MustCompile(`^[a-z0-9_]+$`)

// allowedFrom lists, per reported status, the current statuses it may follow.
// delivered may follow dispatched because the delivery report can overtake the
// sent report.
var allowedFrom = map[string][]string{
	"sent":      {"dispatched"},
	"delivered": {"dispatched", "sent"},
	"failed":    {"dispatched", "sent"},
}

// DeviceRoutes registers the device API; auth must authenticate device tokens
// (devices.RequireDevice).
func DeviceRoutes(mux *http.ServeMux, s *Service, auth func(http.Handler) http.Handler) {
	mux.Handle("GET /device/v1/messages/pending", auth(httpx.Handle(s.pending)))
	mux.Handle("POST /device/v1/messages/{id}/claim", auth(httpx.Handle(s.claim)))
	mux.Handle("POST /device/v1/messages/{id}/status", auth(httpx.Handle(s.status)))
}

type pendingItem struct {
	ID        uuid.UUID `json:"id"`
	ExpiresAt time.Time `json:"expires_at"`
}

type pendingResp struct {
	ServerTime time.Time     `json:"server_time"`
	Messages   []pendingItem `json:"messages"`
}

func (s *Service) pending(w http.ResponseWriter, r *http.Request) error {
	dev := devices.FromContext(r.Context())
	row, err := s.q.ListPending(r.Context(), dbq.ListPendingParams{DeviceID: dev.ID, RowLimit: int32(s.cfg.PendingLimit)})
	if err != nil {
		return err
	}
	out := pendingResp{ServerTime: row.ServerTime.UTC(), Messages: make([]pendingItem, len(row.Ids))}
	for i, id := range row.Ids {
		out.Messages[i] = pendingItem{ID: id, ExpiresAt: row.ExpiresAts[i].UTC()}
	}
	return httpx.JSON(w, http.StatusOK, out)
}

type claimResp struct {
	ID         uuid.UUID `json:"id"`
	To         string    `json:"to"`
	Body       string    `json:"body"`
	SIMSlot    *int16    `json:"sim_slot"`
	ExpiresAt  time.Time `json:"expires_at"`
	ServerTime time.Time `json:"server_time"`
}

// claim is one statement (UPDATE + event) on the happy path; the lookup that
// explains a miss runs only then. Another device's message is a 404 so
// existence is not leaked.
func (s *Service) claim(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return httpx.NotFound("message_not_found")
	}
	dev := devices.FromContext(r.Context())
	row, err := s.q.ClaimMessage(r.Context(), dbq.ClaimMessageParams{ID: id, DeviceID: dev.ID})
	if err == nil {
		body := ""
		if row.Body != nil {
			body = *row.Body
		}
		return httpx.JSON(w, http.StatusOK, claimResp{
			ID: row.ID, To: row.ToNumber, Body: body, SIMSlot: row.SimSlot,
			ExpiresAt: row.ExpiresAt.UTC(), ServerTime: row.ServerTime.UTC(),
		})
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	cur, err := s.q.GetMessageForDevice(r.Context(), dbq.GetMessageForDeviceParams{ID: id, DeviceID: dev.ID})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.NotFound("message_not_found")
		}
		return err
	}
	if cur.Status == "expired" || (cur.Status == "queued" && cur.Expired) {
		return &httpx.Error{Status: http.StatusGone, Code: "expired", Message: "message expired before it could be claimed"}
	}
	return httpx.Conflict("already_claimed", "message was already claimed")
}

type statusReq struct {
	Status       string `json:"status"`
	ErrorCode    string `json:"error_code"`
	ErrorMessage string `json:"error_message"`
}

func (s *Service) status(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return httpx.NotFound("message_not_found")
	}
	var req statusReq
	if err := httpx.Decode(w, r, &req, s.cfg.MaxBodyBytes); err != nil {
		return err
	}
	from, ok := allowedFrom[req.Status]
	if !ok {
		return httpx.Unprocessable("invalid_status", "status must be one of sent, delivered, failed")
	}
	p := dbq.TransitionStatusParams{ID: id, NewStatus: req.Status, AllowedFrom: from}
	if req.Status == "failed" {
		if n := len(req.ErrorCode); n < 1 || n > maxErrorCode || !errorCodeRe.MatchString(req.ErrorCode) {
			return httpx.Unprocessable("invalid_error_code", "failed requires error_code of 1-64 characters [a-z0-9_]")
		}
		if utf8.RuneCountInString(req.ErrorMessage) > maxErrorMessage || !utf8.ValidString(req.ErrorMessage) {
			return httpx.Unprocessable("invalid_error_message", "error_message must be at most 256 characters")
		}
		p.ErrorCode = &req.ErrorCode
		if req.ErrorMessage != "" {
			p.ErrorMessage = &req.ErrorMessage
		}
	}
	dev := devices.FromContext(r.Context())
	p.DeviceID = dev.ID
	row, err := s.q.TransitionStatus(r.Context(), p)
	if err == nil {
		m := fromPub(pubRow(row))
		if s.OnStatus != nil {
			s.OnStatus(r.Context(), m)
		}
		return httpx.JSON(w, http.StatusOK, m)
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	cur, err := s.q.GetMessageForDevice(r.Context(), dbq.GetMessageForDeviceParams{ID: id, DeviceID: dev.ID})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.NotFound("message_not_found")
		}
		return err
	}
	if cur.Status != req.Status {
		return httpx.Conflict("invalid_transition", "cannot move from "+cur.Status+" to "+req.Status)
	}
	// Retry of an already-applied report: idempotent no-op.
	return httpx.JSON(w, http.StatusOK, fromPub(pubRow{
		ID: cur.ID, UserID: cur.UserID, DeviceID: cur.DeviceID, SimSlot: cur.SimSlot, ToNumber: cur.ToNumber,
		Status: cur.Status, ExpiresAt: cur.ExpiresAt, ClientRef: cur.ClientRef, ErrorCode: cur.ErrorCode,
		ErrorMessage: cur.ErrorMessage, CreatedAt: cur.CreatedAt, DispatchedAt: cur.DispatchedAt, SentAt: cur.SentAt,
		DeliveredAt: cur.DeliveredAt, FailedAt: cur.FailedAt,
	}))
}
