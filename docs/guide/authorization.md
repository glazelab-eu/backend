# Authorization and visibility

- Enforced server-side: stock ownership, club roles (MEMBER/ADMIN, never the legacy owner field; a club must keep an admin), images, authorship.

## Images

- `ImageRecord.visibleTo` is the single visibility rule for every image listing and download (CLUB: current members and the uploader; PRIVATE: the uploader), applied to the effective visibility: a draft's photo is PRIVATE (topic "Photos"). The entity method and the scalar `Target` checks both call the static `visibleTo(visibility, uploaderId, clubId, userId, clubIds)`; never copy the rule.
- `ImageController.requireUploader`: 403 (visible) or 404 (hidden) for uploader-only actions; every 404 uses `IMAGE_NOT_FOUND`.
- `ImageRecord.deletableBy(uploaderId, clubId, userId, adminClubIds, siteAdmin)` is the single deletion rule (uploader, an ADMIN of a CLUB photo's club, site admins), used by `DELETE /api/images/{id}`, `PUT /api/images/{id}/kind`, `PUT /api/images/{id}/focus` and `ImageView.canDelete` (the client offers all three by that flag); a draft's photo is 404 for everyone but its uploader first.
- `CurrentUser.viewer(auth)` builds the request's `Viewer(userId, clubIds, adminClubIds, siteAdmin)` from one membership query (`MembershipRepository.rolesOf`; guests: null user, nothing else): never look up roles per photo or club.
- `dto/ImageDtos.ImageView.visibleViews(images, viewer)` is the single mapper for every image listing (`ImageView.forUploader` for upload and edit answers); `ImageView.uploader` (null after account deletion) rides on that filter.

## Combinations and glazes

- A draft exists only for its author: every lookup by ID (`CombinationService.requireAuthor`/`lockOwned`; tag, favourite, upload, PUT/DELETE/publish) answers 404 for someone else's draft (guests 401); so does every endpoint of its photos.
  - `GET /api/favorites` drops it (one query, combinations fetch-joined for `visibleTo`; `FavoriteQueryApiTest` counts statements).
  - Admin glaze removal refuses with 409 without naming it.
- PUT, DELETE and publish of a combination: its author only, no site-admin override.
- The title PATCH: its author or a site admin (product decision; 403 `AUTHOR_REQUIRED` for anyone else). A site admin sees no other account's draft, so it is 404 for them too; their correction of another account's title is logged (topic "Bans", `admin_action`).
- `CombinationView.stepsLocked` is `hasForeignPhotos` for the author, always false for others.
- Barcode and tag PATCHes are open to any signed-in user on any glaze or visible combination.

## Clubs

- A club the caller cannot see answers exactly like an unknown club: same status, body and work. Every endpoint naming a club reads the caller's membership as a scalar before any club lock: an outsider never takes, holds or waits for a club lock, so neither answer nor timing confirms a club ID. A new endpoint taking a club ID follows this (`ClubApiTest.aClubTheCallerCannotSeeAnswersLikeAnUnknownClub` compares answers byte for byte, `ClubMembershipLockApiTest.outsidersNeverTakeTheClubLock` the locks).
  - Admin endpoints (`ClubService.lockAdminClub`: settings, role change, invite link): 404 `CLUB_NOT_FOUND` for a non-member and an unknown club; 403 `ADMIN_REQUIRED` only for a plain member. The role is read again under the club lock (an admin removed meanwhile gets 404, a demoted one 403).
  - Endpoints naming a club as the place of a read or write: 403 `CLUB_MEMBERSHIP_REQUIRED` for both (member list, leave and removal, club stock save and `NewGlaze.stock`, CLUB uploads and visibility changes, buddy member requests, every wishlist endpoint); stock removal 404.
- `GET /api/clubs/{id}/members`: every member (`ClubService.requireMember`: a fresh membership query in the read-only transaction, no club lock); display name, role and ID only, never an email.
- Generating a link (`ClubService.regenerate`) also sets `invitesEnabled` under the club lock: a new club starts with invites off, the settings switch stays the off switch, and of a save and a generation the later under the lock decides. It answers `InviteView {token, version}` (the token only here; the version as `ClubView.inviteVersion`).
- Members join only through an invite link (`POST /api/clubs/join`), never added by an admin (`POST …/members` answers 405).
- Leaving and removal (`DELETE /api/clubs/{id}/members/{userId}`, `ClubService.removeMember`): a member removes themself, a club admin any other member, another admin included.
  - Non-member or unknown club: 403 `CLUB_MEMBERSHIP_REQUIRED`, before any lock. A member removing someone else: 403 `ADMIN_REQUIRED`. Unknown target: 404 `MEMBER_NOT_FOUND`. The last admin leaving or being removed: 409 `LAST_ADMIN`.
  - A club always keeps an admin and is never emptied by a leave: its sole member stays until account deletion removes the club.
  - Leaves with the member: the membership, their name on the club shelf and wishlist (`stock.added_by`, `club_wishlist.added_by` cleared) and, for the creator, the legacy `club.owner_id`.
  - Stays with the club: its stock, its wishlist, its invite link (a removed member may rejoin with an active one; the admin replaces the link to keep them out) and its CLUB photos, which keep their uploader and stay visible to members and that uploader (a leaver still sees, deletes and moves their own).
  - The leaver keeps personal stock, favourites, combinations and PUBLIC/PRIVATE photos.

## Club wishlist

- `club_wishlist` (V40, `WishlistService`, `GET|POST /api/clubs/{id}/wishlist`, `DELETE …/{entryId}`, `PUT …/{entryId}/acquired`): every member reads it and adds entries; guests 401, non-members and unknown clubs the 403 above.
- Removal and acquired/wanted are `WishlistEntry.manageableBy` (a club ADMIN or the entry's `added_by`), the one rule, also `WishlistEntryView.canManage`; another member 403 `MANAGE_RIGHTS_REQUIRED`, an entry not on this club's list 404 `ENTRY_NOT_FOUND`.
- An entry is `GLAZE` (a catalogue `glazeId`, no name or link; 400 `GLAZE_FIELDS`) or `ITEM` (name 1–120 and link required, no glaze; `ck_club_wishlist_fields`); a note (≤ 200) is optional; name and note are `Text` `LINE`.
- The link is `model/WishlistLinks`, one strict grammar shared with the frontend (topic "Contract"): http(s), host or bracketed IPv6, RFC 3986 characters with valid escapes, no credentials or white space, ≤ 2000. The server stores it only: never fetches, previews or redirects through it.
- At most `OPEN_LIMIT` (100) entries not acquired per club, and one open entry per glaze (409 `FULL`, `ALREADY_WISHED`), checked under the club lock on an addition and on marking an entry wanted again; acquired entries stay listed.
- Marking the state an entry already has changes nothing (an acquired entry keeps its first time).
- The entries belong to the club (ON DELETE CASCADE) and go with a glaze removed from the catalogue (ON DELETE CASCADE); `added_by` follows the club stock rule (topic "Stock"): cleared on leave or removal, nulled with the account, never given back on rejoining.
- Not synced and no activity-feed type: the client fetches it per page.

## Stock

- Stock removal and `PATCH`: 404 for another user's entry, a non-member's club entry or an unknown entry; 403 (`StockService.REMOVE_RIGHTS_REQUIRED`) only for a member removing a club entry they may not remove. A save or addition to a non-member's or unknown club is the 403 `CLUB_MEMBERSHIP_REQUIRED` above. Guests 401.
