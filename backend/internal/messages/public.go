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
// (accounts.RequireAPIKey). sendLimit (applied after auth) wraps only
// POST /v1/messages; pass nil for none.
func Routes(mux *http.ServeMux, s *Service, auth, sendLimit func(http.Handler) http.Handler) {
	if sendLimit == nil {
		sendLimit = accounts.Identity
	}
	mux.Handle("POST /v1/messages", auth(sendLimit(httpx.Handle(s.create))))
	mux.Handle("GET /v1/messages/{id}", auth(httpx.Handle(s.get)))
	mux.Handle("GET /v1/messages", auth(httpx.Handle(s.list)))
}

// AppRoutes registers the dashboard variants of the same handlers under
// session auth (accounts.RequireSession). sendLimit wraps only POST /app/messages,
// which additionally requires a verified email (checked before the limiter).
func AppRoutes(mux *http.ServeMux, s *Service, sess, sendLimit func(http.Handler) http.Handler) {
	if sendLimit == nil {
		sendLimit = accounts.Identity
	}
	mux.Handle("POST /app/messages", sess(accounts.Verified(sendLimit(httpx.Handle(s.create)))))
	mux.Handle("GET /app/messages/{id}", sess(httpx.Handle(s.get)))
	mux.Handle("GET /app/messages", sess(httpx.Handle(s.list)))
	mux.Handle("GET /app/stats", sess(httpx.Handle(s.stats)))
}

type createReq struct {
	DeviceID   string `json:"device_id"`
	SIMSlot    *int   `json:"sim_slot"`
	To         string `json:"to"`
	Body       string `json:"body"`
	TTLSeconds *int   `json:"ttl_seconds"`
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
	return httpx.JSON(w, http.StatusOK, fromPub(pubRow(row)))
}

type listResp struct {
	Data       []Message  `json:"data"`
	NextBefore *uuid.UUID `json:"next_before"`
}

func (s *Service) list(w http.ResponseWriter, r *http.Request) error {
	qv := r.URL.Query()
	p := dbq.ListMessagesParams{UserID: accounts.UserID(r.Context())}
	limit := s.cfg.ListLimitDefault
	var before *uuid.UUID
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
		before = &id
	}
	rowLimit := int32(limit + 1) // one extra row tells whether another page exists
	var rows []pubRow
	if before != nil {
		at, err := s.q.GetMessageCursor(r.Context(), dbq.GetMessageCursorParams{ID: *before, UserID: p.UserID})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) { // not the caller's message: empty page
				return httpx.JSON(w, http.StatusOK, listResp{Data: []Message{}})
			}
			return err
		}
		res, err := s.q.ListMessagesBefore(r.Context(), dbq.ListMessagesBeforeParams{
			UserID: p.UserID, BeforeAt: at, BeforeID: *before, DeviceID: p.DeviceID, Status: p.Status, Since: p.Since, RowLimit: rowLimit,
		})
		if err != nil {
			return err
		}
		rows = make([]pubRow, len(res))
		for i, x := range res {
			rows[i] = pubRow(x)
		}
	} else {
		p.RowLimit = rowLimit
		res, err := s.q.ListMessages(r.Context(), p)
		if err != nil {
			return err
		}
		rows = make([]pubRow, len(res))
		for i, x := range res {
			rows[i] = pubRow(x)
		}
	}
	out := listResp{Data: make([]Message, 0, min(len(rows), limit))}
	if len(rows) > limit {
		rows = rows[:limit]
		last := rows[limit-1].ID
		out.NextBefore = &last
	}
	for _, m := range rows {
		out.Data = append(out.Data, fromPub(m))
	}
	return httpx.JSON(w, http.StatusOK, out)
}
