# Licences and the source offer

The API is `AGPL-3.0-or-later` (`LICENSE`, `REUSE.toml`, `LICENSES/`, `README.md` "Licence"); contributions carry a DCO sign-off (`CONTRIBUTING.md`).

- A new dependency must be combinable with the AGPL: no EPL-only, GPL-2.0-only, SSPL, BUSL or non-commercial licence in the jar. `spring-aspects` stays excluded from `spring-boot-starter-data-jpa` (its aspectjweaver is EPL-2.0 only); nothing may use AspectJ (`@Aspect`, `@Configurable`).
- A dependency change regenerates `THIRD-PARTY.txt` with `mvn -P third-party generate-resources` in the same commit (runtime and compile scope; test scope and the optional Lombok left out). The jar carries it and `LICENSE` under `META-INF/`, so the Dockerfile copies both into the build.
- Source offer (AGPL section 13): `config/SourceCode` reads `glazelab.source-url` (`GLAZELAB_SOURCE_URL`). No default, so a fork never points at another project's code; a value that is not an absolute http(s) URL stops the start without echoing it.
  - It reaches the app as `AuthOptionsView.sourceUrl` (public, null while unset), which the footer links; never put it into the terms text (no terms version bump).
- `/api/health` answers `{ok, build}`: `build` is `build.label` of the `build-info` goal (`-Dglazelab.build=<id>`, the Dockerfile's `GLAZELAB_BUILD`), else the project version. Keep the answer tiny (below Nginx's 1 KiB gzip minimum) and never limited (`RateLimiter`).
