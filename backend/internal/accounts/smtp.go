package accounts

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"mime"
	"mime/quotedprintable"
	"net"
	"net/mail"
	"net/smtp"
	"strconv"
	"strings"
	"time"

	"github.com/smsgate/backend/internal/config"
)

// SMTPMailer sends plain-text mail through an SMTP relay using only the
// standard library. STARTTLS is used when the server offers it; port 465 uses
// implicit TLS. One short-lived connection per message: transactional volume
// is tiny, so no pooling.
type SMTPMailer struct {
	addr, host, user, pass, from string
	timeout                      time.Duration
	now                          func() time.Time
}

// NewSMTPMailer builds a mailer from cfg (SMTP_* settings).
func NewSMTPMailer(cfg config.Config) *SMTPMailer {
	return &SMTPMailer{
		addr:    net.JoinHostPort(cfg.SMTPHost, strconv.Itoa(cfg.SMTPPort)),
		host:    cfg.SMTPHost,
		user:    cfg.SMTPUsername,
		pass:    cfg.SMTPPassword,
		from:    cfg.SMTPFrom,
		timeout: cfg.SMTPTimeout,
		now:     time.Now,
	}
}

// Send delivers one message; it honors ctx cancellation and the configured timeout.
func (m *SMTPMailer) Send(ctx context.Context, to, subject, text string) error {
	from, err := mail.ParseAddress(m.from)
	if err != nil {
		return fmt.Errorf("smtp: SMTP_FROM: %w", err)
	}
	rcpt, err := mail.ParseAddress(to)
	if err != nil {
		return fmt.Errorf("smtp: recipient: %w", err)
	}
	msg, err := buildMessage(from, rcpt, subject, text, m.now(), m.host)
	if err != nil {
		return err
	}

	dctx, cancel := context.WithTimeout(ctx, m.timeout)
	defer cancel()
	d := net.Dialer{}
	conn, err := d.DialContext(dctx, "tcp", m.addr)
	if err != nil {
		return fmt.Errorf("smtp: dial: %w", err)
	}
	defer conn.Close()
	if dl, ok := dctx.Deadline(); ok {
		_ = conn.SetDeadline(dl) // bounds the whole conversation
	}
	_, port, _ := net.SplitHostPort(m.addr)
	if port == "465" {
		conn = tls.Client(conn, &tls.Config{ServerName: m.host, MinVersion: tls.VersionTLS12})
	}
	c, err := smtp.NewClient(conn, m.host)
	if err != nil {
		return fmt.Errorf("smtp: greeting: %w", err)
	}
	defer c.Close()
	if ok, _ := c.Extension("STARTTLS"); ok && port != "465" {
		if err := c.StartTLS(&tls.Config{ServerName: m.host, MinVersion: tls.VersionTLS12}); err != nil {
			return fmt.Errorf("smtp: starttls: %w", err)
		}
	}
	if m.user != "" {
		if err := c.Auth(smtp.PlainAuth("", m.user, m.pass, m.host)); err != nil {
			return fmt.Errorf("smtp: auth: %w", err)
		}
	}
	if err := c.Mail(from.Address); err != nil {
		return fmt.Errorf("smtp: MAIL FROM: %w", err)
	}
	if err := c.Rcpt(rcpt.Address); err != nil {
		return fmt.Errorf("smtp: RCPT TO: %w", err)
	}
	w, err := c.Data()
	if err != nil {
		return fmt.Errorf("smtp: DATA: %w", err)
	}
	if _, err := w.Write(msg); err != nil {
		return fmt.Errorf("smtp: write: %w", err)
	}
	if err := w.Close(); err != nil {
		return fmt.Errorf("smtp: send: %w", err)
	}
	_ = c.Quit()
	return nil
}

// buildMessage renders an RFC 5322 message with CRLF line endings and a
// quoted-printable UTF-8 plain-text body.
func buildMessage(from, to *mail.Address, subject, text string, now time.Time, domain string) ([]byte, error) {
	if strings.ContainsAny(subject, "\r\n") {
		return nil, errors.New("smtp: subject contains line break")
	}
	var id [12]byte
	if _, err := rand.Read(id[:]); err != nil {
		return nil, err
	}
	var b bytes.Buffer
	h := func(k, v string) { b.WriteString(k + ": " + v + "\r\n") }
	h("From", from.String())
	h("To", to.String())
	h("Subject", mime.QEncoding.Encode("utf-8", subject))
	h("Date", now.UTC().Format(time.RFC1123Z))
	h("Message-ID", "<"+hex.EncodeToString(id[:])+"@"+domain+">")
	h("MIME-Version", "1.0")
	h("Content-Type", `text/plain; charset="utf-8"`)
	h("Content-Transfer-Encoding", "quoted-printable")
	b.WriteString("\r\n")
	body := strings.ReplaceAll(text, "\r\n", "\n")
	body = strings.ReplaceAll(body, "\n", "\r\n")
	qw := quotedprintable.NewWriter(&b)
	if _, err := qw.Write([]byte(body)); err != nil {
		return nil, err
	}
	if err := qw.Close(); err != nil {
		return nil, err
	}
	b.WriteString("\r\n")
	return b.Bytes(), nil
}
