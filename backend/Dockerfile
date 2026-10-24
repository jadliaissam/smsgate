# Build context: backend/. Works with the classic builder (no BuildKit needed).
FROM golang:1.26-alpine AS build
WORKDIR /src
ENV CGO_ENABLED=0 GOFLAGS=-trimpath
# Dependency layer first so it is cached until go.mod/go.sum change.
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -ldflags="-s -w" -o /out/smsgate ./cmd/smsgate

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/smsgate /smsgate
# Git sha of the build (CI passes it); GET /healthz reports it as "revision".
ARG REVISION=dev
ENV GOMEMLIMIT=160MiB APP_REVISION=$REVISION
EXPOSE 8080
USER nonroot
# distroless has no shell or curl: the binary probes itself.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 CMD ["/smsgate", "-healthcheck"]
ENTRYPOINT ["/smsgate"]
