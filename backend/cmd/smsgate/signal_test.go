package main

import (
	"bufio"
	"errors"
	"os"
	"os/exec"
	"syscall"
	"testing"
	"time"
)

// TestMain lets the test binary act as a child process that waits for the
// first signal and then simulates a slow graceful shutdown.
func TestMain(m *testing.M) {
	if os.Getenv("SMSGATE_SIGNAL_CHILD") == "1" {
		ctx, stop := signalContext()
		defer stop()
		os.Stdout.WriteString("ready\n")
		<-ctx.Done()
		os.Stdout.WriteString("shutting down\n")
		time.Sleep(10 * time.Second) // a second signal must cut this short
		os.Exit(0)
	}
	os.Exit(m.Run())
}

func TestSecondSignalKills(t *testing.T) {
	cmd := exec.Command(os.Args[0], "-test.run=^$")
	cmd.Env = append(os.Environ(), "SMSGATE_SIGNAL_CHILD=1")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer cmd.Process.Kill() //nolint:errcheck
	lines := bufio.NewScanner(out)
	expect := func(want string) {
		t.Helper()
		if !lines.Scan() || lines.Text() != want {
			t.Fatalf("child said %q, want %q", lines.Text(), want)
		}
	}
	expect("ready")
	_ = cmd.Process.Signal(syscall.SIGINT)
	expect("shutting down")
	_ = cmd.Process.Signal(syscall.SIGINT)
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		var ee *exec.ExitError
		if !errors.As(err, &ee) || ee.Sys().(syscall.WaitStatus).Signal() != syscall.SIGINT {
			t.Fatalf("child exit: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("second SIGINT did not terminate the process")
	}
}
