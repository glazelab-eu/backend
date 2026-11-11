# Glazelab backend guide

The Glazelab API: Java 21, Spring Boot 4, Spring Data JPA, Spring Security with database-stored sign-ins, package `eu.glazelab.api`. The web app is the separate frontend repository; the contract with it, the client generation and the side-by-side checkout are in `docs/guide/contract.md`.

This file holds what every backend task needs; the topic files under `docs/guide/` ("Topics" below) hold the rest: read those whose trigger matches the task before editing. `topic "Sync"` in a topic file names a Topics entry of this guide. A rule marked "product decision" is deliberate: discuss it with the maintainers before changing it.

## Commands and contributing

- Java 21+, Maven 3.9+. Run: `mvn spring-boot:run` (8080, `dev` profile). Tests: `mvn test` (`mvn clean test` after moving packages); one class: `mvn -Dtest=<Class> test`; optional PostgreSQL and S3 runs: `tests.md`.
- Small commits with short imperative subjects; changed behaviour gets an HTTP test under `src/test/java/`; a changed rule updates its topic file in the same commit (one rule per bullet, no history).

## Architecture

- Profiles `dev` (default: H2, OpenAPI on) and `prod` (PostgreSQL, OpenAPI off, secure cookies). Shared settings stay in `application.properties`; secrets only via env.
- Packages `model`, `repository`, `controller`, `service`, `dto`, `security`, `storage`, `exception`, `config`; no feature sub-packages. `GlazelabApplication` stays in the root package.
- A feature starts at `controller/<Feature>Controller` (`Catalog` = glazes and stock), continues in `service/` where a workflow lives there, then the same-named entity and repository. Auth and CSRF: `security/SecurityConfig`; sign-ins: `security/LoginSessions`; every glaze and combination view: `service/LibraryViews`; schema: the newest migration (`resources/db/migration/` SQL or Java under `src/main/java/db/migration/`).
- Same-origin sessions with CSRF, stored in the database (`login_session`); the servlet `HttpSession` is never used, so restarts sign nobody out.
- Every authorization and validation rule is enforced here, whatever the frontend also checks. API rate limiting lives only here, never in a proxy (`contract.md`).

## Code rules

- Four-space indentation. Controllers return explicit `*View` records from `dto/`, never entities or `Map`s; nullable fields are annotated; inputs may stay in the controller.
- Repositories, not raw SQL in controllers. `open-in-view` is off: read lazy relations inside transactions. Add a `service/` class for reuse, not for symmetry; a service that creates something returns its view from its own transaction.
- Error answers are `ErrorView`; their documented statuses come from `config/ErrorResponses`, never from a per-controller annotation. The security layer writes errors through `SecurityConfig.error`.
- An error message is one public constant; the frontend's `lib/i18n/server-messages.ts` changes with each wording (`ServerMessagesContractTest`).
- A new endpoint: a club ID follows `authorization.md`; statuses and public routes are derived in `config/ErrorResponses` (`code-rules.md`); check its rate-limit class (`security.md`); a body with a secret or reflected request text needs a `gzip off` location in the proxies; then regenerate the client (`contract.md`; `mvn test` fails on contract drift).
- Compare enums, never strings. Lombok: `@Getter`/`@Setter` only for exposed accessors, `@EqualsAndHashCode(onlyExplicitlyIncluded = true)` on the assigned ID, never `@Data`; beans use `@RequiredArgsConstructor`; records stay records.
- Tests are HTTP tests `controller/<Feature>ApiTest` on `support/ApiTestBase`; shared helpers live there. `mvn test` runs four JVMs in parallel: tests use dynamic ports, never fixed ports or shared files; the test classes of a JVM share its in-memory database, so assert on your own IDs.

## Hard rules

Security and privacy:

- Never enable broad CORS or disable CSRF. Register security filters only in the security chain. Authenticated lookups use the principal's immutable UUID, never its email.
- Never call `request.getSession()`. Exactly two cookies exist (`GLAZELAB_SESSION`, `XSRF-TOKEN`); a new cookie needs the maintainers' agreement and a terms change first. Only a sign-in sets the session cookie; only a sign-out or account deletion clears it.
- Tokens are stored as SHA-256 hashes only, single-use and purpose-checked. A GET never authenticates or consumes a token.
- Nothing confirms what the caller may not know: registration answers the same for a new, pending, verified and banned address (answer and timing); a club or draft the caller cannot see answers exactly like an unknown one; a hidden photo is 404.
- Logs carry no row values: never an email address, client IP address, token, secret, display name, request body, free text, file name, path variable, query string or an exception message that can hold one. A new log line gets a capture test.
- One copy of each visibility rule: `ImageRecord.visibleTo` and `deletableBy` (photos), `Stock.removableBy`, `BuddyRepository.AVAILABLE_STOCK` (what a buddy sees), `CombinationService.hasForeignPhotos`. Call them, never restate them.
- Site admins are granted only with SQL; no endpoint sets the flag.
- A retention period or visibility rule the terms promise (buddies, error reports 30 days, profile-picture evidence 90 days, admin actions 90 days, feedback 90 days) changes only with the terms text, in every language; discuss it with the maintainers first.
- Rate limits classify requests by the decoded path Spring MVC matches, never the raw URI.

Data and concurrency:

- Lock order: actor account → destination clubs by ID → referenced glazes by ID → combinations by ID → image rows by ID → profile picture and evidence rows. Two accounts lock in `BuddyId.ORDER`. Nothing locks an account row after a club. Never lock accounts `FOR UPDATE`. Read `docs/guide/locks.md` before adding or changing a write.
- A membership or role that gates a club write is read after the club lock; a non-member never takes a club lock.
- Uploads encode and store outside database locks; stored objects are removed only after commit (`ImageRemovalService`), and a picture is always two objects (full size and card).
- Every write that changes what a `GlazeView` or `CombinationView` shows records it through `ChangeRecorder` inside its transaction; a new write operation needs a recording scenario or an `EXEMPT` reason in `SyncCoverageApiTest`; a changed stored view shape bumps `SyncService.FORMAT`.
- Every schema change is a new Flyway migration; never edit an applied one. Name every constraint (unnamed ones differ between H2 and PostgreSQL). IDs and foreign keys are native `UUID` columns. A migration ships only in a planned release with a database backup; details under "Schema" (`tests.md`).
- A new table that names an account joins account deletion (`AccountRows` or an FK cascade) and `BanApiTest.footprint`; a ban runs the very same removal.

## Topics

Files in `docs/guide/`; read when the trigger applies. Names in quotes are the titles other files cite.

- `contract.md` ("Contract with the other repository", "Contract"): a new or changed endpoint or DTO, regenerating the client, normalizers, the terms version, error texts, shared enums, what the proxies must keep.
- `code-rules.md` ("Layers and finding your way", "Code rules"): the full feature-to-class map, the `prod` database password check, DTO files, Lombok details.
- `security.md` ("Security"): error constants, a new endpoint's rate-limit class, rate-limit budgets and the tarpit, `TermsFilter` and its two exceptions, site admins and the operator contact, PostgreSQL error detail in logs.
- `sessions.md` ("Sessions", part of "Security"): `LoginSessions`, session lifetimes and renewal, the two cookies, `SessionVersionFilter`, password change, `GET /api/auth/clear-cache`.
- `accounts.md` ("Registration", part of "Security"): registration, mail sending, pending accounts and their purge, account tokens, invite and buddy link tokens.
- `authorization.md` ("Authorization and visibility"): who may see or change photos, drafts, combinations, clubs (members, roles, leaving, removal, invites) and stock; any endpoint that takes a club ID.
- `locks.md` ("Locks and transactions", "Locks"): any new or changed write, lock or transaction; uploads, visibility changes, account deletion, session rows.
- `combinations.md` ("Steps", part of "Domain invariants"): steps, drafts and publishing, variations, final firing, combination edit and delete.
- `stock.md` ("Stock", part of "Domain invariants"): shelf entries, club stock, `added_by`, jar additions, removal rights.
- `glazes.md` (part of "Domain invariants"): glazes and manufacturers, glaze corrections and their history, barcodes, tags.
- `photos.md` ("Photos", part of "Domain invariants"): uploads and encoding, image downloads and cache headers, photo details and visibility changes, deletion and its log, photo reports, storage (file and S3).
- `account-deletion.md` ("Account deletion", part of "Domain invariants"): what account deletion removes and keeps; any new table that references an account.
- `buddies.md` ("Buddies"): buddy pairs, links, requests, availability, the keep-private flag.
- `profile-pictures.md` ("Profile pictures"): avatars, their reports, evidence, blocks and the admin report endpoints.
- `bans.md` ("Bans"): banning and unbanning, the email blocklist and its secret, address normalisation, `admin_action`.
- `error-reports.md` ("Error reports"): the diagnostics opt-in, `POST /api/reports`, scrubbing, the admin reader.
- `feedback.md` ("Feedback"): `POST /api/feedback` and the admin feedback endpoints.
- `activity-feed.md` ("Activity feed"): the feed query (derived at read time: never store feed events), its endpoints, the unread badge; any "what's new" or notification idea.
- `sync.md` ("Sync"): `GET /api/sync`, `ChangeRecorder`, cursors, resync answers, the epoch; any write that changes a glaze or combination view.
- `favourites.md` ("Favourite totals"): `GET /api/favorites/counts`.
- `admin-users.md` ("Admin user list"): `GET /api/admin/users`.
- `logs.md` ("Logs"): adding or changing a log line; what each level carries.
- `licensing.md` ("Licences"): a new or changed dependency, `THIRD-PARTY.txt`, the source offer (`GLAZELAB_SOURCE_URL`), the build label in `/api/health`.
- `tests.md` ("Tests", "Schema"): test helpers and fixtures, PostgreSQL- and S3-gated tests, tests that block pictures or addresses, sync and session tests; a new migration (constraint names, `MigrationTest`, the H2 check-constraint gotcha).
