# SMSGate

Public SMS-gateway service: developers send SMS through an HTTP API, paired Android phones deliver them.

## Layout

- `backend/` Go service (module `github.com/smsgate/backend`)
- `docs/` design spec and implementation plans

## Development

    make db-up     # local Postgres 17
    make test      # go test -race ./...
    make run       # run the server (APP_ENV=dev)

Configuration is environment-only; see `backend/.env.example` for every variable and its default.

Runtime tuning: `GOMEMLIMIT` (the Docker image defaults to `96MiB`) and `GOGC` are standard Go runtime variables and may be overridden.
