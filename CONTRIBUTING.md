# Contributing to the Glazelab API

Thank you for helping. Bug reports, ideas and changes are welcome as GitHub issues and pull requests.

## Before you open a pull request

- Read `AGENTS.md`: the architecture, the code rules and the hard rules (security, privacy, data) this repository keeps.
- Keep a change small and focused, and add tests for the behaviour it changes.
- Run the tests before you push: `mvn test` (Java 21 or later, Maven 3.9 or later). A change to the API contract also
  changes the web app's generated client (see the web app's repository).

## Licence of contributions

The Glazelab API is licensed under the GNU Affero General Public License, version 3 or later (`AGPL-3.0-or-later`, see `LICENSE`).
Your contribution is licensed under the same terms. There is no contributor licence agreement: instead, every commit
carries a sign-off under the Developer Certificate of Origin (DCO) below, which states that you have the right to submit
it.

Sign off each commit with `git commit -s` (or `--signoff`). Git then adds a line with the name and address of your
Git identity:

```text
Signed-off-by: Your Name <you@example.com>
```

Use your real name or the name you are known by, and an address you can be reached at. To sign off commits you already
made, use `git commit --amend -s` for the last one or `git rebase --signoff <base>` for several.

```text
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```
