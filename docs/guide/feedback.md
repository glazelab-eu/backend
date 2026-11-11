# Feedback

- Rule (product decision; the terms sentence `terms.section.diagnostics.feedback` says the same): signed-in accounts only (guests 401), read only by site admins (the operator: names shown there need no terms change). Kept until a site admin deletes it, or `glazelab.feedback.retention` (90 days, named in the terms) after it was marked done; deleted at once with the account (deletion, ban, pending purge: `AccountRows` → `FeedbackService.removeOf`; `BanApiTest.footprint` counts it).
- `POST /api/feedback` (`FeedbackController`; multipart, CSRF, current terms, FEEDBACK budget) answers `IdView`:
  - `message`: required; CRLF read as LF (a browser's multipart encoding sends CRLF, the app counts LF); `Text` multi-line, stripped; 1 to 2000 characters (400 `MESSAGE_REQUIRED`/`MESSAGE_TOO_LONG`);
  - optional context: `route` (a route NAME `[a-z][a-z0-9-]*`, never a link), `build`, `language` (BCP 47), `device` (`PHONE`/`TABLET`/`DESKTOP`); a malformed one is 400 `INVALID_INPUT`;
  - up to `FeedbackPhoto.MAX_PHOTOS` 3 `files` (400 `TOO_MANY_PHOTOS`).
  - The parts are `@RequestPart` strings, never `@RequestParam`: springdoc would document those as query parameters, and a 2000-character text in a URL reaches access logs.
  - Photos take the photo pipeline: `ImageEncoder.encodeAll` (the photo limits and sniffing for each, ONE encoder permit for all, ≤ 1600 px without metadata plus the card copy) and `ImageController.storeObjects` (all objects before the transaction, all removed when anything fails). The request fits the 12 MB multipart limit because the app shrinks photos first.
  - `FeedbackService.send` locks the sender's account row, then inserts (an account deletion queued before leaves nothing).
- Storage (`feedback`, `feedback_photo`, V35): `fk_feedback_user` ON DELETE CASCADE, `ck_feedback_status` (`OPEN`/`DONE`), `ck_feedback_done` (a time exactly when done), `ck_feedback_device`; photos `fk_feedback_photo_feedback` ON DELETE CASCADE, `uq_feedback_photo_key`, `uq_feedback_photo_index`, `ck_feedback_photo_index` 0..2.
  - Every deletion (admin, account, purge) reads the photos' keys first and deletes the objects after commit (`ImageRemovalService.deleteObjectsAfterCommit`).
  - The daily `FeedbackService.purge` logs the count only. Never log a text, context value or file name.
  - Not synced (writes `EXEMPT` in `SyncCoverageApiTest`).
- Site admins (`CurrentUser.siteAdmin`, 401/403):
  - `GET /api/admin/feedback?page&status` → `FeedbackPageView {entries, hasMore, open}`: newest first, `ImageDeletionController.PAGE_SIZE`; three statements (the slice with the sender's current display name, the page's photos, a count of all open ones); never an email.
  - `PUT /api/admin/feedback/{id}/status {status}`: DONE sets `done_at` only when it was open (a repeated DONE keeps the first time), OPEN clears it; 404 `FEEDBACK_NOT_FOUND`. `DELETE /api/admin/feedback/{id}`.
  - `GET /api/admin/feedback/photos/{id}` and `/card`: `no-store, private`, `X-Accel-Expires: 0`; 404 `IMAGE_NOT_FOUND`.
- Tests: `FeedbackApiTest`, `MigrationTest` (V35), `RateLimitApiTest`.
