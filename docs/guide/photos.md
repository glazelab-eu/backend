# Photos and storage

## Photos

- An image belongs to exactly one combination or glaze. Kinds: `TEST_TILE` and `FINISHED` on both, `PACKAGING` on glazes only, `IN_PROGRESS` (a piece not yet finished) on combinations only: `ImageKind.allowedOn` and the named checks; a refusal is 400 `PACKAGING_GLAZE_ONLY` or `IN_PROGRESS_COMBINATION_ONLY`.
- Visibility and kind are the enums `model/ImageVisibility` and `model/ImageKind` (`@Enumerated(STRING)`, bound as query parameters and JSON). An unknown value or a JSON ordinal is the generic 400; `spring.jackson.datatype.enum.fail-on-numbers-for-enums` keeps ordinals out of every enum.
- Each upload narrows its kind in OpenAPI with `@Parameter(schema = @Schema(allowableValues…))`; a plain `@Schema` on an enum parameter merges the lists.
- Photos of a draft (`ImageRecord.effective`, the one copy of the rule):
  - stored with the author's chosen visibility and club, shown to the author in `ImageView`; effective PRIVATE until publication;
  - the effective visibility drives `visibleTo` (entity and `Target.getVisibility()`), `ChangeRecorder.picture`, `cacheControl` and account deletion; `getVisibility()` is the stored one;
  - so nobody else (club members, club and site admins, guests) gets anything but the unknown ID's 404 for any image endpoint, and no shared cache can hold one: a PUBLIC choice keeps its ID at publication;
  - `DELETE /api/images/{id}` answers 404 to everyone but the uploader before any club lock; removing a draft's photo writes no `image_deletion` row (it never reached anyone else, and a draft's title may be blank).
- `controller/ImageEncoder` rejects more than 12 MB or 24 megapixels before decoding, allows one encode per process (503 when busy) and writes ≤ 1600 px JPEG without metadata plus, inside the same permit (never a second encode), a card copy of ≤ 480 px (`CARD_SIDE`, same quality, scaled from the upright full size; a picture that already fits is its own card).
- Every picture download (photos, profile pictures, reported pictures, feedback photos) answers through `controller/Pictures.answer`: the caller finds the record and checks access in a short transaction (or plain repository calls) that has ended, then the object is read with no database connection held (a slow store would otherwise drain the pool; `Pictures` throws inside a transaction; `PictureConnectionApiTest`). Never put `@Transactional` on a download.
- `GET /api/images/{id}` (full size) and `GET /api/images/{id}/card` (`ImageView.cardUrl`) share one download path: the same `visibleTo` check, 404s and headers (`ImageController.cacheControl`; `ImageApiTest` pins each value per visibility and size):
  - By effective visibility. PUBLIC: `max-age=31536000, public, immutable` with `X-Accel-Expires: 60`;
  - CLUB and PRIVATE: `max-age=604800, private, immutable` (`BROWSER_ONLY_DAYS`, the viewer's browser only) with `X-Accel-Expires: 0`; the week is the accepted revocation window (topic "Contract");
  - errors: the `no-store` of Spring Security's default headers; never set a cache header on an error path.
  - A record without a card object answers the card URL with its full-size bytes; without the full object the full-size URL is 404 (the card URL too once its object is also missing).
- Author must see: a photo on a combination its uploader did not author stays visible to the author. Uploads and `PUT …/visibility` refuse PRIVATE, and CLUB of a club the author is not in, with 400 `ImageController.AUTHOR_MUST_SEE` (`requireVisibleToAuthor`: `ImageRecord.visibleTo` with the author as viewer).
  - Order: after `CHOOSE_CLUB` and the uploader's own membership 403; the upload checks before encoding and again under the destination club lock; leaving PUBLIC is refused before the move.
  - Only CLUB depends on the author's clubs, and only on the destination club, whose lock the binding check runs under and a leave takes too: a leave first refuses the photo, a leave after it hides it from the author (`combinations.md`, edit and delete).
  - The author's own photos, glaze photos and a combination whose author deleted their account keep every option. The PATCH never touches visibility.
- `PATCH /api/images/{id}` `{kind, caption, firedOn}` and `PUT …/visibility` (any direction) are uploader-only: visible non-owners 403, hidden or unknown 404 (site admins included).
- `PUT /api/images/{id}/kind` `{kind}` follows `deletableBy` exactly like the DELETE (same locks, 403 `KIND_RIGHTS_REQUIRED`, 404, site admins also on hidden photos); the kind's target rule is checked after it; the same kind answers 200 and records nothing. The app changes kinds only through it; the PATCH keeps `kind` for older clients.
- Focus point (`focus_x`, `focus_y`, `ck_image_focus`): where covers centre their `object-fit: cover` crop, in percent of width and height, both or neither (null is the centre); `PhotoDetails.focus` is the one check (400 `FOCUS_INVALID` for half a focus or a value outside 0..100). The stored bytes stay whole.
  - Uploads take optional `focusX`/`focusY`, checked before encoding; leaving PUBLIC carries the focus to the successor.
  - `PUT /api/images/{id}/focus` `{focusX, focusY}` follows the kind change exactly (`lockManaged`, 403 `FOCUS_RIGHTS_REQUIRED`, 404, the INFO line for a site admin on someone else's photo); the body is checked after the rights; nulls reset; the same focus answers 200 and records nothing.
- The PATCH changes only the fields present: `kind` absent or null keeps; `caption` and `firedOn` (`Optional`, the final firing's Jackson reading) keep when absent and clear on null (a blank caption too); `{}` is a no-op 200.
  - Validated after the uploader check (a hidden photo answers 404 whatever the body) by `model/PhotoDetails`: caption a `Text` line, stripped, blank is null, ≤ 200 counted after stripping; `firedOn` within 1900-01-01..2100-12-31 (shared with `FinalFiring.of`; `ck_image_fired_on` backs it); each refusal is a 400 with a public constant.
  - It keeps ID, URL, key, `createdAt` and visibility and locks actor → parent → image.
- Uploads (combination and glaze) take the same optional `caption` and `firedOn`, validated before encoding; `ImageView.caption` and `firedOn` are visible exactly where the picture is.
- Leaving PUBLIC moves the record to a new ID (`ImageRecord.movedTo`: the successor shares key (so both objects), `createdAt`, caption and date; storage untouched; a failure rolls back; the old ID's full and card URLs answer 404). Other visibility changes keep the ID.
- `DELETE /api/images/{id}` follows `deletableBy`: site admins may delete any photo, hidden ones included (moderation must reach a reported photo that left PUBLIC), but download only what `visibleTo` allows; others who can see it get 403 (`DELETE_RIGHTS_REQUIRED`), the rest 404.
  - Locks (`lockManaged`, shared with the type and focus changes): account → the photo's club (CLUB photos, only for a caller who is a member by a scalar read first; role read under it; only a role in the club the locked photo still has counts) → parent → image.
  - The record deletion and its `image_deletion` snapshot (titles and IDs without FKs; none for a draft's photo) commit together; storage follows commit. Glaze, combination and account removal log their photos the same way. Admins page the log at `GET /api/admin/image-deletions`.
  - The daily `PhotoRetention.purgeDeletionLog` deletes log rows older than `glazelab.image-deletion-log.retention` (90 days; counts only in the log).

## Photo reports

- `image_report`, `model/ImageReport`: `POST /api/images/{id}/reports` `{reason}` (optional, at most 500 characters as sent, stripped, blank is null) for a signed-in viewer the photo is visible to (else 404); locks account → parent → image. The uploader gets 400 (`OWN_PHOTO_REPORT`). One report per photo and reporter (`uq_image_report_reporter`): a repeat answers 204 and keeps the first.
- `GET /api/admin/image-reports` (site admins): newest first, pages of `ImageDeletionController.PAGE_SIZE` via `ImageDeletionController.page`, one slice query; names the reporter and the uploader (ID and current display name, null once deleted) but carries no photo URL.
- `POST /api/admin/image-reports/{id}/dismiss` deletes one report (404 unknown).
- A report nobody decided goes like a dismissal `glazelab.moderation.report-expiry` (90 days) after it was opened: the daily `PhotoRetention.expireReports`, one delete on `image_report_time_idx` that locks only those rows; the photo stays.
- Deleting the photo resolves its reports (FK ON DELETE CASCADE; the deletion log keeps the evidence); leaving PUBLIC carries them to the successor ID with their IDs and times.

## Storage

- `ImageStorageConfig` picks `file` (default) or `s3` (`S3ImageStorage`; AWS SDK v2 `s3` + `url-connection-client` only; `GLAZELAB_S3_*` in `S3Settings`) from `GLAZELAB_STORAGE`.
- Keys are API-generated `<uuid>.jpg` (`ImageStorage.checkKey`); file name = object key. Every picture has two objects: its record's key and `ImageStorage.cardKey` of it (`<uuid>.card.jpg`), derived, never a column.
- Whatever writes or removes objects handles both: the upload stores both before its transaction and removes both when either write or the DB work fails; `ImageRemovalService` (every removal path) deletes both after commit (a missing card is a no-op delete).
- `get` throws `NoSuchFileException` (controller 404). Never log or echo credentials; failures surface as a generic `IOException`.
- Uploads disable aws-chunked encoding and checksum trailers, for S3-compatible stores.
