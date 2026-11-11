# Glazes, barcodes and tags

- Manufacturers: the short ID (2–10 letters/digits, starting with a letter) and the display name are unique in any letter case. Any signed-in user adds one, only together with a glaze (`NewGlaze.newManufacturer`); no editing; deleted with its last glaze, checked under the manufacturer lock (topic "Locks").
- A glaze's code is mandatory, normalized (topic "Contract") and unique per manufacturer (409).
- `DELETE /api/glazes/{id}` (admin only) refuses with 409 while steps use the glaze and deletes stored objects after commit.
- Corrections (`glaze_edit`, `model/GlazeEdit`): `PATCH /api/glazes/{id}` (`GlazeService.correct`, `dto/GlazeDtos.GlazeCorrection`) changes only the fields present.
  - `name` and `code`: absent or null keeps. `notes` (`Optional`, read like the final firing): absent keeps, null or blank clears. Other properties, a manufacturer included, are ignored.
  - Name trimmed (1 to 120); notes stripped (≤ 1000, `NOTES_TOO_LONG`); code normalized like on creation. A stored code is never re-normalized: older codes stay as stored until the code itself is corrected.
  - Any signed-in user changes name and notes. The code: only a site admin, or while no glaze step of any combination, drafts included, uses the glaze (`CombinationStepRepository.existsByGlaze_Id`; else 403 `CODE_LOCKED`); the code of another glaze of the manufacturer is 409 `GLAZE_EXISTS`.
  - It locks actor → glaze and checks under the glaze lock; combination writes lock their step glazes before inserting steps, so the check is race-free (`GlazeEditLockApiTest`, `CombinationConcurrencyApiTest`).
  - Each changed field writes one row (field `NAME`/`NOTES`/`CODE`, `ck_glaze_edit_field`; old and new value, null only for notes, `ck_glaze_edit_values`; one time and editor per request); an unchanged value writes none. The log goes with its glaze (ON DELETE CASCADE) and keeps a deleted editor as null (ON DELETE SET NULL).
- `GET /api/glazes/{id}/history` is public (`SecurityConfig.PUBLIC_GET`): newest first, at most `CatalogController.HISTORY_LIMIT` (100) entries and `more`, editors by display name only.
- `GlazeView.lastEditedAt`/`lastEditedBy` come from one `GlazeEditRepository.latest()` query for the whole listing, never one per glaze (`GlazeEditApiTest` counts statements).
- Barcodes (`glaze_barcode`, primary key `barcode`): a GTIN belongs to at most one glaze in the whole library; a glaze has up to 10. `NewGlaze.barcode` sets one; `PATCH /api/glazes/{id}/barcodes` locks the glaze row and answers 409 when another glaze has it; `GET /api/barcodes/{barcode}` is public (400 invalid, 404 unknown) and returns the `GlazeView` with only the pictures the caller may see.
- Tags (`glaze_tag`, `combination_tag`): `PATCH /api/{glazes|combinations}/{id}/tags` applies removals, then additions, under the item row lock; no server-side search.
