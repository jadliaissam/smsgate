// Package httpx is the small HTTP toolkit shared by all handlers: typed errors,
// a handler adapter, strict JSON decode/encode and middleware.
package httpx

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"runtime/debug"
	"strconv"
	"strings"
	"time"
)

// Error is an API error rendered as {"error":{"code":...,"message":...}}.
type Error struct {
	Status        int    `json:"-"`
	Code, Message string `json:"-"`
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func BadRequest(code, msg string) *Error { return &Error{http.StatusBadRequest, code, msg} }
func NotFound(code string) *Error        { return &Error{http.StatusNotFound, code, "not found"} }
func Unauthorized() *Error {
	return &Error{http.StatusUnauthorized, "unauthorized", "authentication required"}
}
func Conflict(code, msg string) *Error      { return &Error{http.StatusConflict, code, msg} }
func Unprocessable(code, msg string) *Error { return &Error{http.StatusUnprocessableEntity, code, msg} }

type errBody struct {
	Error struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

func writeError(w http.ResponseWriter, e *Error) {
	var b errBody
	b.Error.Code, b.Error.Message = e.Code, e.Message
	_ = JSON(w, e.Status, &b)
}

// HandlerFunc is a handler that returns an error instead of writing it.
type HandlerFunc func(w http.ResponseWriter, r *http.Request) error

// Handle adapts h: *Error values are rendered with their status; any other
// error becomes a generic 500 and is logged (never sent to the client).
func Handle(h HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		err := h(w, r)
		if err == nil {
			return
		}
		var he *Error
		if errors.As(err, &he) {
			writeError(w, he)
			return
		}
		if cerr := r.Context().Err(); cerr != nil && errors.Is(err, cerr) {
			// Client went away (e.g. while waiting for a hashing slot): not a
			// server fault, so keep it out of the error log.
			LogFrom(r.Context()).Debug("request cancelled", "request_id", RequestIDFrom(r.Context()), "path", r.Pattern)
			writeError(w, &Error{http.StatusServiceUnavailable, "cancelled", "request cancelled"})
			return
		}
		LogFrom(r.Context()).Error("handler error", "request_id", RequestIDFrom(r.Context()), "method", r.Method, "path", r.Pattern, "err", err)
		writeError(w, &Error{http.StatusInternalServerError, "internal", "internal error"})
	})
}

// isJSON reports whether a Content-Type header value is application/json
// (case-insensitive, parameters such as charset allowed). Allocation-free.
func isJSON(ct string) bool {
	if i := strings.IndexByte(ct, ';'); i >= 0 {
		ct = ct[:i]
	}
	return strings.EqualFold(strings.TrimSpace(ct), "application/json")
}

// Decode reads exactly one JSON value from the body into dst, rejecting a
// Content-Type other than application/json (415; this also makes cookie
// authenticated endpoints CSRF-safe, since cross-site requests without a CORS
// preflight cannot send it), unknown fields, trailing data and bodies over
// maxBytes.
func Decode(w http.ResponseWriter, r *http.Request, dst any, maxBytes int64) error {
	if !isJSON(r.Header.Get("Content-Type")) {
		return &Error{http.StatusUnsupportedMediaType, "unsupported_media_type", "Content-Type must be application/json"}
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBytes))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		return decodeErr(err)
	}
	if _, err := dec.Token(); err != io.EOF {
		if err == nil {
			err = errors.New("trailing data")
		}
		return decodeErr(err)
	}
	return nil
}

func decodeErr(err error) *Error {
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		return &Error{http.StatusRequestEntityTooLarge, "body_too_large", "request body too large"}
	}
	return BadRequest("invalid_json", "request body is not valid JSON")
}

// JSON writes v as a JSON response with the given status.
func JSON(w http.ResponseWriter, status int, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	h := w.Header()
	h.Set("Content-Type", "application/json; charset=utf-8")
	h.Set("Content-Length", strconv.Itoa(len(b)))
	w.WriteHeader(status)
	_, err = w.Write(b)
	return err
}

type ctxKey int

const (
	keyID ctxKey = iota
	keyLog
)

// RequestIDFrom returns the request id set by RequestID, or "".
func RequestIDFrom(ctx context.Context) string { s, _ := ctx.Value(keyID).(string); return s }

// LogFrom returns the logger installed by Recover, or slog.Default().
func LogFrom(ctx context.Context) *slog.Logger {
	if l, _ := ctx.Value(keyLog).(*slog.Logger); l != nil {
		return l
	}
	return slog.Default()
}

const hexdigits = "0123456789abcdef"

func newID() string {
	var b [32]byte
	for i := 0; i < 32; i += 16 {
		v := rand.Uint64()
		for j := 0; j < 16; j++ {
			b[i+j] = hexdigits[v&0xf]
			v >>= 4
		}
	}
	return string(b[:])
}

func validID(s string) bool {
	if s == "" || len(s) > 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '-' || c == '_' || c == '.') {
			return false
		}
	}
	return true
}

// RequestID propagates a safe incoming X-Request-Id or generates one, echoes
// it in the response and stores it in the request context.
func RequestID(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		id := r.Header.Get("X-Request-Id")
		if !validID(id) {
			id = newID()
		}
		w.Header().Set("X-Request-Id", id)
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), keyID, id)))
	})
}

// Recover turns panics into a generic 500, logging the panic with the request
// id, and installs log in the request context for Handle.
func Recover(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			r = r.WithContext(context.WithValue(r.Context(), keyLog, log))
			defer func() {
				rec := recover()
				if rec == nil {
					return
				}
				if rec == http.ErrAbortHandler {
					panic(rec)
				}
				log.Error("panic", "request_id", RequestIDFrom(r.Context()), "method", r.Method, "path", r.Pattern, "panic", rec,
					"stack", string(debug.Stack()))
				writeError(w, &Error{http.StatusInternalServerError, "internal", "internal error"})
			}()
			next.ServeHTTP(w, r)
		})
	}
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (s *statusRecorder) WriteHeader(code int) {
	if s.status == 0 {
		s.status = code
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.status == 0 {
		s.status = http.StatusOK
	}
	return s.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach the underlying writer.
func (s *statusRecorder) Unwrap() http.ResponseWriter { return s.ResponseWriter }

// AccessLog logs method, matched route pattern (low cardinality), status and
// duration of every request. Bodies are never logged. It must wrap the mux
// directly so r.Pattern is visible after routing.
func AccessLog(log *slog.Logger) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			start := time.Now()
			sr := &statusRecorder{ResponseWriter: w}
			panicked := true
			defer func() {
				status := sr.status
				if panicked {
					status = http.StatusInternalServerError
				} else if status == 0 {
					status = http.StatusOK
				}
				route := r.Pattern
				if route == "" {
					route = "unmatched"
				}
				log.LogAttrs(r.Context(), slog.LevelInfo, "request",
					slog.String("request_id", RequestIDFrom(r.Context())),
					slog.String("route", route),
					slog.Int("status", status),
					slog.Duration("duration", time.Since(start)))
			}()
			next.ServeHTTP(sr, r)
			panicked = false
		})
	}
}
