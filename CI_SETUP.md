# CI Setup Guide — `komi-kun/komi-kun`

How to turn on GitHub Actions for this personal fork of Komikku, add the CI
secrets, configure repo settings, and verify each workflow.

This guide is based on the workflow audit (`gh-workflows.md`, 2026-10-11). It
matches the current state of `.github/` on branch
`chore/syncyomi-v2-mihon-backlog`.

- **Repo:** `komi-kun/komi-kun`
- **Default branch:** `master`
- **App id:** `app.komikun`
- **JDK:** 21 (`.github/.java-version`)
- **Gradle:** 9.3.x (wrapper)

> **Scope note:** none of this requires editing Kotlin/Gradle. The build already
> supports the `-Pdist` / `MIHON_GITHUB_RELEASE` / env-var signing contract the
> workflows use. The only blockers are repo settings, secrets, and the workflow
> edits already applied on this branch (see [§6](#6-what-was-changed-in-this-branch)).

---

## 0. TL;DR checklist

1. [ ] Enable Actions on the fork (one-time UI acknowledgement).
2. [ ] Confirm default branch = `master`; make repo public or enable Dependency Graph.
3. [ ] Add the 6 repository secrets.
4. [ ] Leave Actions workflow permissions at "Read repository contents".
5. [ ] No environments / no Actions variables required.
6. [ ] Test: same-repo PR → push to `master` → `workflow_dispatch` preview → `v*` tag release.

---

## 1. Enable Actions for the fork

Forks ship with workflows **disabled** until the owner acknowledges them.

1. Open `https://github.com/komi-kun/komi-kun/actions`.
2. Click the green **"I understand my workflows, go ahead and enable them"**
   button.
3. After this, any workflow with a matching trigger runs normally
   (`push`, `pull_request`, `release`, `workflow_dispatch`).

If you later want to stop a single workflow without deleting it, use
**Actions → <workflow> → ⋯ → Disable workflow**. (We already disabled three
upstream-only workflows by renaming them — see [§6](#6-what-was-changed-in-this-branch).)

---

## 2. Repository settings

Go to **Settings**.

### 2.1 Default branch

**Settings → General → Default branch** must be **`master`**. The push,
mirror, preview, and release workflows all key off `master`. (Already true for
this fork.)

### 2.2 Dependency Graph (for `dependency-review-action`)

`build_pull_request.yml` runs `actions/dependency-review-action`, which needs the
**Dependency Graph**.

- **Public repo (recommended):** Dependency Graph is on by default. Nothing to do.
- **Private repo:** `Settings → Code security and analysis → Dependency graph`
  → **Enable**. If you don't want it, remove the "Dependency Review" step from
  `build_pull_request.yml` instead, or the PR job fails at that step.

### 2.3 Actions permissions

**Settings → Actions → General → Workflow permissions:** leave the default
**"Read repository contents and packages permissions"**.

Do **not** switch to "Read and write permissions" globally. The release and
preview workflows declare their own `permissions: contents: write` at the
workflow level, which is the least-privilege way to let them create releases.

### 2.4 Environments

**No environments are needed.** No workflow declares an `environment:` block, so
there are no environment-scoped secrets or protection rules to configure.

### 2.5 Actions variables

**None required.** All build flags (`-Pdist=…`, `-Pinclude-telemetry`,
`-Penable-updater`, …) are passed inline in the workflow commands, not via repo
variables.

---

## 3. Repository secrets

**Settings → Secrets and variables → Actions → New repository secret.**

Add exactly these **6** secrets:

| # | Secret name | Used by | What it is |
|---|-------------|---------|------------|
| 1 | `SIGNING_KEY` | push, PR (same-repo), preview, release, benchmark | base64 of the release keystore |
| 2 | `KEY_STORE_PASSWORD` | all signing | keystore password |
| 3 | `ALIAS` | all signing | key alias |
| 4 | `KEY_PASSWORD` | all signing | key password |
| 5 | `GOOGLE_SERVICES_JSON` | push, PR (same-repo), preview, release | Firebase `google-services.json` for `app.komikun` |
| 6 | `GOOGLE_CLIENT_SECRETS_JSON` | push, PR (same-repo), preview, release | OAuth client secrets (trackers) |

### 3.1 `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`

Create (or reuse) an Android release keystore:

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -alias komikku \
  -keyalg RSA -keysize 4096 -validity 10000
```

The prompts set your **store password** and **key password**. Pick an alias
(the example uses `komikku`).

Generate each secret value:

```bash
# SIGNING_KEY — single line, no wrapping newlines
base64 -w0 release.keystore

# KEY_STORE_PASSWORD — the store password you entered
# ALIAS              — the alias you entered (e.g. komikku)
# KEY_PASSWORD       — the key password you entered
```

Paste the `base64 -w0` output as the value of `SIGNING_KEY`. Keep
`release.keystore` somewhere safe — never commit it.

> **Why base64:** `SIGNING_KEY` is consumed two ways. The `r0adkll` signer
> (`push`/`PR`/`preview`/`benchmark`) reads it as `signingKeyBase64`; the release
> workflow passes it as the `storeFileBase64` env var so the AGP signing config
> writes `$RUNNER_TEMP/komikku.keystore`. Both expect the raw base64 of the
> keystore file with no line breaks, hence `-w0`.

### 3.2 `GOOGLE_SERVICES_JSON`

Contents of a **valid Firebase `google-services.json`**, written to
`app/google-services.json` at build time.

- The `package_name` inside must be **`app.komikun`** (the release/debug app id;
  debug variant uses `app.komikun.dev`, so include both if you want debug
  telemetry to resolve).
- The build types that set `dist=ci`/`dist=github` apply the Google Services
  plugin, so a **missing or empty** file **fails the build**. `push`, same-repo
  PRs, preview, and release all need it.
- **Crashlytics:** `-Pdist=github` (used only by the release workflow) sets
  `uploadCrashlyticsMapping = true`. If you keep `-Pdist=github` in release,
  the Firebase project must have **Crashlytics enabled**, or the release job
  fails while uploading the mapping. If you don't use Crashlytics, switch the
  release build to `-Pdist=ci` (see [§7](#7-what-each--pdist-does)) or add a real
  Crashlytics-capable config.

### 3.3 `GOOGLE_CLIENT_SECRETS_JSON`

Contents of the OAuth client-secrets JSON used by tracker integrations, written
to `app/src/main/assets/client_secrets.json`.

- Absence is **not** a hard build failure, but OAuth-based trackers won't work
  at runtime. If you don't use those trackers, you can still add a minimal/empty
  JSON object (`{}`) to keep the file-writing step happy.

### 3.4 Setting the secrets with the `gh` CLI

If you have the [GitHub CLI](https://cli.github.com/) installed, you can skip the
web UI entirely. Run these from the repo root.

> **This checkout has multiple remotes** (`origin`, `mihon`, `tachiyomiSY`), so
> `gh` cannot infer the target — every command needs `--repo` (or `-R`). Use
> `komi-kun/komi-kun`.

```bash
# one-time
gh auth login

REPO=komi-kun/komi-kun

# 1-4: signing. SIGNING_KEY must be single-line base64, so pipe it in.
base64 -w0 release.keystore | gh secret set SIGNING_KEY --repo "$REPO"
gh secret set KEY_STORE_PASSWORD --repo "$REPO" --body 'YOUR_STORE_PASSWORD'
gh secret set KEY_PASSWORD       --repo "$REPO" --body 'YOUR_KEY_PASSWORD'   # same as the store password for PKCS12
gh secret set ALIAS              --repo "$REPO" --body 'komikku'

# 5-6: Google JSON files -- pipe the file's exact bytes as the secret value.
gh secret set GOOGLE_SERVICES_JSON       --repo "$REPO" < app/google-services.json
gh secret set GOOGLE_CLIENT_SECRETS_JSON --repo "$REPO" < path/to/client_secrets.json

# verify (lists names + updated dates; values are never printed)
gh secret list --repo "$REPO"
```

Notes:

- `--repo` is required here (multiple remotes); if you only had one it could be
  omitted.
- `gh secret set NAME < file` stores the file's exact bytes. For `SIGNING_KEY`
  use the `base64 -w0 … | gh secret set` form so there are no wrapping newlines.
- Secrets set via `gh` are identical to ones added in the web UI.
- Overwrite with `gh secret set NAME …`; remove with `gh secret delete NAME`.
- For an organisation repo, add `--org <org>` (or `--env <env>` for an
  environment secret) instead of `--repo`.

### 3.5 Secrets you do **not** need

- `BOT_PAT` — belonged to the disabled upstream-dispatch workflows.
- `CODEBERG_SSH` — belonged to the disabled mirror workflow.

Do not add these unless you deliberately re-purpose those workflows.

---

## 4. Which workflows are active, disabled, and why

| Workflow | Status | Notes |
|----------|--------|-------|
| `build_pull_request.yml` | **Keep** | Safe: uses `pull_request` (not `pull_request_target`), secret steps gated on same-repo head. Fork PRs build unsigned with no secrets; same-repo PRs need the 6 secrets. |
| `build_push.yml` | **Keep** | Runs on `master` pushes. Needs the 6 secrets. Now also runs the SQLDelight migration gate. |
| `build_release.yml` | **Keep** | `v*` tag → draft GitHub release, 5 APKs. Uses AGP signing. Needs a Crashlytics-capable config for `-Pdist=github` (or switch to `-Pdist=ci`). |
| `build_preview.yml` | **Keep** (optional) | Manual, master-only; auto-tags `r<count>` and publishes a prerelease. Needs the 6 secrets. |
| `build_benchmark.yml` | **Keep** (optional) | Manual benchmark build; needs only the 4 signing secrets. Niche. |
| `pr_label.yml` | **Keep** (low value) | Labels Weblate-authored PRs `translations`. Harmless, safe. |
| `build_dispatch_preview.yml` | **Disabled** (`.yml.disabled`) | Cross-repo dispatch to upstream `komikku-app/komikku-preview` with `BOT_PAT` — cannot work on the fork. |
| `codeberg_mirror.yml` | **Disabled** (`.yml.disabled`) | Guarded `github.repository == 'komikku-app/komikku'` → permanent no-op; mirrors to an upstream Codeberg repo. |
| `update_website.yml` | **Disabled** (`.yml.disabled`) | Cross-repo dispatch to upstream `komikku-app/komikku-app.github.io` with `BOT_PAT` — cannot work. |
| `.github/dependabot.yml` | **Keep** (fixed) | `target-branch` corrected to `master`; opens weekly GitHub Actions dependency PRs. |

Renaming to `*.yml.disabled` keeps the file in git but out of GitHub Actions,
which only loads `.yml`/`.yaml` files in `.github/workflows/`. To re-enable one,
rename it back to `.yml` **and** fix its upstream URLs/guards first.

---

## 5. How to test each workflow

Do the setup in [§1](#1-enable-actions-for-the-fork)–[§3](#3-repository-secrets)
first. Then walk through these in order.

### 5.1 Throwaway same-repo PR (exercises `build_pull_request.yml`)

1. Branch off `master`: `git checkout -b chore/ci-smoke-test`.
2. Push the branch to `komi-kun/komi-kun` and open a PR **against `master` in the
   same repo** (not a fork).
3. Expected steps: Dependency Review → Gradle wrapper validation → JDK 21 →
   write secrets → `spotlessCheck` → `assemblePreview -Pdist=ci` →
   `testDebugUnitTest` → `:data:verifyDebugDatabaseMigration` → sign →
   rename → upload APK + mapping.
4. Expected artifact: **`Komikku-p<branch>-r<count>.apk`** (e.g.
   `Komikku-pchore-r85.apk`), plus **`mapping-<sha>`**. On test failure,
   **`test-report-<sha>`** is also uploaded.
5. Close the PR and delete the branch when done; don't merge it.

> A PR **from a fork** (e.g. an upstream contributor) runs the same job but with
> **no secrets** and `assemblePreview` (no `-Pdist`), so it builds unsigned with
> telemetry off. That path needs no configuration.

### 5.2 Push to `master` (exercises `build_push.yml`)

1. Push one non-`.md` commit to `master` (or trigger via
   **Actions → CI → Run workflow**).
2. Expected: same chain as the PR job, ending in the sign + rename + upload.
3. Expected artifact: **`Komikku-master-r<count>.apk`** and `mapping-<sha>`.

### 5.3 Manual preview (exercises `build_preview.yml`)

1. **Actions → Preview Builder → Run workflow**, choose branch **`master`**.
   (The `build-app` job only runs when `github.ref == 'refs/heads/master'`.)
2. Expected: creates tag `r<commit-count>`, builds `assemblePreview -Pdist=ci`,
   signs with `r0adkll`, publishes a **draft + prerelease**.
3. Expected assets (5): **`Komikku-r<count>.apk`**, `Komikku-arm64-v8a-r<count>.apk`,
   `Komikku-armeabi-v7a-r<count>.apk`, `Komikku-x86-r<count>.apk`,
   `Komikku-x86_64-r<count>.apk`.
4. Visit **Releases** to confirm the draft, then delete it (and the auto-tag) if
   it was just a smoke test.

### 5.4 Tag release (exercises `build_release.yml`)

1. Tag `master` and push the tag:
   ```bash
   git tag v1.14.2-ci-test
   git push origin v1.14.2-ci-test
   ```
   (Tags must start with `v`.)
2. Expected: `prepare-build` (changelog compare) → `assembleRelease -Pdist=github`
   with AGP signing → draft release.
3. Expected assets (5): **`Komikku-v1.14.2-ci-test.apk`**,
   `Komikku-arm64-v8a-v1.14.2-ci-test.apk`,
   `Komikku-armeabi-v7a-v1.14.2-ci-test.apk`,
   `Komikku-x86-v1.14.2-ci-test.apk`,
   `Komikku-x86_64-v1.14.2-ci-test.apk`.
4. Delete the tag/release after verifying.

### 5.5 Benchmark (exercises `build_benchmark.yml`, optional)

**Actions → Benchmark Builder → Run workflow.** Builds `assembleBenchmark`,
signs with `r0adkll`, uploads APK artifacts (retained 30 days). Needs only the 4
signing secrets.

---

## 6. Common failure modes

| Symptom | Cause | Fix |
|---------|-------|-----|
| PR job fails at **Dependency Review** | Dependency Graph disabled (private repo) | Enable Dependency Graph, or remove that step from `build_pull_request.yml`. |
| `push`/same-repo-PR/preview build fails applying the **google-services plugin** | `GOOGLE_SERVICES_JSON` missing/empty or wrong `package_name` | Add a valid config for `app.komikun` (and `app.komikun.dev` for debug). |
| Release job fails **uploading the Crashlytics mapping** | `-Pdist=github` sets `uploadCrashlyticsMapping=true`, but the Firebase project has no Crashlytics | Enable Crashlytics in Firebase, or switch the release build to `-Pdist=ci`. |
| Build fails **signing** | Wrong `SIGNING_KEY` (not `base64 -w0`), or wrong password/alias | Regenerate: `base64 -w0 release.keystore`; verify `KEY_STORE_PASSWORD`/`ALIAS`/`KEY_PASSWORD` match the keystore. |
| OAuth trackers broken at runtime (not a build failure) | `GOOGLE_CLIENT_SECRETS_JSON` missing | Add the client-secrets JSON (or `{}`). |
| Preview/release `workflow_dispatch` does nothing | Triggered from a non-`master` branch | Run from `master`. |
| Release workflow doesn't run on a tag | Tag doesn't start with `v` | Use `v*` tags. |
| Changelog/badge links point to `komikku-app/komikku` | An old hardcode (fixed in this branch) | Confirm the branch's edits are on `master`. |
| `resource not accessible by integration` when creating a release | Missing `contents: write` | The workflows already declare it; ensure you didn't override repo-level permissions to "read only". |

---

## 7. What each `-Pdist` does

Distributions are `LOCAL | CI | GITHUB | FOSS`; an unknown value fails the build
with `error(...)`. Default (no flag) is **LOCAL**.

| `-Pdist` | Telemetry | Crashlytics mapping upload | Updater | `google-services.json` needed | Used by |
|----------|-----------|----------------------------|---------|-------------------------------|---------|
| *(none)* = `LOCAL` | off | no | off | no | fork PRs, benchmark |
| `CI` | on | no | on | **yes** | same-repo PR, `push`, preview |
| `GITHUB` | on | **yes** | on | **yes** (Crashlytics-capable) | release |
| `FOSS` | off | no | on | no | not used by any workflow |

Derived from `BuildConfig.kt`:

- `includeTelemetry = -Pinclude-telemetry ?: (dist ∈ {CI, GITHUB})`
- `uploadCrashlyticsMapping = includeTelemetry && dist == GITHUB`
- `enableUpdater = -Penable-updater ?: (dist != LOCAL)`
- `enableCodeShrink = !-Pdisable-code-shrink`
- `includeDependencyInfo = -Pinclude-dependency-info ?: false`

To force telemetry **off** even for `CI`/`GITHUB`, pass
`-Pinclude-telemetry=false` (note: an empty value counts as *true*).

---

## 8. What was changed in this branch (Task 2)

Applied on `chore/syncyomi-v2-mihon-backlog`; commit these to `master`:

1. **Upstream URL fix:** `komikku-app/komikku` → `komi-kun/komi-kun` in
   `build_release.yml` (compare API + download badge + full-changelog link, 5
   occurrences), `build_preview.yml` (same, 5 occurrences), and
   `.github/scripts/release_note_generate.sh` (1 occurrence). The script is not
   referenced by any workflow (dead code) but was fixed for consistency.
2. **Migration gate:** added
   `- name: Verify SQLDelight migrations` / `run: ./gradlew :data:verifyDebugDatabaseMigration`
   to `build_push.yml`, right after "Run unit tests", matching
   `build_pull_request.yml`.
3. **Dependabot:** `.github/dependabot.yml` `target-branch: "develop"` →
   `"master"`.
4. **Disabled upstream-only workflows** (renamed, not deleted):
   `build_dispatch_preview.yml`, `codeberg_mirror.yml`, `update_website.yml`
   → each `*.yml.disabled`. `pr_label.yml` left as-is.
5. **Signing:** unchanged. The `r0adkll` signer is still used by
   `push`/`PR`/`preview`/`benchmark` (pinned `BUILD_TOOLS_VERSION: '35.0.1'`);
   `release` uses AGP signing. Migrating the rest to AGP signing is a possible
   follow-up, not required for CI to run.

Not touched (cosmetic, out of scope): `.github/FUNDING.yml`,
`.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/*` still point at
upstream docs/releases.
