# Admin user list

- `GET /api/admin/users?page&q` (`UserAdminController`): site admins only through `CurrentUser.siteAdmin` (401 guests, 403 others); read-only.
- All accounts by `lower(display_name)`, id; pages of `ImageDeletionController.PAGE_SIZE` (the shared `page` check, 400 for an invalid number); one slice query (`AppUserRepository.adminList`, a six-field projection: no count, no N+1).
- `q`: case-insensitive "contains" on display name and email; stripped, cut (not refused) at `UserAdminController.MAX_SEARCH` (100); blank = no search; `%`, `_` and `\` escaped (`pattern`); always bound as the one `:pattern` parameter (`"%%"` without a search), so the query has one shape.
- `AdminUserView {id, displayName, email, status ACTIVE|PENDING (`email_verified`), siteAdmin, createdAt}`. Never add password hashes, tokens, session, club, buddy or stock data. No banned flag (a ban removes the account).
- The email is shown to site admins only and needs no terms text or version bump (the site admin is the operator; product decision): do not touch the terms for it.
- `createdAt` is `app_user.created_at` (V33; `updatable = false`, set by the `AppUser` constructor, the one place accounts are created). NULL on purpose for older accounts: no backfill (a terms acceptance time or a token is not a registration time), so the view's date is nullable.
- Not synced (`SyncService.FORMAT` unchanged).
- `UserAdminApiTest` pins roles, paging, order, statuses, the search and the exact JSON keys; `MigrationTest` pins V33.
