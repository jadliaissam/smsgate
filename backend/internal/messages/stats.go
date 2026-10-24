package messages

import (
	"net/http"
	"time"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/httpx"
)

type statsPlan struct {
	ID                  string `json:"id"`
	Name                string `json:"name"`
	MonthlyMessageQuota int32  `json:"monthly_message_quota"`
	MaxDevices          int32  `json:"max_devices"`
}

type statsMonth struct {
	Start    time.Time        `json:"start"`
	Total    int64            `json:"total"`
	ByStatus map[string]int64 `json:"by_status"`
}

type statsLast24h struct {
	Since    time.Time        `json:"since"`
	Total    int64            `json:"total"`
	ByStatus map[string]int64 `json:"by_status"`
}

type statsResp struct {
	Plan    statsPlan    `json:"plan"`
	Month   statsMonth   `json:"month"`
	Last24h statsLast24h `json:"last_24h"`
}

// counts returns per-status counts since a time; every known status is present.
func (s *Service) counts(r *http.Request, since time.Time) (map[string]int64, int64, error) {
	rows, err := s.q.CountMessagesByStatus(r.Context(), dbq.CountMessagesByStatusParams{
		UserID: accounts.UserID(r.Context()), Since: since,
	})
	if err != nil {
		return nil, 0, err
	}
	by := make(map[string]int64, len(statuses))
	for st := range statuses {
		by[st] = 0
	}
	var total int64
	for _, x := range rows {
		by[x.Status] = x.N
		total += x.N
	}
	return by, total, nil
}

func (s *Service) stats(w http.ResponseWriter, r *http.Request) error {
	now := s.Now().UTC()
	start := time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, time.UTC)
	since := now.Add(-24 * time.Hour).Truncate(time.Second)

	plan, err := s.q.GetUserPlan(r.Context(), accounts.UserID(r.Context()))
	if err != nil {
		return err
	}
	out := statsResp{
		Plan:    statsPlan{ID: plan.ID, Name: plan.Name, MonthlyMessageQuota: plan.MonthlyMessageQuota, MaxDevices: plan.MaxDevices},
		Month:   statsMonth{Start: start},
		Last24h: statsLast24h{Since: since},
	}
	if out.Month.ByStatus, out.Month.Total, err = s.counts(r, start); err != nil {
		return err
	}
	if out.Last24h.ByStatus, out.Last24h.Total, err = s.counts(r, since); err != nil {
		return err
	}
	return httpx.JSON(w, http.StatusOK, out)
}
