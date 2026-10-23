package push

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/oauth2"
	"golang.org/x/oauth2/google"

	"github.com/smsgate/backend/internal/config"
)

const (
	fcmScope = "https://www.googleapis.com/auth/firebase.messaging"
	// maxErrBody bounds how much of an FCM error response is read.
	maxErrBody = 1 << 10
)

// FCM sends data pushes through the FCM HTTP v1 API (no Firebase Admin SDK).
type FCM struct {
	hc  *http.Client
	ts  oauth2.TokenSource
	url string // full messages:send URL
}

// NewHTTPClient builds the shared HTTP client for FCM and OAuth calls.
func NewHTTPClient(cfg config.Config) *http.Client {
	tr := http.DefaultTransport.(*http.Transport).Clone()
	tr.MaxIdleConnsPerHost = cfg.FCMMaxIdlePerHost
	tr.ForceAttemptHTTP2 = true
	return &http.Client{Timeout: cfg.WakeTimeout, Transport: tr}
}

// NewFCM loads the service-account JSON from cfg.FCMCredentialsFile. hc is used
// for both FCM calls and token refreshes.
func NewFCM(ctx context.Context, cfg config.Config, hc *http.Client) (*FCM, error) {
	data, err := os.ReadFile(cfg.FCMCredentialsFile)
	if err != nil {
		return nil, fmt.Errorf("FCM_CREDENTIALS_FILE: %w", err)
	}
	var sa struct {
		ProjectID string `json:"project_id"`
	}
	if err := json.Unmarshal(data, &sa); err != nil || sa.ProjectID == "" {
		return nil, fmt.Errorf("FCM_CREDENTIALS_FILE %q: not a service-account JSON with project_id", cfg.FCMCredentialsFile)
	}
	// The oauth2 token fetch uses this client from the context.
	creds, err := google.CredentialsFromJSON(context.WithValue(ctx, oauth2.HTTPClient, hc), data, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("FCM_CREDENTIALS_FILE %q: %w", cfg.FCMCredentialsFile, err)
	}
	return newFCMWithTokenSource(sa.ProjectID, cfg, hc, creds.TokenSource), nil
}

// newFCMWithTokenSource wires an FCM with an explicit token source (tests).
// The source is cached so refreshes only happen near expiry.
func newFCMWithTokenSource(project string, cfg config.Config, hc *http.Client, ts oauth2.TokenSource) *FCM {
	return &FCM{
		hc:  hc,
		ts:  oauth2.ReuseTokenSource(nil, ts),
		url: strings.TrimRight(cfg.FCMEndpoint, "/") + "/v1/projects/" + url.PathEscape(project) + "/messages:send",
	}
}

type fcmRequest struct {
	Message fcmMessage `json:"message"`
}

type fcmMessage struct {
	Token   string     `json:"token"`
	Data    fcmData    `json:"data"`
	Android fcmAndroid `json:"android"`
}

type fcmData struct {
	Type      string `json:"type"`
	MessageID string `json:"message_id"`
}

type fcmAndroid struct {
	Priority string `json:"priority"`
	TTL      string `json:"ttl"`
}

// Wake sends a high-priority data push carrying only the message id.
func (f *FCM) Wake(ctx context.Context, t WakeTarget) error {
	secs := int64(time.Until(t.ExpiresAt) / time.Second)
	if secs < 0 {
		secs = 0
	}
	body, err := json.Marshal(fcmRequest{Message: fcmMessage{
		Token:   t.FCMToken,
		Data:    fcmData{Type: "new", MessageID: t.MessageID.String()},
		Android: fcmAndroid{Priority: "HIGH", TTL: strconv.FormatInt(secs, 10) + "s"},
	}})
	if err != nil {
		return err
	}
	tok, err := f.ts.Token()
	if err != nil {
		return fmt.Errorf("fcm: access token: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, f.url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+tok.AccessToken)
	resp, err := f.hc.Do(req)
	if err != nil {
		return fmt.Errorf("fcm: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 == 2 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, maxErrBody))
		return nil
	}
	b, _ := io.ReadAll(io.LimitReader(resp.Body, maxErrBody))
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10)) // keep the connection reusable
	if tokenRejected(resp.StatusCode, b) {
		return fmt.Errorf("fcm: status %d: %w", resp.StatusCode, ErrTokenInvalid)
	}
	return fmt.Errorf("fcm: status %d: %s", resp.StatusCode, bytes.TrimSpace(b))
}

// tokenRejected reports whether an FCM error response means the registration
// token is unusable: 404, or 400 UNREGISTERED / INVALID_ARGUMENT about the token.
func tokenRejected(status int, body []byte) bool {
	if status == http.StatusNotFound {
		return true
	}
	if status != http.StatusBadRequest {
		return false
	}
	var e struct {
		Error struct {
			Message string `json:"message"`
			Details []struct {
				ErrorCode string `json:"errorCode"`
			} `json:"details"`
		} `json:"error"`
	}
	if json.Unmarshal(body, &e) != nil {
		return false
	}
	for _, d := range e.Error.Details {
		if d.ErrorCode == "UNREGISTERED" {
			return true
		}
	}
	return strings.Contains(strings.ToLower(e.Error.Message), "registration token")
}
