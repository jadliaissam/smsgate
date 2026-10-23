// Command smsgate is the SMS gateway server.
package main

import (
	"fmt"
	"log/slog"
	"os"

	"github.com/smsgate/backend/internal/config"
)

func main() {
	cfg, err := config.Load()
	if err != nil {
		fmt.Fprintln(os.Stderr, "config:", err)
		os.Exit(1)
	}
	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{Level: cfg.LogLevel}))
	log.Info("smsgate starting", "addr", cfg.Addr, "env", cfg.Env)
}
