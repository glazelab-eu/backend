# Sessions, cookies and passwords

## Sessions

- `security/LoginSessions` (`login_session`) is the `SecurityContextRepository` behind the request-attribute one (`SecurityConfig.securityContexts`, `@Primary`). The servlet `HttpSession` is never used: nothing about a sign-in lives in API memory, so restarts and deployments sign nobody out.
- The cookie `GLAZELAB_SESSION` (HttpOnly, SameSite=Lax, Path `/`, Secure with `server.servlet.session.cookie.secure`, i.e. `GLAZELAB_SECURE_COOKIES`) carries a random `SecureTokens` token. The row keeps only its SHA-256 hash (primary key), the account ID, the session version and `created_at`/`renewed_at`; never an address, browser or email.
- A request loads the row once: the request attribute is set to signed out before the lookup, so a failed lookup (e.g. the database is down) is not repeated by the request's error dispatch, which then renders a plain 500. It builds an `AccountPrincipal` of ID and version only (its name is the ID).
- `SessionVersionFilter` then reloads and validates the account once per request; `TermsFilter` and `CurrentUser` reuse that request attribute, never a session-cached row. Mutations still lock through `lockUser`/repository locking methods.
- Lifetimes (`glazelab.session.*`): idle 30 days after `renewed_at`, absolute 365 days after `created_at`, both enforced by the lookup alone. A validated request whose `renewed_at` is at least `renew-after` (1 day) old moves it with a conditional `UPDATE` (of concurrent requests one wins), so other requests write nothing.
- Only a sign-in sets the cookie (Max-Age = absolute); only a sign-out or account deletion clears it; renewal never sends it. Any other answer carrying the token could be stored by a shared cache (PUBLIC images are `public, immutable`) or, arriving late, hand back a token a password change has just replaced. A browser may therefore keep a dead cookie for up to a year; it answers 401 like any unknown token.
- Every `saveContext` with an account (form login; `SessionLogin.signIn`: magic link, password change) issues a new token and deletes the row the request's cookie named: no session ID survives a sign-in, a planted cookie is never adopted, and a second save of the same sign-in in one request is a no-op. Any other context (logout, `SessionLogin.signOut` after account deletion) deletes that row and clears the cookie.
- What ends sessions:
  - sign-out (that row);
  - password change and reset (`LoginSessionRepository.endAll` in the account's transaction, after its lock; the changer gets a new token);
  - account deletion and the pending purge (FK `ON DELETE CASCADE`);
  - a stale or unverified request (`LoginSessions.endStale`: its own row only; it never touches the cookie, because a concurrent request of the same browser may just have received a new token);
  - the hourly `LoginSessions.purge` of rows past either lifetime.

## Cache clearing

- No sign-out, deletion or other answer sends `Clear-Site-Data`: the browser holds an answer carrying it while it empties its cache (seconds to tens of seconds), and a late sign-out answer would delete a new sign-in's cookie.
- `GET /api/auth/clear-cache` (`AuthController.clearBrowserCache`) is the only answer with `Clear-Site-Data: "cache"`; never `"storage"`, `"cookies"` or `"*"`, and never on a sign-in or sign-out answer.
  - In `SecurityConfig.PUBLIC_GET` (no CSRF, no session); never rate limited (`RateLimiter.kind` answers null as for `/api/health`; `ErrorResponses` lists no 429).
  - 204 `no-store`, no `Vary`; reads and writes nothing, sets no cookie, reflects nothing.
  - The header is sent only when `Sec-Fetch-Site` is absent, `same-origin` or `none` (exact values); any other request (another site's `<img>`) gets the same 204 without it, so no other site can empty a visitor's cache of this origin.

## Cookies

- Exactly two, both strictly necessary, disclosed in the terms (`terms.section.cookies`): `GLAZELAB_SESSION` and the CSRF cookie `XSRF-TOKEN` (`CookieCsrfTokenRepository` in `SecurityConfig.csrfTokens`: HttpOnly, SameSite=Lax, Path `/`, Secure with an https request, no Max-Age; set by the first `GET /api/auth/csrf`; deleted when a sign-in or password change rotates it and at sign-out). The SPA never reads it: it sends the value of `/api/auth/csrf` as `X-XSRF-TOKEN`. A guest's reads set none.
- No `JSESSIONID`, remember-me, locale, request-cache (`NullRequestCache`) or tracking cookie; never call `request.getSession()`.
- A new cookie needs the maintainers' agreement and a terms change first (new `TERMS_VERSION`, the frontend's `terms.ts`, `terms.section.cookies` in every language). `security/CookieInventoryApiTest` and the frontend's `e2e/specs/cookies.spec.ts` name the allowed cookies and fail on any other cookie or flags.

## Password change

- `POST /api/auth/password/change` (`AccountService.changePassword`): CSRF, a signed-in session and the current password; allowed with pending terms (`TermsFilter` passes `/api/auth/`); every attempt spends the per-IP AUTH budget like a sign-in.
- It locks the account row, compares the current password under the lock (400 `WRONG_PASSWORD`, nothing changes), refuses an unchanged password (400 `SAME_PASSWORD`) and applies the reset rules (`@Size(min=8, max=128)`, `encode`'s 72 UTF-8 bytes).
- `AppUser.changePassword` bumps the session version; every account token and every stored session of the account goes in the same transaction (a sign-in racing the change keeps the old version, and `SessionVersionFilter` ends it on its next request). The controller then signs the requesting session in again through `SessionLogin` (new session token and CSRF token, so the client fetches a token before its next write).
- With SMTP it publishes `PasswordChangedMail`; `mailPasswordChanged` sends the link-free notice after commit on `mailExecutor` (`NOT_SUPPORTED`: it touches no rows) and only logs a failure.
