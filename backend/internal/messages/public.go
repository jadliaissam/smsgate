package messages

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
)

// Routes registers the public API; auth must authenticate API keys
// (accounts.RequireAPIKey).
func Routes(mux *http.ServeMux, s *Service, auth func(http.Handler) http.Handler) {
	mux.Handle("POST /v1/messages", auth(httpx.Handle(s.create)))
	mux.Handle("GET /v1/messages/{id}", auth(httpx.Handle(s.get)))
	mux.Handle("GET /v1/messages", auth(httpx.Handle(s.list)))
}

type createReq struct {
	DeviceID   string `json:"device_id"`
	SIMSlot    *int   `json:"sim_slot"`
	To         string `json:"to"`
	Body       string `json:"body"`
	TTLSeconds int    `json:"ttl_seconds"`
	ClientRef  string `json:"client_ref"`
}

func (s *Service) create(w http.ResponseWriter, r *http.Request) error {
	var req createReq
	if err := httpx.Decode(w, r, &req, s.cfg.MaxBodyBytes); err != nil {
		return err
	}
	// An unparsable device_id can't belong to anyone: same 404 as a missing one.
	dev, err := uuid.Parse(req.DeviceID)
	if err != nil {
		return httpx.NotFound("device_not_found")
	}
	m, created, err := s.Create(r.Context(), accounts.UserID(r.Context()), CreateInput{
		DeviceID: dev, SIMSlot: req.SIMSlot, To: req.To, Body: req.Body, TTLSeconds: req.TTLSeconds, ClientRef: req.ClientRef,
	}, r.Header.Get("Idempotency-Key"))
	if err != nil {
		return err
	}
	status := http.StatusAccepted
	if !created {
		status = http.StatusOK
	}
	return httpx.JSON(w, status, m)
}

func (s *Service) get(w http.ResponseWriter, r *http.Request) error {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		return httpx.NotFound("message_not_found")
	}
	row, err := s.q.GetMessageForUser(r.Context(), dbq.GetMessageForUserParams{ID: id, UserID: accounts.UserID(r.Context())})
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return httpx.NotFound("message_not_found")
		}
		return err
	}
	return httpx.JSON(w, http.StatusOK, FromRow(row))
}

type listResp struct {
	Data       []Message  `json:"data"`
	NextBefore *uuid.UUID `json:"next_before"`
}

func (s *Service) list(w http.ResponseWriter, r *http.Request) error {
	qv := r.URL.Query()
	p := dbq.ListMessagesParams{UserID: accounts.UserID(r.Context())}
	limit := s.cfg.ListLimitDefault
	if v := qv.Get("limit"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > s.cfg.ListLimitMax {
			return httpx.Unprocessable("invalid_limit", "limit must be between 1 and "+strconv.Itoa(s.cfg.ListLimitMax))
		}
		limit = n
	}
	if v := qv.Get("device_id"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			return httpx.Unprocessable("invalid_device_id", "device_id must be a UUID")
		}
		p.DeviceID = &id
	}
	if v := qv.Get("status"); v != "" {
		if !statuses[v] {
			return httpx.Unprocessable("invalid_status", "status must be one of queued, dispatched, sent, delivered, failed, expired")
		}
		p.Status = &v
	}
	if v := qv.Get("since"); v != "" {
		t, err := time.Parse(time.RFC3339, v)
		if err != nil {
			return httpx.Unprocessable("invalid_since", "since must be an RFC 3339 timestamp")
		}
		p.Since = &t
	}
	if v := qv.Get("before"); v != "" {
		id, err := uuid.Parse(v)
		if err != nil {
			return httpx.Unprocessable("invalid_before", "before must be a message id")
		}
		p.Before = &id
	}
	p.RowLimit = int32(limit + 1) // one extra row tells whether another page exists
	rows, err := s.q.ListMessages(r.Context(), p)
	if err != nil {
		return err
	}
	out := listResp{Data: make([]Message, 0, min(len(rows), limit))}
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[limit-1].ID
		out.NextBefore = &last
	}
	for _, m := range rows {
		out.Data = append(out.Data, FromRow(m))
	}
	return httpx.JSON(w, http.StatusOK, out)
}
