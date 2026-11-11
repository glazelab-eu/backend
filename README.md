# Glazelab API

The server of Glazelab, a ceramic glaze library, stock tracker and glaze-combination notebook: a Spring Boot application
(Java 21, Spring Boot 4, Spring Data JPA, Spring Security, Flyway) that serves the JSON API under `/api/` for the Glazelab web
app, which lives in its own repository.

## Run, test and build

Requirements: Java 21 or later and Maven 3.9 or later.

- Run for development: `mvn spring-boot:run` (port 8080). The default `dev` profile uses an embedded H2 database under
  `data/` and turns the OpenAPI document on (`/api/openapi`).
- Production: the `prod` profile (`SPRING_PROFILES_ACTIVE=prod`) uses PostgreSQL (`GLAZELAB_DB_URL`, `GLAZELAB_DB_USER`,
  `GLAZELAB_DB_PASSWORD`). Every setting and its environment variable is in `src/main/resources/application*.properties`.
- Tests: `mvn test` (HTTP tests against an in-memory database; `mvn clean test` after moving packages). One class:
  `mvn -Dtest=<Class> test`.
- Package: `mvn -DskipTests package` writes `target/glazelab-<version>.jar`; `Dockerfile` builds the container image.
- Health probe: `GET /api/health` answers `{"ok":true,"build":"<build>"}`.

## Source code offer

Glazelab is licensed under the AGPL, so a server that people use over a network offers them its source code. Set:

- `GLAZELAB_SOURCE_URL`: an absolute `https://` (or `http://`) URL where the source code of the running version is
  published. The web app shows a "Source code" link to it in its footer, for everyone and without signing in. There is
  no default: the link stays hidden while it is unset, and a value that is not an absolute http(s) URL stops the start.
  A changed or forked server points it at its own published source.
- `GLAZELAB_BUILD` (container build argument) or `-Dglazelab.build=<id>` (Maven): the build label that `/api/health`
  answers, for example the commit; without it the label is the project version.

## Licence

Copyright (C) 2026 The Glazelab authors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public
License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later
version (SPDX: `AGPL-3.0-or-later`). It is distributed WITHOUT ANY WARRANTY; see `LICENSE` for the full text.

The licences of the libraries packaged into the jar are listed in `THIRD-PARTY.txt` (also inside the jar under
`META-INF/`). Regenerate it after a dependency change with `mvn -P third-party generate-resources` and commit it.
Per-file licence information follows the [REUSE](https://reuse.software) specification (`REUSE.toml`, `LICENSES/`).

Contributions: see `CONTRIBUTING.md`. Security issues: see `SECURITY.md`.
