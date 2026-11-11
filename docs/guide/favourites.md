# Favourite totals

- `GET /api/favorites/counts` (`FavoriteController`, public through `SecurityConfig.PUBLIC_GET`) returns `FavoriteCountsView {glazes, combinations}` of `{id, count}` rows for starred glazes and published combinations; zero totals are omitted.
- Two grouped repository queries, no per-item lookups.
- Never include a draft, an account ID, a name or who starred an item. The private `GET /api/favorites` stays signed-in only.
- Not synced: the client fetches totals in full, so no change recording, migration or `SyncService.FORMAT` change.
- `FavoriteApiTest` pins aggregation, idempotency, draft exclusion, public access and the two-statement read.
