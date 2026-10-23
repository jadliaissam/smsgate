package accounts

import (
	"bufio"
	"context"
	"net"
	"strconv"

	"github.com/smsgate/backend/internal/config"
	"net/mail"
	"strings"
	"testing"
	"time"
)

func TestBuildMessage(t *testing.T) {
	from := &mail.Address{Name: "SMSGate", Address: "no-reply@example.com"}
	to := &mail.Address{Address: "dev@example.com"}
	now := time.Date(2026, 10, 23, 12, 0, 0, 0, time.UTC)
	b, err := buildMessage(from, to, "Verify your SMSGate email", "Confirm your email: https://x/y?t=1\nline2", now, "example.com")
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	for _, want := range []string{
		"From: \"SMSGate\" <no-reply@example.com>\r\n",
		"To: <dev@example.com>\r\n",
		"Subject: Verify your SMSGate email\r\n",
		"Date: Fri, 23 Oct 2026 12:00:00 +0000\r\n",
		"MIME-Version: 1.0\r\n",
		"Content-Type: text/plain; charset=\"utf-8\"\r\n",
		"\r\n\r\nConfirm your email: https://x/y?t=3D1\r\nline2\r\n",
	} {
		if !strings.Contains(s, want) {
			t.Errorf("missing %q in:\n%q", want, s)
		}
	}
	if !strings.Contains(s, "Message-ID: <") || !strings.Contains(s, "@example.com>\r\n") {
		t.Error("Message-ID missing")
	}
	if strings.Contains(strings.ReplaceAll(s, "\r\n", ""), "\n") {
		t.Error("bare LF in message")
	}
}

func TestBuildMessageRejectsHeaderInjection(t *testing.T) {
	a := &mail.Address{Address: "a@example.com"}
	if _, err := buildMessage(a, a, "hi\r\nBcc: evil@x", "b", time.Now(), "d"); err == nil {
		t.Fatal("expected error")
	}
}

func TestBuildMessageEncodesNonASCIISubject(t *testing.T) {
	a := &mail.Address{Address: "a@example.com"}
	b, _ := buildMessage(a, a, "Patvirtinkite el. paštą", "x", time.Now(), "d")
	if !strings.Contains(string(b), "Subject: =?utf-8?q?") {
		t.Fatalf("subject not encoded: %q", b)
	}
}

func TestSMTPMailerSendsToFakeServer(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	got := make(chan string, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		r := bufio.NewReader(c)
		w := func(s string) { _, _ = c.Write([]byte(s + "\r\n")) }
		w("220 fake ESMTP")
		var data strings.Builder
		inData := false
		for {
			line, err := r.ReadString('\n')
			if err != nil {
				return
			}
			if inData {
				if line == ".\r\n" {
					inData = false
					got <- data.String()
					w("250 ok")
					continue
				}
				data.WriteString(line)
				continue
			}
			switch cmd := strings.ToUpper(line); {
			case strings.HasPrefix(cmd, "EHLO"):
				w("250 fake")
			case strings.HasPrefix(cmd, "DATA"):
				inData = true
				w("354 go")
			case strings.HasPrefix(cmd, "QUIT"):
				w("221 bye")
				return
			default:
				w("250 ok")
			}
		}
	}()
	host, port, _ := net.SplitHostPort(ln.Addr().String())
	p, _ := strconv.Atoi(port)
	cfg := config.Default()
	cfg.SMTPHost, cfg.SMTPPort, cfg.SMTPFrom = host, p, "SMSGate <no-reply@example.com>"
	if err := NewSMTPMailer(cfg).Send(context.Background(), "dev@example.com", "Hello", "body"); err != nil {
		t.Fatal(err)
	}
	select {
	case d := <-got:
		if !strings.Contains(d, "Subject: Hello\r\n") || !strings.Contains(d, "\r\nbody\r\n") {
			t.Fatalf("data = %q", d)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no data received")
	}
}
