#!/usr/bin/env bash
# End-to-end smoke test against a running server (APP_ENV=dev, MAILER=log).
#
#   BASE_URL   server base URL           (default http://localhost:8080)
#   SERVER_LOG file the server logs to   (arg 1 or env; JSON lines, needed to
#              read the verify link from the dev LogMailer)
#
# Requires: bash, curl, jq. Exits non-zero on the first mismatch.
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
LOG="${1:-${SERVER_LOG:-}}"
[[ -n "$LOG" && -r "$LOG" ]] || { echo "usage: $0 <server-log-file>  (or SERVER_LOG=...)" >&2; exit 2; }

JAR="$(mktemp)"; trap 'rm -f "$JAR"' EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo "-- $*"; }

# req METHOD PATH [curl args...] -> sets $CODE and $BODY (runs in the current
# shell, never in a subshell, so the variables survive)
req() {
  local method="$1" path="$2"; shift 2
  local out; out="$(mktemp)"
  CODE="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" -b "$JAR" -c "$JAR" "$BASE$path" "$@")"
  BODY="$(cat "$out")"; rm -f "$out"
}
expect() { [[ "$CODE" == "$1" ]] || fail "$2: HTTP $CODE, want $1"; }

EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
PASS="smoke-password-123"
J=(-H 'Content-Type: application/json')

step "healthz"
req GET /healthz; expect 200 healthz

step "signup"
req POST /app/signup "${J[@]}" -d "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p}')"
expect 201 signup

step "read verify link from log"
TOKEN=""
for _ in $(seq 1 50); do
  TOKEN="$(grep -F "$EMAIL" "$LOG" | grep -o 'verify?token=[^"\\ ]*' | tail -n1 | cut -d= -f2- || true)"
  [[ -n "$TOKEN" ]] && break
  sleep 0.1
done
[[ -n "$TOKEN" ]] || fail "no verify link for $EMAIL in $LOG"
# the token is URL-escaped inside the link; decode it
TOKEN="$(printf '%b' "${TOKEN//%/\\x}")"

step "verify"
req POST /app/verify "${J[@]}" -d "$(jq -nc --arg t "$TOKEN" '{token:$t}')"
expect 204 verify

step "login"
req POST /app/login "${J[@]}" -d "$(jq -nc --arg e "$EMAIL" --arg p "$PASS" '{email:$e,password:$p}')"
expect 204 login

step "api key"
req POST /app/api-keys "${J[@]}" -d '{"name":"smoke"}'; expect 201 api-key
KEY="$(jq -r .key <<<"$BODY")"
[[ -n "$KEY" && "$KEY" != null ]] || fail "no api key returned"

step "pairing code"
req POST /app/pairing-codes "${J[@]}"
CODE_JSON="$BODY"; expect 201 pairing-code
PAIR="$(jq -r .code <<<"$CODE_JSON")"
[[ -n "$PAIR" && "$PAIR" != null ]] || fail "no pairing code"

step "register device"
req POST /device/v1/register "${J[@]}" -d "$(jq -nc --arg c "$PAIR" \
  '{pairing_code:$c,name:"Smoke phone",sims:[{slot:1,subscription_id:1,carrier:"Test"}],app_version:"smoke"}')"
REG="$BODY"; expect 201 register
DEV="$(jq -r .device_id <<<"$REG")"; DTOK="$(jq -r .device_token <<<"$REG")"
[[ "$DEV" != null && "$DTOK" != null ]] || fail "register response: $REG"

step "send message"
req POST /v1/messages -H "Authorization: Bearer $KEY" "${J[@]}" \
  -d "$(jq -nc --arg d "$DEV" '{device_id:$d,to:"+37060000000",body:"smoke test",client_ref:"smoke"}')"
MSG="$BODY"; expect 202 "create message"
MID="$(jq -r .id <<<"$MSG")"
[[ "$(jq -r .status <<<"$MSG")" == queued ]] || fail "new message status: $MSG"

DAUTH=(-H "Authorization: Bearer $DTOK")
step "pending"
req GET /device/v1/messages/pending "${DAUTH[@]}"
PEND="$BODY"; expect 200 pending
[[ "$(jq -r --arg id "$MID" '[.messages[].id]|index($id)!=null' <<<"$PEND")" == true ]] || fail "message not in pending: $PEND"

step "claim"
req POST "/device/v1/messages/$MID/claim" "${DAUTH[@]}"
CL="$BODY"; expect 200 claim
[[ "$(jq -r .body <<<"$CL")" == "smoke test" ]] || fail "claim body: $CL"

step "report sent"
req POST "/device/v1/messages/$MID/status" "${DAUTH[@]}" "${J[@]}" -d '{"status":"sent"}'
[[ "$CODE" == 200 || "$CODE" == 204 ]] || fail "status report: HTTP $CODE"

step "get message"
req GET "/v1/messages/$MID" -H "Authorization: Bearer $KEY"
GOT="$BODY"; expect 200 "get message"
[[ "$(jq -r .status <<<"$GOT")" == sent ]] || fail "final status: $GOT"

echo OK
