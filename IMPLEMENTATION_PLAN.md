# TLS-Chat implementation plan

This file tracks implementation work for the full messenger scope described in `планы.txt`.
`README.md` is intentionally not modified.

## Wednesday, 25 November 2026 — server foundation

- Freeze the current baseline and keep the existing HTTP API working.
- Harden authentication and session handling.
- Finish the company/department/region/role access model.
- Define REST contracts for messages, files, presence, notifications, and calls.
- Move production persistence toward PostgreSQL and keep SQLite for local development.
- Add focused tests for authorization boundaries and message lifecycle.

## Thursday, 26 November 2026 — communication features

- Add real-time transport for messages, presence, delivery/read events, and notifications.
- Add file upload/download metadata and safe storage boundaries.
- Add call signaling primitives and the WebRTC session model.
- Add UI controls and explicit disabled/error states for unfinished media operations.

## Friday, 27 November 2026 — clients and deployment

- Add the deployable web/PWA experience for the completed server contracts.
- Prepare Android and desktop client foundations using the shared API.
- Add Docker Compose services for backend, PostgreSQL, reverse proxy, and supporting services.
- Add deployment smoke tests and verify Heroku compatibility.

## Completion rule

Only features that compile, have tests where practical, and are exercised through the running application are marked complete. Video calls, file transfer, Android, and desktop packaging require external infrastructure and will remain explicitly tracked if that infrastructure is not available.

## Progress recorded on 25 November 2026

- Implemented session revocation through `POST /api/logout` and added the web logout control.
- Added `GET /health` for deployment health checks.
- Added authenticated file upload/download metadata and storage boundaries (`POST /api/files`, `GET /api/files/{id}`).
- Added call session and WebRTC signaling persistence and REST endpoints (`POST /api/calls`, `GET/POST /api/calls/{id}/events`).
- Added browser call controls with audio/video capture, SDP/ICE exchange, incoming-call polling, and call cleanup.
- Added Dockerfile, Docker Compose services for PostgreSQL/backend/reverse proxy, persistent upload volumes, and WebSocket-compatible proxy headers.
- Added GitHub Actions Maven CI workflow and ignored local upload/runtime data.
- Added repository coverage for session revocation, attachment metadata, and call events.
- Added installable web/PWA shell (`manifest.webmanifest`, service worker) and responsive call-window styling.
- Verified `mvn verify`: 15 tests passed and the runtime health endpoint integration test passed.
- Heroku remains suitable for the stateless web/API process and Heroku Postgres, but uploaded files must move to object storage before production because the dyno filesystem is ephemeral.

The browser call flow is now wired to the signaling API, but it still requires two-client end-to-end testing and production TURN configuration. Docker Compose configuration is present but Docker is not installed in the current environment, so the container stack has not been executed here. Android and desktop call flows still require their clients.
