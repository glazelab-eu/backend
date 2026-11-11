# Registration, pending accounts and tokens

## Tokens

- SHA-256 hashes only (`SecureTokens`), single-use, purpose-checked. A GET never authenticates or consumes one; confirmation is a CSRF-protected POST.
- Token lookups are scalar-only before locking the account or club.
- Expired account tokens go hourly (`PendingAccounts.expireTokens`), one transaction per account under its lock, which every issue and consumption holds.
- Raw invite and buddy links are answered only on generation (`POST /api/clubs/{id}/invite`, `POST /api/buddies/link`); the club or `buddy_link` row stores only the hash.
- `inviteVersion` (`ClubService.inviteVersion`: a hash of the stored hash, null without a link) lets a client tell its remembered link from a newer one; it cannot look the club up. In the generation answer and the `ClubView` of clubs the caller administers; null for plain members (`hasInviteLink` stays for everyone). Never expose the stored hash.

## Pending accounts

- `prod` registration requires SMTP (`glazelab.require-email-verification=true`, checked before anything is written). Mail-free signup is local-dev only: there an address belongs to whoever registers it first.
- `POST /api/auth/register` takes no password (an older client's `password` is ignored). A new account is pending with the hash of a discarded `SecureTokens` secret, so no sign-in matches.
- Sign-in compares the password before the account status (`SecurityConfig.authentication`): a pending account is never refused faster than a wrong password.
- Expiry: 24 hours (`verification_expires_at`); `PendingAccounts` purges the account and frees the address. An expired pending account cannot consume even a valid token.
- The first password is set only by activation: verification, first magic login and first reset answer a non-consuming 409 challenge until the mailbox owner supplies password and display name. Activation replaces the unusable hash, bumps the session version and clears all tokens.
- Consent comes with the activation, never with the registration (whoever registers may not own the mailbox; topic "Contract"): each activating link needs `termsAccepted` with the current `termsVersion` (400 `TERMS_NOT_ACCEPTED` after the challenge and the credential checks, nothing changed) and takes `diagnosticsOptIn` (default false). Activation records the server time and overwrites whatever the row held, so accounts that registered while registration still recorded consent are covered; the fields are ignored for an active account.
- A pending account is no club member; membership comes only from joining with an invite link while signed in. Never add members by email address (address oracle, no consent). `changeRole` answers 404 `Member not found` to promoting a pending member that has a membership row anyway.
- Purge (`PendingAccounts.remove`; registration's `expireEmail`; `expireBatch`, every 15 minutes): deletes `AccountRows` (account deletion's list) before the row, under the account lock only; never a club lock (club writes lock club → account row).
  - `expireBatch`: one transaction per account; a failure is logged by account ID and skipped.
  - Until purged, a leftover pending member counts as another member for account deletion's last-admin 409.

## Registration

- Never reveals an address, in answer or timing: new, pending, verified and banned addresses answer the same `RegistrationView`.
- Takes email and display name only; an older client's `password`, `termsAccepted`, `termsVersion` and `diagnosticsOptIn` are ignored, and a new account stores no terms acceptance and error reports off.
- The request thread does only: one hash, `EmailBlocklist.blocked` (one indexed statement, for every address), the address lookup, the insert of a new account. The insert is the one remaining, small timing difference (a banned address costs what one in use costs).
- Its lookups never lock an existing account (`existsByEmailKey`; `PendingAccounts.expireEmail` locks only an expired one): a mail task holds the account row while it issues a link, so waiting would delay exactly the addresses in use.
- Mail goes off the request thread: `RegistrationMail` → `AccountService.mailRegistration` (`@TransactionalEventListener` after commit, `@Async` on `mailExecutor`, `REQUIRES_NEW`) picks the link (pending, new included: verify; verified: reset) and applies the one-minute throttle.
  - An SMTP failure never reaches the answer; registration stays committed.
  - Every account mail (`mailRegistration`, `request`) issues its token under the account lock in a transaction of its own and commits before the send (`AccountService.deliver`); SMTP runs with no transaction and no lock, so a slow server never makes the owner's writes wait or meet the `prod` lock timeout (`AccountMailLockApiTest`, PostgreSQL variant).
  - A failed send takes its token back under the account lock again (`takeBack`): the renewed older link is restored (hash, issue and expiry time) or a new record deleted, so the older link stays valid and the throttle never blocks a resend; a token changed meanwhile stays.
  - `mailExecutor` is bounded (a full queue drops with a warning) and drains on shutdown.
- A unique violation from a concurrent registration of the same new address is retried once by `AuthController.register` and answers as an existing address, never 409.
- Without SMTP the answer's `setupToken` is the raw VERIFY_EMAIL token, only for an account this very request created (hashed in `account_token`, expires with the account, never logged); an existing address gets none and nothing changes.
