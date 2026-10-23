.PHONY: db-up db-down sqlc test run build

DEV_DB := postgres://smsgate:smsgate@localhost:5432/smsgate?sslmode=disable

db-up:
	docker compose -f docker-compose.dev.yml up -d postgres

db-down:
	docker compose -f docker-compose.dev.yml down

sqlc:
	docker run --rm -v $(PWD)/backend:/src -w /src sqlc/sqlc:1.29.0 generate

test:
	cd backend && TEST_DATABASE_URL='$(DEV_DB)' go test -race ./...

run:
	cd backend && DATABASE_URL='$(DEV_DB)' APP_ENV=dev go run ./cmd/smsgate

build:
	cd backend && go build -o ../bin/smsgate ./cmd/smsgate
