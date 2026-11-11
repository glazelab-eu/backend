# Account deletion

- `POST /api/auth/delete`: CSRF, current password, allowed with pending terms; 409 for the last admin of a club with other members.
- Deletes (`AccountRows` plus FK cascades):
  - sole-member clubs (and legacy owned clubs without members) with their stock, wishlist (FK cascade), invites and CLUB photos;
  - the account's drafts with their photos, private photos, stock, favourites, memberships, tokens, buddy link, buddy pairs (either side, accepted or pending), deletion-log IDs;
  - the profile picture and every evidence copy of its earlier pictures, reported or not; fingerprints stay (topic "Profile pictures");
  - error reports (the `client_report` cascade alone); feedback and its photos (`FeedbackService.removeOf`);
  - every stored session (cascade); the request's cookie is cleared.
- Keeps:
  - other clubs' club stock and wishlist entries (`added_by` nulled by the FK);
  - shared text and photos, with nulled author, uploader and legacy club-owner;
  - the account's photo reports, profile-picture reports and glaze corrections, with a null reporter or editor (ON DELETE SET NULL);
  - profile-picture reports about its pictures: text removed, open ones CLOSED before the FK nulls their owner.
- Never null a PRIVATE photo's uploader: a private upload committed after the locked read keeps its FK and fails the deletion.
- Flush and clear the persistence context after the bulk anonymisation, before deleting the row.
- Stored objects are removed after commit.
- A ban runs this very removal (`AccountDeletionService.removeRecords`; topic "Bans"): a change here changes the ban. `BanApiTest.aBanRemovesTheAccountExactlyLikeAnAccountDeletion` compares both over every table naming an account; add a new table to its `footprint`.
