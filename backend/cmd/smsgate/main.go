// Command smsgate is the SMS gateway server.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/smsgate/backend/internal/accounts"
	"github.com/smsgate/backend/internal/config"
	"github.com/smsgate/backend/internal/db"
	"github.com/smsgate/backend/internal/db/dbq"
	"github.com/smsgate/backend/internal/messages"
	"github.com/smsgate/backend/internal/push"
	"github.com/smsgate/backend/internal/realtime"
	"github.com/smsgate/backend/internal/server"
	"github.com/smsgate/backend/internal/sweeper"
)

func main() {
	hc := flag.Bool("healthcheck", false, "probe GET /healthz on the loopback address and exit 0 (healthy) or 1")
	flag.Parse()
	if *hc {
		if err := healthcheck(config.HealthURL(), 3*time.Second); err != nil {
			fmt.Fprintln(os.Stderr, "healthcheck:", err)
			os.Exit(1)
		}
		return
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "smsgate:", err)
		os.Exit(1)
	}
}

// healthcheck GETs url and succeeds only on HTTP 200.
func healthcheck(url string, timeout time.Duration) error {
	c := http.Client{Timeout: timeout}
	resp, err := c.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	return nil
}

// signalContext is cancelled by the first SIGINT/SIGTERM (graceful shutdown).
// It then stops catching signals, so a second one terminates the process
// immediately instead of waiting for the shutdown budget.
// Handling is released before ctx is cancelled, so no second signal can slip
// in between.
func signalContext() (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.Background())
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		select {
		case <-ch:
		case <-ctx.Done():
		}
		signal.Stop(ch)
		cancel()
	}()
	return ctx, cancel
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("config: %w", err)
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: cfg.LogLevel}))
	log.Info("smsgate starting", "addr", cfg.Addr, "env", cfg.Env)

	ctx, stop := signalContext()
	defer stop()

	if err := db.Migrate(ctx, cfg.DatabaseURL, cfg.MigrateLockTimeout); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	pool, err := db.Open(ctx, cfg)
	if err != nil {
		return err
	}
	defer pool.Close()

	var waker push.Waker = push.Noop{}
	if cfg.FCMCredentialsFile == "" && cfg.FCMCredentialsJSON == "" {
		log.Warn("FCM_CREDENTIALS_JSON and FCM_CREDENTIALS_FILE not set: push wake-ups disabled (noop waker)")
	} else {
		// Background context: the token source captures it and must outlive startup.
		f, err := push.NewFCM(context.Background(), cfg, push.NewHTTPClient(cfg))
		if err != nil {
			return err
		}
		waker = f
	}

	var mailer accounts.Mailer
	switch cfg.Mailer {
	case "smtp":
		mailer = accounts.NewSMTPMailer(cfg)
	default:
		if cfg.Env != "dev" {
			log.Warn("MAILER=log outside APP_ENV=dev: emails (including verification links) are only written to the log")
		}
		mailer = accounts.LogMailer{Log: log}
	}

	q := dbq.New(pool)
	svc := messages.NewService(q, waker, cfg, log)
	hub := server.NewHub(pool, cfg, log, time.Now)
	handler := server.New(server.Deps{Pool: pool, Log: log, Cfg: cfg, Waker: waker, Mailer: mailer, Messages: svc, Hub: hub})
	srv := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler,
		ReadHeaderTimeout: cfg.ReadHeaderTimeout,
		ReadTimeout:       cfg.ReadTimeout,
		WriteTimeout:      cfg.WriteTimeout,
		IdleTimeout:       cfg.IdleTimeout,
		MaxHeaderBytes:    16 << 10,
	}

	sweepCtx, cancelSweep := context.WithCancel(context.Background())
	var wg sync.WaitGroup
	// Device sockets: the reaper and the LISTEN connection that routes
	// wake/revoke notifications from every instance to the sockets held here.
	wg.Add(2)
	go func() { defer wg.Done(); hub.Run(sweepCtx) }()
	go func() {
		defer wg.Done()
		realtime.NewListener(pool.Config().ConnConfig, hub, log, cfg.WSListenRetryMax, cfg.WSListenPingInterval).Run(sweepCtx)
	}()
	wg.Add(1)
	go func() {
		defer wg.Done()
		(&sweeper.Sweeper{
			Q: q, Log: log, Interval: cfg.SweepInterval, LostAfter: cfg.DeviceLostAfter, Batch: cfg.SweepBatch,
			PurgeInterval: cfg.PurgeInterval,
		}).Run(sweepCtx)
	}()

	serveErr := make(chan error, 1)
	go func() { serveErr <- srv.ListenAndServe() }()

	var runErr error
	select {
	case err := <-serveErr:
		runErr = fmt.Errorf("listen: %w", err)
	case <-ctx.Done():
		log.Info("shutting down")
	}

	shCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shCtx); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("http shutdown", "err", err)
	}
	// Hijacked WebSockets are not drained by srv.Shutdown: close them (1001)
	// so devices reconnect to another instance; their marks are cleared.
	hub.Shutdown(shCtx)
	cancelSweep()
	done := make(chan struct{})
	go func() { wg.Wait(); svc.Wait(); close(done) }()
	select {
	case <-done:
	case <-shCtx.Done():
		log.Warn("shutdown timeout: background work still running")
	}
	log.Info("stopped")
	return runErr // pool closed by defer
}
