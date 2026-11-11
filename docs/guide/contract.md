# Contract with the other repository

The web app is the separate frontend repository (Svelte). This file lists the rules the API keeps for it and what changes on both sides together; the frontend guide's topic "Contract with the other repository" states the client side.

## Layout and the API document

- Check out `backend` and `frontend` as siblings (`<dir>/backend`, `<dir>/frontend`). Tests that read `../frontend` skip without it (the backend-only Docker build): `OpenApiContractTest` (`openapi.json`), `ServerMessagesContractTest` (`src/lib/i18n/server-messages.ts`), `AuthApiTest` (`src/lib/api/terms.ts`), `GlazeCodeTest` (`src/lib/glazes/code-cases.json`).
- The dev profile serves the OpenAPI document at `GET /api/openapi` (off in `prod`). The frontend keeps it as `openapi.json` (keys sorted, two-space indent) and generates its client from it; neither is edited by hand, and `mvn test` fails when the running contract differs from the snapshot.
- After any change to an endpoint, a DTO or an error status, regenerate. From the folder that holds both checkouts, with Java 21, Maven, Node 22, Python 3 and curl, and a throw-away data folder (never development or deployment data):

```sh
set -euo pipefail
work=$(mktemp -d); port=18080   # any free port
mvn -f backend/pom.xml -B -ntp package -DskipTests
PORT=$port SPRING_PROFILES_ACTIVE=dev GLAZELAB_DATA="$work/data" GLAZELAB_IMAGES="$work/images" \
  java -jar backend/target/glazelab-0.1.0.jar >"$work/server.log" 2>&1 & api=$!
trap 'kill $api; rm -rf "$work"' EXIT
for _ in $(seq 90); do curl -fsS "http://localhost:$port/api/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://localhost:$port/api/openapi" -o "$work/openapi.json"
python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1])), indent=2, sort_keys=True))' "$work/openapi.json" >frontend/openapi.json
npm --prefix frontend run api:generate && npm --prefix frontend run format
```

- Commit the JSON with the generated client in the frontend. Fix the contract here rather than letting the client weaken its types.
- Error answers are typed: each operation lists the statuses it can answer with `ErrorView` `{error}`, derived in `config/ErrorResponses`, never from per-controller annotations. A nullable `$ref` cannot be expressed, hence `GlazeCreatedView.stock` is a list of 0 or 1 (its `id` stays for older clients).

## Rules both sides keep

- Times: every API time is an `Instant` serialised with `Z` (`ApiTypesTest` refuses `LocalDateTime`); a date without a time is `YYYY-MM-DD`.
- Normalizers with shared test vectors; a change applies to both sides:
  - Tags `model/Tags` (`TagsTest`): NFC, lower case, hyphen-joined words of letters, marks and digits, accents kept; ≤ 40 characters, ≤ 20 per item.
  - Barcodes `model/Barcodes` (`BarcodesTest`): EAN-13, UPC-A or EAN-8 with a valid GS1 check digit; UPC-A stored as EAN-13 with a leading 0; no UPC-E.
  - Glaze codes `Glaze.code` (`GlazeCodeTest` over the frontend's `code-cases.json`): trim, upper case, runs of whitespace, slashes and hyphens become one hyphen.
  - Wishlist links `model/WishlistLinks` (`WishlistLinksTest` over the frontend's `lib/clubs/wishlist-links.json`): the grammar of topic "Authorization and visibility"; the client sends the browser-normalised link (`URL.href`), the server only checks.
- Terms: `AccountService.TERMS_VERSION` and the frontend's `lib/api/terms.ts` change together (`AuthApiTest`).
  - Consent is recorded at activation, never at registration (topic "Registration"): the activating link sends `termsAccepted`, `termsVersion` and `diagnosticsOptIn`; registration sends email and name only.
  - `terms.section.buddies` states exactly what a buddy sees (topic "Buddies"); `terms.section.diagnostics` what an error report carries, who reads it, the 30 days and the opt-out (topic "Error reports"); `terms.section.diagnostics.feedback` the feedback readers, photos, context and 90 days (topic "Feedback"); `terms.section.profilePicture` the picture rules and the 90-day evidence (topic "Profile pictures").
  - A change to any of these rules or retention periods changes the text in every language and, unless the maintainers decide otherwise, the version.
- Error texts: every message is one public constant in English; the frontend's `lib/i18n/server-messages.ts` translates each and changes with the wording (`ServerMessagesContractTest` fails on a message without a key). Of the 429 texts the client keeps only `SignInDelays.DELAYED`.
- Tarpit decoy (topic "Security"): only an unknown picture's 404 may become a `200 application/octet-stream` decoy; the client ignores any 2xx that is not JSON (or not `image/*` for a picture).
- String enums keep their values on both sides (the frontend's `lib/api/enums.test.ts` checks its lists against `openapi.json`):
  - Image kind `TEST_TILE`/`FINISHED`/`PACKAGING`/`IN_PROGRESS`; visibility `PUBLIC`/`CLUB`/`PRIVATE`; step type `GLAZE`/`CUSTOM`; buddy relation `NONE`/`OUTGOING`/`INCOMING`/`BUDDIES`/`SELF`.
  - Profile pictures: report status `OPEN`/`DISMISSED`/`ACTIONED`/`CLOSED`; picture state `CURRENT`/`REPLACED`/`OWNER_REMOVED`/`ADMIN_REMOVED`/`ACCOUNT_DELETED`.
  - Error reports: kind `ERROR`/`WARNING`/`UNHANDLED_REJECTION`; device facts `COARSE`/`FINE`/`NONE`, `UNSUPPORTED`/`NONE`/`ACTIVE`, `LIGHT`/`DARK`.
  - Feedback: status `OPEN`/`DONE`; device `PHONE`/`TABLET`/`DESKTOP`.
  - Activity feed entry type `BUDDY_COMBINATION`/`BUDDY_GLAZE`/`NEW_BUDDY`/`BUDDY_REQUEST`/`CLUB_COMBINATION`/`CLUB_MEMBER`/`CLUB_GLAZE`.
  - Club wishlist entry type `GLAZE`/`ITEM`.
- Sync (topic "Sync"): the client sends `cursor`, `epoch` and `format` back unchanged and loads in full after a `resync` answer; `SyncService.FORMAT` changes with the stored view shapes; `StepView.manufacturer`/`name` mirror the glaze, so a rename re-sends only the `GlazeView`.
- Activity feed: `UserView.feedUnread`/`feedBuddies`/`feedClubs` ride on every `/me`; `GET /api/feed/unread` is the app's only poll; `GET /api/feed` pages by an opaque cursor; `POST /api/feed/seen` takes the newest shown entry's time.
- Feedback: `POST /api/feedback` is multipart (`message`, optional `route`/`build`/`language`/`device`, up to three `files`), within the 12 MB multipart limit; the server re-encodes the photos.
- Profile pictures: `avatarId` rides on `UserView`, `MemberView`, `BuddyView` and `BuddyLinkPreviewView`; authors' pictures come only from `GET /api/combinations/author-avatars`, never from a synced view.
- Stale writes (topics "Stock", "Steps"): the client sends only what the person changed: size-only `POST /api/stock`, `requestId` on a resent addition, `baseTags` on a combination PUT.
- Account and invite links are the frontend's hash routes `#/verify|reset|login|invite/<token>`, built by `AccountMail`.

## What the proxies in front must keep

- They overwrite `X-Forwarded-For` with the client address and never pass a client-supplied one: rate limiting lives only here and trusts that chain. No proxy limits `/api/`.
- They cap bodies as `BodySizeFilter` does (128 KB, 13 MB on the upload paths) and answer an oversized `/api/` body with `{"error":"This request is too large."}`.
- Answers with a secret or reflected request text (`/api/auth/`, `/api/images/`) stay uncompressed (`gzip off`); the API itself does not compress.
- CLUB/PRIVATE images and profile pictures are `private` with `X-Accel-Expires: 0`: never stored by a proxy cache, never `Vary`-keyed for a shared cache; their one-week browser lifetime is the accepted revocation window.
