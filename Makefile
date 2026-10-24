.PHONY: db-up db-down sqlc test run build web-install web-dev web-test web-build android-test android-build

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

web/node_modules: web/package.json web/package-lock.json
	cd web && npm ci
	@touch $@

web-install: web/node_modules

# Vite dev server; proxies /app /v1 /device /healthz to VITE_API_PROXY (default http://localhost:8080).
web-dev: web/node_modules
	cd web && npm run dev

web-test: web/node_modules
	cd web && npm run lint && npm run typecheck && npm test

web-build: web/node_modules
	cd web && npm run build

# Android (needs JDK 17 in JAVA_HOME, the SDK in ANDROID_HOME or android/local.properties,
# and the private android/app/google-services.json copied in by hand).
android-test:
	node android/scripts/gen-tokens.mjs --check
	node --test 'android/scripts/*.test.mjs'
	cd android && ./gradlew lint testDebugUnitTest

android-build:
	cd android && ./gradlew assembleDebug
