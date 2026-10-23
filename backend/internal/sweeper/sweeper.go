// Package sweeper runs the message-lifecycle background loop: queued messages
// past their validity window become 'expired', and dispatched messages that
// never got a report long after expiry become failed/device_lost. Each batch
// is a single SQL statement using FOR UPDATE SKIP LOCKED, so several instances
// can sweep concurrently, and an idle tick costs two index-served queries.
// Every PurgeInterval it also deletes expired sessions, email tokens and
// pairing codes in batches.
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
	// PurgeInterval is how often Run purges expired auth rows (first tick
	// included); 0 disables purging.
	PurgeInterval time.Duration
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

// Purge deletes sessions, email tokens and pairing codes that expired (or,
// for pairing codes, were used) over an hour ago, Batch rows per statement and
// at most maxBatchesPerTick statements per table. It returns the rows deleted.
func (s *Sweeper) Purge(ctx context.Context) (int, error) {
	batch := int32(s.Batch)
	total := 0
	for _, purge := range [...]func(context.Context, int32) (int64, error){
		s.Q.PurgeSessions, s.Q.PurgeEmailTokens, s.Q.PurgePairingCodes,
	} {
		for i := 0; i < maxBatchesPerTick; i++ {
			if err := ctx.Err(); err != nil {
				return total, err
			}
			n, err := purge(ctx, batch)
			if err != nil {
				return total, err
			}
			total += int(n)
			if n < int64(batch) {
				break
			}
		}
	}
	return total, nil
}

// Run ticks every Interval until ctx is cancelled. Errors (e.g. a transient DB
// outage) are logged and retried on the next tick.
func (s *Sweeper) Run(ctx context.Context) {
	t := time.NewTicker(s.Interval)
	defer t.Stop()
	var lastPurge time.Time
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
		if s.PurgeInterval > 0 && time.Since(lastPurge) >= s.PurgeInterval {
			lastPurge = time.Now()
			n, err := s.Purge(ctx)
			if err != nil && ctx.Err() == nil {
				s.Log.Error("sweeper purge failed", "err", err)
			}
			if n > 0 {
				s.Log.Info("sweeper purge", "deleted", n)
			}
		}
	}
}
