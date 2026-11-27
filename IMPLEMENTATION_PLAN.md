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
- Extended the Android client and packaged desktop client with shared call-history viewing and scheduled audio-call actions; Android now also has an embedded HTTPS WebRTC client, while native Swing media capture remains a separate milestone.
- Added attachment upload controls to both Android and desktop clients using the authenticated `POST /api/files` contract and the shared 25 MB safety limit; successful uploads are posted into chat history as downloadable links.
- Added `.github/workflows/android.yml` so GitHub CI builds the Android debug APK with a provisioned Android SDK.
- Added `.github/workflows/docker.yml` so every main-branch push and pull request builds the deployable backend image and inspects its runtime metadata.
- Extended Docker Compose with environment-driven S3, TURN, registrar, admin, and credential settings; secrets remain outside the repository.
- Added an in-process HTTP integration flow covering employee approval, login, bearer authentication, profile access, and chat listing.
- Expanded the HTTP integration flow to cover message sending, file upload/download, and session revocation; corrected empty `204` response framing.
- Added `infra/smoke-test.ps1` for post-deployment checks against Heroku or another HTTPS host.
- Made the server main process wait on an explicit shutdown latch so packaged/JAR launches remain alive after HTTP startup.
- Added profile/about and presence state, notification delivery/read state, call history, and scheduled call REST contracts.
- Added protected web admin panel at `/admin` with user status/permission management, temporary password reset, registration approval, chat upsert management, and emergency notification publishing.
- Added authenticated WebSocket transport (`WS_PORT`) for chat subscriptions, realtime messages, presence, ping/pong, and REST fallback compatibility; Docker/Nginx now routes `/ws/` to it.
- Added realtime WebSocket push for broadcast and targeted admin notifications; REST polling remains the fallback for Heroku/direct deployments.
- Added stale-call cleanup: unanswered ringing calls become `MISSED` after two minutes and reject late signaling events; repository coverage verifies the lifecycle.
- Connected stored presence to live chat member counts and exposed profile editing, call history, and scheduled-call controls in the web client.
- Added server-side GitHub commit browsing and commit comments for administrators, with the repository and token kept in `GITHUB_REPOSITORY`/`GITHUB_TOKEN` environment variables.
- Added authenticated ICE-server configuration for browser calls; optional TURN credentials are supplied only through `TURN_URL`, `TURN_USERNAME`, and `TURN_CREDENTIAL` environment variables.
- Protected Marsel photo delivery with the same authenticated-session boundary as the rest of the private application.
- Added `EncryptedBackupApp` and `infra/backup.ps1` for AES-256-GCM backups of SQLite/uploads/photos or encrypted `pg_dump` input, with the key supplied only through `BACKUP_ENCRYPTION_KEY`.
- Verified `mvn verify`: 33 tests passed, including the runtime health endpoint, authenticated message/file/session integration flow, two-party call lifecycle, chunked-request limit, security-boundary checks, deterministic helpdesk triage, and Heroku gateway routing.
- Verification includes a real WebSocket client round-trip, encrypted-backup round-trip, profile, notification, scheduled-call, admin-access, admin-page, PWA-resource, and attachment-storage coverage.
- Heroku remains suitable for the stateless web/API process and Heroku Postgres, but uploaded files must move to object storage before production because the dyno filesystem is ephemeral.
- Added an S3-compatible attachment storage adapter; local disk remains the development default, while Heroku can use `FILE_STORAGE_MODE=s3` with bucket/region/prefix environment configuration.
- Added browser-side media compression before upload (images to 1920px JPEG; supported videos to 1280px WebM with automatic original-file fallback) and an offline mini-game fallback available from the login shell.
- Added realtime typing indicators over the authenticated WebSocket channel with membership checks and a REST-compatible fallback path.
- Added baseline browser/API security headers, including CSP, clickjacking protection, MIME sniffing protection, referrer policy, camera/microphone permissions, and proxy-aware HSTS.
- Added automatic membership reconciliation after registrar/admin role changes; suspended users lose group access and active sessions, while direct-chat membership is preserved for later reactivation.
- Added a server-side scheduled-call dispatcher that claims due calls once and delivers notifications to both participants.
- Added an optional Docker Compose Cloudflare Tunnel profile for public HTTPS access without inbound port forwarding; the tunnel token remains environment-only.
- Relaxed direct-chat creation for peer employees while retaining the CEO invitation boundary, and exposed the authenticated employee directory needed to start those conversations.
- Extended Android and desktop clients with call-session controls (start audio/video session, poll incoming calls, accept, and end) over the shared signaling API; Android also exposes the embedded HTTPS WebRTC client, while Swing still requires a browser/WebRTC handoff.
- Extended the deployment smoke test with authentication-boundary and security-header checks, and added GitHub workflows for verifying and publishing the Heroku instance after deployment.
- Added a two-user HTTP integration test covering call creation, incoming-call visibility, acceptance, active state, and clean termination.
- Enforced the `hidden_data` permission on the employee directory: ordinary users receive only public profile fields, while authorized users may receive phone numbers; the HTTP integration test verifies both denial by default and authorized visibility.
- Hardened browser WebRTC signaling against network reordering by queueing ICE candidates until the remote SDP description is installed, then flushing the queue before negotiation continues.
- Re-ran the published Heroku smoke test successfully: health, PWA assets, authentication boundaries, admin route, photo protection, and security headers all passed; the latest local hardening remains unpublished until the GitHub deployment workflow runs.
- Added retry/backoff to browser call-event polling so temporary Heroku REST failures do not silently terminate signaling; the UI shows reconnection state and retries up to five seconds.
- Hardened incoming-call polling against transient network/JSON/rejection failures so the recurring 2.5-second check remains alive.
- Extended the two-party HTTP call integration test to verify the authenticated ICE-server contract before call creation.
- Extended the Heroku smoke test to verify the published page contains WebRTC call signaling, PWA service-worker registration, and typing-indicator client code; the live Heroku smoke run passed.
- Extended deployment security checks so anonymous access to `/api/users` and `/api/calls/ice-servers` is rejected; the live Heroku smoke run passed again.
- Added employee-directory driven direct-chat creation to the Desktop and Android clients through the shared `/api/users` and `/api/chats/direct` contracts; server-side CEO and membership restrictions remain authoritative.
- Added profile/about and presence editing to the Desktop and Android clients through the shared `/api/profile` contract; Java tests remain green, while Android APK compilation is delegated to the configured GitHub workflow because the local SDK is unavailable.
- Extended the authenticated HTTP integration flow to verify profile read/update behavior for `about` and `presence`.
- Expanded the Registrar desktop panel with visual assignment controls for CEO and all planned specialist leadership permissions (engine, integrations, porting, support, cybersecurity, language, equipment, wellbeing, design, game design, scenario, and sound).
- Added role checkboxes to the web admin panel for the same full permission set; selected roles continue to be submitted through the existing server-side permissions contract.
- Hardened browser call failure cleanup: denied camera/microphone access now rejects or ends the server call immediately, and ICE event delivery errors are surfaced without unhandled Promise failures.
- Added separate scheduled-audio and scheduled-video actions to the Desktop and Android clients; both use the existing server dispatcher and retain the 10-minute convenience default.
- Added notification polling to Desktop and Android clients: emergency notices use a modal warning, ordinary notices update status, and displayed notices are marked read through the shared API.
- Added configurable scheduling delay (1 minute to 7 days) for audio/video calls in the Desktop and Android clients instead of forcing the previous 10-minute value.
- Extended the HTTP integration flow to cover scheduled video-call creation, participant listing, and cancellation.
- Connected the desktop client to the authenticated WebSocket transport for realtime chat refresh, typing/notification reactions, and automatic REST fallback when a WebSocket endpoint is unavailable.
- Added the same authenticated WebSocket/reconnect-safe fallback integration to the Android client; Android still requires the configured GitHub workflow to compile because the local SDK is unavailable.
- Android reconnects its realtime transport from the existing notification polling loop after a transient WebSocket failure, while REST remains available during recovery.
- Added a desktop "Open WebRTC client" action that hands off audio/video media capture to the shared HTTPS WebRTC web client in the system browser; Swing remains the native chat/signaling shell.
- Extended automatic membership assignment so every configured specialist leadership permission (`*_lead`) receives the leadership chat, not only CEO and department lead roles.
- Added explicit authenticated WebSocket `read` events with chat membership checks and anonymous-chat privacy handling; the browser emits the event when opening a chat while REST remains the authoritative read fallback.
- Added authenticated WebSocket `delivered` events alongside message broadcasts; the browser records delivery state without exposing sender identity in the anonymous chat.
- Desktop and Android clients now emit the same read event after loading a chat, without exposing identity in anonymous chat.
- Added administrator chat membership management: the protected admin API and panel can now list members and explicitly add or remove approved employees from any chat.
- Added `unless-stopped` restart policies to the PostgreSQL, backend, reverse-proxy, and optional tunnel services in Docker Compose for VPS recovery after process or host restarts.
- Added a one-time 60-second browser handoff endpoint so the Desktop client can open the HTTPS WebRTC client with its authenticated session without putting the persistent bearer token in the URL; the endpoint is covered by the HTTP integration test.
- Unified authenticated web-client activation so restored, Android-handoff, and Desktop-handoff sessions hide the empty-state overlay and load the chat workspace consistently.
- Corrected realtime presence for multi-device users: closing one WebSocket no longer marks the employee offline while another authenticated connection remains; the WebSocket integration test covers the last-connection transition.
- Applied the same multi-device rule to REST logout: it changes presence to offline only when no authenticated realtime connection remains.
- Added short-lived single-use realtime tickets: the browser requests a ticket over authenticated HTTPS and no longer places the persistent bearer token in its WebSocket URL; legacy Desktop/Android WebSocket token authentication remains supported during client rollout.
- Updated the Android native realtime client to request and consume the same one-time ticket before opening its WebSocket; its REST fallback remains active if ticket or socket setup fails.
- Updated the packaged Desktop client to obtain the realtime ticket off the Swing event thread before opening its WebSocket; the REST refresh loop remains the fallback.
- Extended Desktop and Android realtime handlers to surface the authenticated `delivered` event instead of silently ignoring it.
- Wired presence events through Web, Desktop, and Android clients so online-state changes refresh chat summaries immediately.
- Unified the helpdesk automatic response across REST and WebSocket message paths, so realtime users receive the bot acknowledgement before a support agent responds.
- Unified Marsel's calming bot response across REST and WebSocket message paths as well.
- Added deterministic helpdesk triage categories for access, calls, files, messaging, incidents, and security; security incidents are marked urgent for human escalation.
- Improved the web admin panel with dashboard counters, searchable user/registration/chat tables, and status/type filters.
- Extended the Android GitHub workflow with manual dispatch and a 14-day `app-debug.apk` artifact upload, so a successful CI build produces a downloadable APK for verification.
- Extended the Java CI workflow with a 14-day artifact upload for the packaged Desktop and Registrar JAR clients after a successful `mvn verify` run.
- Hardened GitHub workflows with explicit `contents: read` permissions for CI, Android, Docker, and backup jobs; deployment secrets remain environment-scoped.
- Added Android registration and administration panels: employees can submit registration requests, while administrators can authenticate, review/approve/reject registrations, inspect users, and update status/permissions through the existing protected API.

The browser and embedded Android call flows are wired to the signaling API, but they still require two-client end-to-end testing and production TURN configuration. Docker Compose configuration is present but Docker is not installed in the current environment, so the container stack has not been executed here. The standalone Swing client exposes REST call signaling controls but does not yet capture camera/microphone media natively.

Infrastructure verification on 25 November 2026: the Heroku `Procfile` still starts the packaged Java server using the configured `PORT`, and the published Heroku instance returned HTTP 200 for `/health` and `/`. The published page contains the WebRTC, typing-indicator, and service-worker client paths. Docker/Compose was structurally reviewed, but cannot be executed locally because Docker is not installed; TLS for the Compose deployment still depends on Cloudflare Tunnel or another external TLS terminator.

## Current completion matrix

### Confirmed complete in source and local verification

- Company registration with registrar approval, temporary credentials, status changes, permission assignment, automatic role/region/department membership, and protected employee access.
- REST chat/message/file/profile/presence/notification/call contracts with authorization checks.
- Browser PWA shell, offline fallback, compressed media uploads, typing indicators, WebSocket transport, and WebRTC signaling UI.
- Browser WebSocket authentication now uses a 60-second single-use ticket issued by the REST API; the ticket flow is covered by HTTP and WebSocket integration tests.
- Admin web panel with user access management, chat/rule management, manual chat membership management, emergency notifications, and optional GitHub commit comments.
- Desktop and Android REST clients with chats, messages, files, profiles, notifications, scheduled calls, call signaling, and WebSocket fallback integration.
- Desktop WebRTC handoff now reuses the authenticated Desktop session in the browser through a single-use expiring code, avoiding a second login while keeping the bearer token out of the URL.
- Android native login now hands the authenticated session to the embedded HTTPS WebRTC WebView through same-origin local storage, so users do not have to log in twice and the token is not placed in the URL.
- PostgreSQL/Docker/VPS configuration, encrypted backup utility, S3 attachment adapter, Heroku smoke checks, and 33 passing Maven tests.

### Implemented but requiring external verification or deployment

- Two-client browser audio/video call: requires two authenticated devices and a production TURN service for reliable NAT traversal.
- Desktop and Android APK/native client builds: source and CI workflows exist, but local Android SDK/Gradle are unavailable; the embedded Android WebRTC path is source-verified but not APK-tested here.
- Docker Compose Hosted Mode: manifests and restart policies exist, but Docker is unavailable locally.
- Latest local changes on the public Heroku URL: require the normal GitHub commit/deployment cycle before live smoke can prove them.

### Not yet complete

- Native camera/microphone media capture inside the standalone Swing client remains incomplete. Android now includes an embedded HTTPS WebRTC client with runtime camera/microphone permissions, so audio/video calls are available inside the APK; a fully native Android WebRTC engine remains a later hardening step.
- Successful execution of the scheduled backup workflow; the repository now provides the workflow, smoke, and backup tooling, but provider credentials and the GitHub environment secrets remain external concerns.

The PostgreSQL secret hardening and `.env.example` configuration template are complete. They are listed here only as prerequisites for a real deployment: the operator still needs to provide `POSTGRES_PASSWORD`, `DATABASE_URL`, and the other environment secrets outside the repository.

The SQLite chat upsert was hardened so admin edits preserve existing chat membership and message history; a regression test now covers this invariant.

The repository now also contains a Heroku deployment workflow at `.github/workflows/heroku-deploy.yml`. It deploys automatically on pushes to `main` and can also be started manually, but only after a clean Java 25 `mvn verify`; it requires `HEROKU_API_KEY` in the protected `heroku` GitHub environment, while `HEROKU_APP_URL` is optional because the default URL is derived from the app name. No credentials are committed.

The scheduled backup workflow at `.github/workflows/backup.yml` runs a daily encrypted PostgreSQL dump and retains the encrypted artifact for 30 days. It requires `DATABASE_URL` and a base64-encoded 32-byte `BACKUP_ENCRYPTION_KEY` in the protected `heroku` GitHub environment.

The backup script now builds its Java classpath with the host platform's path separator, so the Ubuntu-based GitHub backup runner uses `:` while local Windows execution uses `;`.

Compose now binds its plain HTTP proxy to loopback only; public access is expected through Cloudflare Tunnel or another TLS-terminating reverse proxy, preventing accidental external exposure of the unencrypted hop.

The `/health` endpoint now verifies the database connection and returns `503` with `status=unavailable` when the backend is not ready, instead of reporting a false healthy state.

User login now throttles five consecutive failures per client address and username for 15 minutes, returning `429` while blocked; a server-level HTTP regression test covers this boundary.

The helpdesk path still provides an automatic first response from the built-in bot and routes support staff separately; replacing that deterministic responder with an external AI provider remains optional future work and is not required for the closed-network MVP.

API contexts now reject JSON request bodies above 64 KB—including chunked requests without `Content-Length`—before handler parsing, while file uploads retain the 25 MB streaming limit; HTTP regression tests cover both JSON boundaries.

Live verification on 25 November 2026: `https://avengers-chat-8022390115f6.herokuapp.com` returned HTTP 200 for both `/health` and `/`; the published page still contains WebRTC, Service Worker, and typing-indicator paths and returns CSP, clickjacking, MIME-sniffing, referrer, and camera/microphone permission headers. This verifies the currently deployed revision only; it does not include unpushed local changes.

Repeated the full HTTPS smoke test after the latest local hardening: the currently published Heroku revision passed health, PWA assets, protected API boundaries, admin route, private photo protection, and security-header checks. Local login throttling, health-readiness behavior, Android WebView calls, and Compose binding changes still require the GitHub deployment workflow to publish them.

## Heroku realtime transport follow-up

- Added `HerokuGateway` and changed the Heroku Procfile to expose one `$PORT`: normal HTTP is proxied to a private backend port, while `Upgrade: websocket` is proxied to the private Java-WebSocket port. This addresses the audit finding that a separately bound `WS_PORT` cannot be reached through the Heroku router.
- `ChatServer` accepts the gateway's internal WebSocket port through a system-property fallback, while the existing `WS_PORT` environment variable remains supported for direct/container deployments.
- Realtime broadcasts now re-check current chat membership before delivery, so a stale WebSocket subscription does not keep receiving messages, typing, or read events after access is revoked.
- Anonymous-chat typing events now use a generic participant label instead of the employee's display name.
- Required verification after deployment: authenticated browser/desktop WebSocket handshake must return `101 Switching Protocols`, then a two-client message, typing, read, and call-signaling smoke test must pass. The audit's invalid-ticket 404 was not an authorized scenario and does not prove authentication behavior.

README remains unchanged by request. Its legacy architecture/checklist text should be reconciled with the current Heroku deployment and the implemented/verified/planned matrix only after explicit approval.
