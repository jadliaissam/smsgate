// Package sweeper runs the message-lifecycle background loop: queued messages
// past their validity window become 'expired', and dispatched messages that
// never got a report long after expiry become failed/device_lost. Each batch
// is a single SQL statement using FOR UPDATE SKIP LOCKED, so several instances
// can sweep concurrently, and an idle tick costs two index-served queries.
package sweeper

import (
	"context"
	"log/slog"
	"time"

	"github.com/smsgate/backend/internal/db/dbq"
)

// maxBatchesPerTick bounds the drain loop so a huge backlog cannot starve the
// ticker; the next tick continues where this one stopped.
const maxBatchesPerTick = 20

// Sweeper expires stale messages. Zero Batch/Interval are not valid; take them
// from config.
type Sweeper struct {
	Q         *dbq.Queries
	Log       *slog.Logger
	Interval  time.Duration
	LostAfter time.Duration
	Batch     int
	// OnChange, when set, is called for every message the sweeper transitioned
	// (webhook hook). It must not block for long.
	OnChange func(ctx context.Context, m dbq.Message)
}

func (s *Sweeper) notify(ctx context.Context, m dbq.Message) {
	if s.OnChange != nil {
		s.OnChange(ctx, m)
	}
}

// Tick runs one sweep: it drains expired-queued then lost-dispatched batches
// (while a batch comes back full, at most maxBatchesPerTick each) and returns
// how many messages were expired and marked device_lost.
func (s *Sweeper) Tick(ctx context.Context) (expired, lost int, err error) {
	batch := int32(s.Batch)
	for i := 0; i < maxBatchesPerTick; i++ {
		if err = ctx.Err(); err != nil {
			return
		}
		var rows []dbq.ExpireQueuedRow
		if rows, err = s.Q.ExpireQueued(ctx, batch); err != nil {
			return
		}
		for _, r := range rows {
			s.notify(ctx, dbq.Message(r))
		}
		expired += len(rows)
		if len(rows) < int(batch) {
			break
		}
	}
	lostSecs := s.LostAfter.Seconds()
	for i := 0; i < maxBatchesPerTick; i++ {
		if err = ctx.Err(); err != nil {
			return
		}
		var rows []dbq.FailLostDispatchedRow
		if rows, err = s.Q.FailLostDispatched(ctx, dbq.FailLostDispatchedParams{LostAfterSeconds: lostSecs, Batch: batch}); err != nil {
			return
		}
		for _, r := range rows {
			s.notify(ctx, dbq.Message(r))
		}
		lost += len(rows)
		if len(rows) < int(batch) {
			break
		}
	}
	return
}

// Run ticks every Interval until ctx is cancelled. Errors (e.g. a transient DB
// outage) are logged and retried on the next tick.
func (s *Sweeper) Run(ctx context.Context) {
	t := time.NewTicker(s.Interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		expired, lost, err := s.Tick(ctx)
		if err != nil && ctx.Err() == nil {
			s.Log.Error("sweeper tick failed", "err", err)
		}
		if expired+lost > 0 {
			s.Log.Info("sweeper tick", "expired", expired, "device_lost", lost)
		}
	}
}
