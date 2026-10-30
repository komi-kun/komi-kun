# Agent runbook (komi-kun Yomitan/OCR port)

Every agent working on this branch must follow these rules. They exist to keep parallel work
conflict-free and correctly signed.

## Scope

- Repo: `/home/luca/development/komi-kun`, branch **`feat/introduce-ocr-and-yomitan`**.
- **Never** switch branches, rebase, or force-push this repo's shared branch.
- Only edit the files your task owns (see `00-implementation-contract.md` §3 file-ownership map).
  Do not reformat, reorder imports in, or "tidy" files you don't own.
- Forks you may push to (never upstream, never open PRs upstream):
  - `git@github.com:komi-kun/hoshidicts.git` — pinned `62fc9fa16c3c4aac4b7ad06302af86cca09ada9a`
  - `git@github.com:komi-kun/webgpuviewer.git` — tag `ocr-accessors-1` (`792aef3`)

## Commits & signing

- Commits are **SSH-signed** (`commit.gpgsign=true`, `gpg.format=ssh`, key `~/.ssh/id_gh`, which is
  served by a Bitwarden agent).
- **Always leave signing on.** If a commit fails with a signing error (agent locked), retry with the
  agent env var unset so `ssh-keygen` uses the key file directly:
  ```bash
  env -u SSH_AUTH_SOCK git commit -m "…"
  ```
- One logical change per commit; conventional-commit style (`feat:`, `fix:`, `build:`, `chore:`).
- Prefer **one committer per wave** (a designated integrator) so parallel edits never race the git
  index. If you are not the integrator, **do not `git commit`** — leave changes in the worktree.

## Gradle serialization (parallel agents share one project dir)

- Any `./gradlew` invocation MUST take the shared lock, so concurrent agents never run Gradle at
  once:
  ```bash
  flock /tmp/opencode/gradle.lock ./gradlew <tasks>
  ```
- Prefer the narrowest task (`:module:compileDebugKotlin`) over `assemble*`.
- Run `spotlessApply` before `spotlessCheck`; CI gates on `spotlessCheck`.

## Code conventions (komi-kun `AGENTS.md`)

- Every new/modified **upstream** hunk is wrapped `// KUN -->` … `// KUN <--` (`.sq`: `-- KUN -->`).
- New strings only in `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` (`KMR`). Never touch
  non-`base` locales. Never add to `i18n/` (`MR`) or `i18n-sy/` (`SYMR`).
- New code uses coroutines/Flow; avoid RxJava. Metro DI (`@Inject`, `@ContributesInto*`), not Injekt.
- Keep reader-facing seam files GPL-free (see contract §1.3): do not import `mihon.core.dictionary.*`
  / `mihon.core.ocr.*` internals there.

## Verification (per wave — see contract §5)

```bash
flock /tmp/opencode/gradle.lock ./gradlew spotlessApply spotlessCheck
flock /tmp/opencode/gradle.lock ./gradlew :core:dictionary:compileDebugKotlin :core:ocr:compileDebugKotlin
flock /tmp/opencode/gradle.lock ./gradlew testDebugUnitTest
bash scripts/verify-gpl-free.sh        # once it exists (contract §1.3)
```

## Definition of done per task

1. Owned files changed; nothing else touched.
2. Narrow compile green (under the lock).
3. `spotlessCheck` green.
4. Report: files changed, commands run, results, blockers — and, if you are the integrator, the
   commit SHA (signed).
