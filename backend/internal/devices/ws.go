package devices

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/google/uuid"

	"github.com/smsgate/backend/internal/httpx"
	"github.com/smsgate/backend/internal/realtime"
)

// ws upgrades GET /device/v1/ws (after RequireDevice and the per-device rate
// limit, so a bad or revoked token gets 401 before any upgrade).
func (s *service) ws(w http.ResponseWriter, r *http.Request) error {
	id := FromContext(r.Context()).ID
	return s.Hub.Serve(w, r, id, func(ctx context.Context, data []byte) []byte { return s.frame(ctx, id, data) })
}

// wsFrame is a device frame: {"type":"ping"} or {"type":"heartbeat", ...the
// POST /device/v1/heartbeat fields}.
type wsFrame struct {
	Type string `json:"type"`
	heartbeatReq
}

// frame handles one device frame. ping and heartbeat are answered with
// {"type":"pong","server_time":...}: the DB clock for a heartbeat (as over
// HTTP), the app clock for a ping (no DB round-trip). Errors are answered with
// {"type":"error","code","message"} using the HTTP API's codes.
func (s *service) frame(ctx context.Context, device uuid.UUID, data []byte) []byte {
	if s.FrameLimit != nil && !s.FrameLimit(device) {
		return realtime.ErrorFrame("rate_limited", "too many frames")
	}
	var f wsFrame
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&f); err != nil || dec.More() {
		return realtime.ErrorFrame("invalid_json", "frame is not valid JSON")
	}
	switch f.Type {
	case "ping":
		return realtime.PongFrame(s.Now())
	case "heartbeat":
		t, err := s.applyHeartbeat(ctx, device, f.heartbeatReq)
		if err != nil {
			var he *httpx.Error
			if errors.As(err, &he) {
				return realtime.ErrorFrame(he.Code, he.Message)
			}
			return realtime.ErrorFrame("internal_error", "internal error")
		}
		return realtime.PongFrame(t)
	default:
		return realtime.ErrorFrame("unknown_type", `type must be "ping" or "heartbeat"`)
	}
}
