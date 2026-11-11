# Layers and code rules

Profiles, packages and the base code rules are in `AGENTS.md` ("Architecture", "Code rules"); this file adds only the details.

## Layers and finding your way

- `prod` also uses native forwarded headers; the profile `postgres` is a legacy alias of `prod`.
- `prod` has no default `GLAZELAB_DB_PASSWORD`. Boot's binder keeps an unresolvable placeholder as literal text, so `RequiredDatabasePassword` (a `BeanFactoryPostProcessor`) binds it the same lenient way and rejects that literal or a blank value without echoing it (strict resolution would reject and print a password containing `${...}`). Tests that activate `prod` or `postgres` must set `spring.datasource.password`.
- Split a package only when it clearly reduces complexity, minding package-private access.
- Controllers (`controller/<Feature>Controller`): `Catalog` (glazes, stock, stock and tag edits), `Combination`, `Club`, `Buddy`, `Feed`, `Image`, `ImageDeletion`, `ImageReport`, `Favorite`, `Sync`, `Auth`/`MagicLogin`, `Ban`, `UserAdmin`, `Report`, `ReportAdmin`, `Avatar`, `Feedback`.
- Services:
  - `LibraryViews`: every glaze and combination view (listings, sync delta, paged first sync);
  - `SyncService`, `ChangeRecorder` (topic "Sync");
  - `GlazeService`: creation, corrections, barcode edits, removal (`dto/GlazeDtos`);
  - `CombinationService`: create/edit/publish/delete and the combination lock order (`dto/CombinationDtos`);
  - `StockService`; `WishlistService` (the club wishlist); `ImageRemovalService` (every photo removal path); `ClubService` (also leaving and member removal);
  - `AccountDeletionService` (also the ban, topic "Bans") with `EmailBlocklist`;
  - `BuddyService` (topic "Buddies"), `AvatarService` (topic "Profile pictures"), `ReportService` (topic "Error reports"), `FeedbackService` (topic "Feedback"), `FeedService` with `repository/FeedRepository` (topic "Activity feed").

## Code rules

- Response records are `*View` in `dto/<Feature>Dtos` (`GlazeDtos`, `StockDtos`, `CombinationDtos`, `AuthDtos`, `ImageDtos`, `ClubDtos`, `BuddyDtos`, `AvatarDtos`, `BanDtos`, `ReportDtos`, `TagDtos`, `AdminUserDtos`, `FavoriteDtos`, `FeedDtos`, `FeedbackDtos`, `SyncDtos`); `CommonDtos.IdView` for created IDs, `ErrorView` for every `ApiErrorHandler` body. `ApiTypesTest` rejects `Map` answers.
- Every time in a request or view is an `Instant` (ISO-8601 with `Z`); a date without a time stays `LocalDate`. `ApiTypesTest` refuses `LocalDateTime` in endpoint types (a zone-less time is read in the reader's zone); stored times are `TIMESTAMP WITH TIME ZONE` columns.
- `config/ErrorResponses` documents `ErrorView` on every operation, deriving the statuses from the operation shape and `SecurityConfig.PUBLIC_GET`/`PUBLIC_POST`: a new status or public route changes that class and `OpenApiContractTest`.
- `SecurityConfig.error` writes the security layer's errors; never build them by string concatenation.
- Lombok (pinned annotation processor): entities also carry `@ToString` excluding lazy relations and secrets.
- Free text (`model/Text`): a request field carries `@Text.Plain(kind)` (also `List<@Plain String>`, `Optional<@Plain String>`); a multipart part or a service input calls `Text.clean`/`optional`. The answer is the kind's message alone (`INVALID_TEXT`, `INVALID_NAME`), mapped in the frontend's `server-messages.ts`.
  - Every kind refuses C0/C1 controls and unpaired surrogates; `MULTI_LINE` (notes, descriptions, house rules, reasons, feedback) allows tab, LF and CR; `LINE` refuses line breaks and U+2028/2029; `NAME` (display and club names) also refuses format characters (bidi controls, zero-width spaces) except ZWJ/ZWNJ and tag characters, and needs a visible character.
  - Stripping is `Text.strip` (Unicode white space and no-break spaces, like JavaScript `trim`), never `String.trim`: trim also eats control characters instead of refusing them. Passwords, tokens, barcodes and codes keep their own rules.
