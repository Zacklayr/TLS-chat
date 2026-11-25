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
- Hardened the PWA shell with versioned cache cleanup, offline same-origin resource fallback, and a served install icon for Android/desktop installation.
- Added a separate `DesktopClientApp` employee client foundation with login, accessible-chat listing, message history, sending, and periodic refresh over the shared REST API.
- Added a packaged desktop-client JAR target for the employee desktop client.
- Added a native Android project foundation under `clients/android` with login, chat listing, message history, and message sending over the shared REST API; Android SDK/Gradle are unavailable in this environment, so its APK is not yet built here.
- Added `.github/workflows/android.yml` so GitHub CI builds the Android debug APK with a provisioned Android SDK.
- Added an in-process HTTP integration flow covering employee approval, login, bearer authentication, profile access, and chat listing.
- Expanded the HTTP integration flow to cover message sending, file upload/download, and session revocation; corrected empty `204` response framing.
- Added `infra/smoke-test.ps1` for post-deployment checks against Heroku or another HTTPS host.
- Made the server main process wait on an explicit shutdown latch so packaged/JAR launches remain alive after HTTP startup.
- Added profile/about and presence state, notification delivery/read state, call history, and scheduled call REST contracts.
- Added protected web admin panel at `/admin` with user status/permission management, temporary password reset, registration approval, chat upsert management, and emergency notification publishing.
- Added authenticated WebSocket transport (`WS_PORT`) for chat subscriptions, realtime messages, presence, ping/pong, and REST fallback compatibility; Docker/Nginx now routes `/ws/` to it.
- Connected stored presence to live chat member counts and exposed profile editing, call history, and scheduled-call controls in the web client.
- Added server-side GitHub commit browsing and commit comments for administrators, with the repository and token kept in `GITHUB_REPOSITORY`/`GITHUB_TOKEN` environment variables.
- Added authenticated ICE-server configuration for browser calls; optional TURN credentials are supplied only through `TURN_URL`, `TURN_USERNAME`, and `TURN_CREDENTIAL` environment variables.
- Protected Marsel photo delivery with the same authenticated-session boundary as the rest of the private application.
- Added `EncryptedBackupApp` and `infra/backup.ps1` for AES-256-GCM backups of SQLite/uploads/photos or encrypted `pg_dump` input, with the key supplied only through `BACKUP_ENCRYPTION_KEY`.
- Verified `mvn verify`: 16 tests passed and the runtime health endpoint integration test passed.
- Expanded verification to 18 passing tests, including a real WebSocket client round-trip, encrypted-backup round-trip, profile, notification, scheduled-call, admin-access, admin-page, and PWA-resource coverage.
- Heroku remains suitable for the stateless web/API process and Heroku Postgres, but uploaded files must move to object storage before production because the dyno filesystem is ephemeral.
- Added an S3-compatible attachment storage adapter; local disk remains the development default, while Heroku can use `FILE_STORAGE_MODE=s3` with bucket/region/prefix environment configuration.

The browser call flow is now wired to the signaling API, but it still requires two-client end-to-end testing and production TURN configuration. Docker Compose configuration is present but Docker is not installed in the current environment, so the container stack has not been executed here. Android and desktop call flows still require their clients.
