# Google Services / Firebase Setup Guide — `komi-kun/komi-kun`

A beginner-friendly guide to the **two Google files** this fork's build can use,
why they exist, and how to create them from scratch in the Google/Firebase
consoles.

- **Companion to [`CI_SETUP.md`](CI_SETUP.md).** That file covers the generic CI
  bits (enabling Actions, repo settings, signing secrets, workflow triggers).
  This file only covers the **Google/Firebase side**: `google-services.json`,
  `client_secrets.json`, the release keystore SHA-1, and the Crashlytics mapping
  upload.
- **Audience:** someone who has *never* used Google Cloud or Firebase.
- **Repo facts** below were read from the actual source on branch
  `chore/syncyomi-v2-mihon-backlog`. Anything the repo does **not** pin down is
  called out in [§8](#8-repo-facts-i-could-not-fully-pin-down).

> Nothing here requires editing Kotlin/Gradle. You are only creating two JSON
> files (locally and/or as GitHub secrets). If you want to skip Google entirely,
> jump to [§3 Option A](#3-option-a--verify-without-any-google-account) — it works
> today with the flags the build already supports.

---

## 0. TL;DR — pick a path

| | Option A — no Google account | Option B — full Firebase + Drive |
|---|---|---|
| Effort | none | ~30–45 min |
| `google-services.json` | not needed | required for CI (`-Pdist=ci`/`github`) |
| `client_secrets.json` | not needed | only if you want Google Drive backup |
| CI status | fork-PR path is green; same-repo path needs the secrets (see below) | all green |
| Telemetry | off | compiled in, but effectively **off at runtime for a fork** (see [§7](#7-privacy--trust-notes)) |

You can do Option A now and upgrade to Option B later without changing any code.

---

## 1. What the two Google files actually are (and why the build cares)

This fork can be built with **telemetry compiled in** or **not**. That one
decision is what makes the Google files matter.

The switch is `BuildConfig.includeTelemetry` in
`buildSrc/src/main/kotlin/mihon/buildlogic/BuildConfig.kt`:

```kotlin
override val includeTelemetry: Boolean = project.flag("include-telemetry")
    ?: (distribution == Distribution.CI || distribution == Distribution.GITHUB)

override val uploadCrashlyticsMapping: Boolean = includeTelemetry && (distribution == Distribution.GITHUB)
```

So telemetry is **on** by default only when you build with `-Pdist=ci` or
`-Pdist=github` (the closest equivalent of the `-Pdist` table is
[`CI_SETUP.md` §7](CI_SETUP.md#7-what-each--pdist-does)). Everything else
(`-Pdist=local`, `-Pdist=foss`, or no `-Pdist` at all) leaves it **off**.

`app/build.gradle.kts` then conditionally applies two Google Gradle plugins:

```kotlin
if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)      // com.google.gms.google-services 4.5.0
        apply(libs.plugins.firebase.crashlytics.get().pluginId)  // com.google.firebase.crashlytics 3.0.8
    }
}
```

### 1.1 `app/google-services.json`

- **What it is:** the Firebase config file for your Android app(s). It contains
  your project id, API keys, and a `client` entry per registered Android
  `package_name`.
- **Where it goes:** `app/google-services.json` (the app module root — this is
  where Mihon moved it to; it is `gitignore`d via `/app/google-services.json`).
- **Why the build needs it:** the `com.google.gms.google-services` Gradle plugin
  reads it and validates that a client's `package_name` matches the
  **variant-specific `applicationId`** being built. If the file is missing or the
  package isn't in it, the plugin fails the build — so any `-Pdist=ci` /
  `-Pdist=github` build needs it.
- **Default (no flag):** the plugin is never applied, so the file is never
  needed. See [§3](#3-option-a--verify-without-any-google-account).

> ⚠️ **Important correction to `CI_SETUP.md` §3.2:** the CI builds the
> **`preview`** variant for PR/push/preview (`assemblePreview -Pdist=ci`) and the
> **`release`** variant for tags (`assembleRelease -Pdist=github`). Their
> package names are **different** (see [§2](#2-which-application-ids-you-must-register)),
> so a `google-services.json` containing only `app.komikun` will make the
> **preview** build fail with a "no matching client for `app.komikun.beta`" error.

### 1.2 `app/src/main/assets/client_secrets.json`

- **What it is:** a **Google Cloud OAuth client-secrets** file used only by
  **Google Drive backup/sync** (a TachiyomiSY feature). It is *not* Firebase.
- **Where it goes:** `app/src/main/assets/client_secrets.json` — this is the
  exact path the CI writes (see [§4.5](#45-add-the-two-github-secrets)), and the
  one the app reads at runtime.
- **Why the build needs it:** it does **not**, strictly — there is no compile-time
  reference. The build succeeds if it's absent. But `GoogleDriveService` reads it
  from assets at runtime, so Google Drive backup throws if it's missing.
- **The app's expectations** (`app/.../data/sync/service/GoogleDriveSyncService.kt`):
  it loads the file with `GoogleClientSecrets.load(...)`, generates the auth URL,
  and later reads `secrets.installed.clientId` / `secrets.installed.clientSecret`.
  Therefore the JSON **must contain an `installed` object with `client_id` and
  `client_secret`**. That is the shape of a Google **"Desktop"** OAuth client
  download, and it is also the shape of an Android OAuth client download. See
  [§4.4](#44-create-the-google-drive-oauth-client-client_secretsjson).
- **Redirect URI / scheme:** the code hardcodes
  `REDIRECT_URI = "eu.kanade.google.oauth:/oauth2redirect"`, and
  `app/src/main/AndroidManifest.xml` registers the `eu.kanade.google.oauth`
  scheme on `GoogleDriveLoginActivity`. Scopes requested: `drive.file` and
  `drive.appdata`.

---

## 2. Which application IDs you must register

From `app/build.gradle.kts`: `defaultConfig.applicationId = "app.komikun"`, plus
per-build-type `applicationIdSuffix`:

| Build type | Variant built by | Final `applicationId` | Register in Firebase? |
|---|---|---|---|
| `release` | release workflow (`assembleRelease`) | `app.komikun` | **Yes** (required) |
| `preview` | PR / push / preview workflows (`assemblePreview`) | `app.komikun.beta` | **Yes** (required for that CI path) |
| `debug` | local `assembleDebug` | `app.komikun.dev` | Optional (local debugging) |
| `releaseTest` | — | `app.komikun.rt` | No |
| `foss` | — | `app.komikun.foss` | No |
| `benchmark` | benchmark workflow (`assembleBenchmark`) | `app.komikun.benchmark` | No (no telemetry) |

**Register at least `app.komikun` and `app.komikun.beta`** (and `.dev` if you
want debug telemetry to resolve). One Firebase project can hold all of them; the
downloaded `google-services.json` then contains one `client` per package.

---

## 3. Option A — verify without any Google account

Use this if you just want a working build and don't care about telemetry or
Drive backup.

### 3.1 Locally (zero Google)

Don't pass `-Pdist` at all (defaults to `LOCAL`), or pass `-Pdist=local`
explicitly:

```bash
# No google-services.json / client_secrets.json needed:
./gradlew assemblePreview          # LOCAL by default → telemetry off
./gradlew assembleDebug            # also LOCAL
# or explicitly:
./gradlew assemblePreview -Pdist=local
```

### 3.2 The exact flag to avoid Google even when CI uses `-Pdist=ci`

`-Pinclude-telemetry=false` forces telemetry off even with `-Pdist=ci` or
`-Pdist=github` (an empty value counts as *true*, so write `=false`):

```bash
./gradlew assemblePreview -Pdist=ci -Pinclude-telemetry=false   # no google-services.json needed
./gradlew assembleRelease -Pdist=github -Pinclude-telemetry=false
```

With telemetry off, the Google Services and Crashlytics plugins are **not
applied**, so neither JSON file is required (and Crashlytics mapping upload is
skipped). The updater stays enabled because `enableUpdater` doesn't depend on
telemetry.

### 3.3 What this means for CI "green"

The workflows hardcode their flags, so be precise about which path you're on:

- **Fork-PR path** (`build_pull_request.yml`, when the PR comes from *another*
  repo): runs plain `./gradlew assemblePreview`, i.e. `LOCAL`. **No Google files,
  no secrets.** This path is already green with zero setup.
- **Same-repo-PR path** (`github.repository == github.event.pull_request.head.repo.full_name`):
  runs `./gradlew assemblePreview -Pdist=ci` and signs the APK. It therefore
  needs `GOOGLE_SERVICES_JSON` (and, to keep OAuth trackers working at runtime,
  `GOOGLE_CLIENT_SECRETS_JSON`) plus the 4 signing secrets.
- **Push, preview, release**: same story authored in the workflows — `-Pdist=ci`
  for push/preview, `-Pdist=github` for release.

So there is **no zero-Google CI with the current workflow files as-is** for the
same-repo paths: they always pass `-Pdist=ci`/`github`. To make those green
without Google you must either provide the JSON (Option B) **or edit the tracked
workflow commands** to add `-Pinclude-telemetry=false` (a code change, out of
scope here). Option A's no-Google guarantee applies to local builds and the
fork-PR path.

---

## 4. Option B — full Firebase + Google Drive setup

Do the sections in order.

### 4.1 Create the Firebase project and Android apps

1. Open **https://console.firebase.google.com/**.
2. Click **Create a project** (or **Add project**). Give it a name (e.g.
   `komikku-fork`). You can keep Google Analytics **on** (recommended) or off;
   Analytics is the Firebase product that sends usage stats, so keep it on if you
   want that product. Accept the terms and click **Create project**.
3. On the project home, click the **Android** icon (or **Add app → Android**):
   - **Android package name:** `app.komikun` → **Register app**.
   - **Download `google-services.json`.** (You'll re-download once at the end
     after adding all packages, to get a single combined file.)
4. Repeat **Add app → Android** for:
   - `app.komikun.beta` (the `preview` variant — required for PR/push/preview CI),
   - optionally `app.komikun.dev` (local `debug`).
   - The SHA-1 field is optional here; add it ([§4.3](#43-get-the-release-keystore-sha-1))
     if you have the keystore. If prompted for a nickname, anything works.
5. After adding every package, go to **⚙ (Project settings) → General → Your
   apps**, and download `google-services.json` once. Put it at
   **`app/google-services.json`**.

**Verify the file** before trusting it: it should be JSON with a `client` array
whose entries have `client_info.android_client_info.package_name` equal to
`app.komikun` and `app.komikun.beta` (and `.dev` if you added it). A quick check:

```bash
grep -o '"package_name": *"[^"]*"' app/google-services.json | sort -u
```

### 4.2 Enable the Firebase products you use

Still in the Firebase console, in the left navigation:

1. **Analytics → Get started** (if you didn't enable it at project creation).
2. **Crashlytics → Get started** and follow the wizard to attach the app.

Enabling Crashlytics is what makes the release workflow's mapping upload work
(see [§6.3](#63-common-errors-and-fixes)). Without it, `-Pdist=github` fails
while uploading the mapping.

### 4.3 Get the release keystore SHA-1

The SHA-1 identifies the signing certificate. You need it for the Google Cloud
OAuth client (and optionally for the Firebase app registration).

If you already created the keystore for `CI_SETUP.md` §3.1:

```bash
keytool -list -v -keystore release.keystore -alias komikku
```

Copy the **`SHA1:`** line (colon-separated hex, e.g. `AB:CD:...`). You can also
read it from an installed APK or an already-built APK:

```bash
keytool -printcert -jarfile Komikku-r85.apk
```

> Use the **release** keystore's SHA-1 (the one whose base64 is in
> `SIGNING_KEY`), because that's what signs the CI APKs. If you distribute
> through Google Play with Play App Signing, use the **Play app-signing** SHA-1
> instead.

### 4.4 Create the Google Drive OAuth client (`client_secrets.json`)

Google Drive sync is a separate Google Cloud API from Firebase. Firebase projects
*are* Google Cloud projects, so you can reuse the same project or create a new
one at **https://console.cloud.google.com/**.

1. Pick/create the project (same one as Firebase is simplest).
2. **APIs & Services → Library** → search **Google Drive API** → **Enable**.
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**.
   - App name, user support email, developer contact email → **Save and continue**.
   - **Scopes**: add `.../auth/drive.appdata` and `.../auth/drive.file`
     (matching `DriveScopes.DRIVE_APPDATA` / `DRIVE_FILE` in the code).
   - If you stay in **Testing** mode, add your own Google account under **Test
     users**, otherwise sign-in is rejected. **Publish** the app (or keep Testing
     for personal use) and click through the summary.
4. **APIs & Services → Credentials → Create credentials → OAuth client ID.**
   Two workable routes:
   - **Android** (this is what the repo's `CONTRIBUTING.md` documents):
     - Application type: **Android**.
     - **Package name:** see the caveat in [§8](#8-repo-facts-i-could-not-fully-pin-down);
       upstream writes `eu.kanade.google.oauth` here, but the app's real
       `applicationId` is `app.komikun`, so `app.komikun` is the conventional
       value.
     - **SHA-1 certificate fingerprint:** the value from [§4.3](#43-get-the-release-keystore-sha-1).
     - Expand **Advanced settings**, enable **Custom URL scheme**, enter
       `eu.kanade.google.oauth` (matches the manifest/redirect URI).
     - **Create**, then **Download JSON**.
   - **Desktop app** (simplest; needs no SHA-1): Application type **Desktop app**
     → **Create** → **Download JSON**. Under the client's **Authorized redirect
     URIs**, add `eu.kanade.google.oauth:/oauth2redirect` so Google accepts the
     app's redirect.

   Both downloads are of the form `{"installed": { ... }}` and satisfy the
   code's `secrets.installed.clientId` / `.clientSecret` access.
5. Save the downloaded file as **`app/src/main/assets/client_secrets.json`**.
   Create the folder if needed:

   ```bash
   mkdir -p app/src/main/assets
   mv ~/Downloads/client_secret_*.json app/src/main/assets/client_secrets.json
   ```

   The minimal shape the app needs (extra fields are fine):

   ```json
   {
     "installed": {
       "client_id": "1234567890-abcdef.apps.googleusercontent.com",
       "client_secret": "your-client-secret",
       "auth_uri": "https://accounts.google.com/o/oauth2/auth",
       "token_uri": "https://oauth2.googleapis.com/token",
       "redirect_uris": ["eu.kanade.google.oauth:/oauth2redirect"]
     }
   }
   ```

   > `{}` (empty object) keeps the CI file-writing step happy but makes Google
   > Drive fail at runtime — only use it if you don't want Drive backup.

### 4.5 Add the two GitHub secrets

**Settings → Secrets and variables → Actions → New repository secret**
(cross-reference `CI_SETUP.md` §3). Both Google files are added as **raw JSON**,
**not** base64 — the workflows write the secret value verbatim to the file:

| Secret | Value (exact form) | File it becomes |
|---|---|---|
| `GOOGLE_SERVICES_JSON` | the **raw** contents of `app/google-services.json` | `app/google-services.json` |
| `GOOGLE_CLIENT_SECRETS_JSON` | the **raw** contents of `client_secrets.json` | `app/src/main/assets/client_secrets.json` |

Or set them with the `gh` CLI, run from the repo root (the values are stored raw,
so do **not** base64-encode them). This checkout has several remotes, so pass
`--repo`:

```bash
REPO=komi-kun/komi-kun
gh secret set GOOGLE_SERVICES_JSON       --repo "$REPO" < app/google-services.json
gh secret set GOOGLE_CLIENT_SECRETS_JSON --repo "$REPO" < path/to/client_secrets.json
```

(`CI_SETUP.md` §3.4 has the `gh` commands for the four signing secrets too.)

This is exactly what every workflow does (identical block repeated in
`build_push.yml`, `build_release.yml`, `build_preview.yml`, and the same-repo
branch of `build_pull_request.yml`):

```yaml
      - name: Write google-services.json
        uses: DamianReeves/write-file-action@6929a9a6d1807689191dcc8bbe62b54d70a32b42 # v1.3
        with:
          path: app/google-services.json
          contents: ${{ secrets.GOOGLE_SERVICES_JSON }}
          write-mode: overwrite

      - name: Write client_secrets.json
        uses: DamianReeves/write-file-action@6929a9a6d1807689191dcc8bbe62b54d70a32b42 # v1.3
        with:
          path: app/src/main/assets/client_secrets.json
          contents: ${{ secrets.GOOGLE_CLIENT_SECRETS_JSON }}
          write-mode: overwrite
```

> Only `SIGNING_KEY` is base64 (`base64 -w0`). `GOOGLE_SERVICES_JSON` and
> `GOOGLE_CLIENT_SECRETS_JSON` are **raw JSON**. Pasting base64 here is a common
> mistake that produces a `` `400`/`403` from the Google plugin`` or a JSON parse
> error.

Also note the release workflow sets an env var:

```yaml
env:
  MIHON_GITHUB_RELEASE: true
```

`app/build.gradle.kts` uses `MIHON_GITHUB_RELEASE` to switch to AGP signing from
`storeFileBase64`/`storePassword`/`keyAlias`/`keyPassword` env vars. It is
**not** Google-related — just be aware it exists when debugging the release job.

### 4.6 Same setup locally (optional)

To build `-Pdist=ci` locally you need the same two files in the same places:

```
app/google-services.json
app/src/main/assets/client_secrets.json
```

`app/google-services.json` is git-ignored, and so is the assets path
(`/app/src/main/assets/client_secrets.json` was added to `.gitignore`). The folder
doesn't exist until you create it, so just be careful not to commit it if you
override the ignore rules.

---

## 5. Artifact map

| Artifact | Where it comes from | Where it goes (local path) | GitHub secret | Workflows that need it |
|---|---|---|---|---|
| `google-services.json` | Firebase console → Project settings → Your apps → Android app → Download | `app/google-services.json` | `GOOGLE_SERVICES_JSON` (raw) | push, same-repo PR, preview, release (all use `-Pdist=ci`/`github`) |
| `client_secrets.json` | Google Cloud console → APIs & Services → Credentials → OAuth client ID → Download (Android or Desktop, `installed` object) | `app/src/main/assets/client_secrets.json` | `GOOGLE_CLIENT_SECRETS_JSON` (raw) | same four workflows write it; only needed at runtime by Google Drive |
| Release keystore SHA-1 | `keytool -list -v -keystore release.keystore -alias …` | n/a (a console value) | internal to the Cert Sys | Google Cloud OAuth Android client; optional Firebase app registration |
| Release keystore | `keytool -genkeypair …` | `release.keystore` (never commit) | `SIGNING_KEY` (**base64 `-w0`**), `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD` | push, PR, preview, release, benchmark (see `CI_SETUP.md` §3) |

---

## 6. Verify it worked

### 6.1 Local

```bash
# Telemetry ON (needs both files in place):
./gradlew assemblePreview -Pdist=ci

# Telemetry OFF (needs nothing):
./gradlew assemblePreview
```

With `-Pdist=ci` and a valid file, Gradle should run a
`processPreviewGoogleServices` task and not error. To confirm the app actually
got the Firebase config, check the generated resource (path varies by variant):

```bash
find app/build -name 'values.xml' -path '*google*' | head
grep -r "google_app_id" app/build/generated 2>/dev/null | head
```

### 6.2 CI

Trigger each path as described in `CI_SETUP.md` §5. Success looks like:

1. **Write google-services.json** / **Write client_secrets.json** steps are green.
2. **Build app** (`assemblePreview -Pdist=ci` or `assembleRelease -Pdist=github`)
   is green.
3. The **Sign APK** / **Rename apk** / **Upload APK** steps produce an artifact
   (`Komikku-<tag>-r<count>.apk`, `mapping-<sha>`).
4. For release, the **Upload mapping** step posts the Crashlytics mapping without
   a 403/400.

### 6.3 Common errors and fixes

| Symptom | Cause | Fix |
|---|---|---|
| `File google-services.json is missing` (or empty) when applying the plugin | `GOOGLE_SERVICES_JSON` secret missing/empty | Add the secret (raw JSON). |
| `No matching client found for package name 'app.komikun.beta'` (and the PR/push/preview job fails) | The JSON only has `app.komikun`; `assemblePreview` builds `app.komikun.beta` | In Firebase, **Add app → Android** with package `app.komikun.beta`, then re-download the combined `google-services.json` and update the secret. Repeat for `.dev` if debugging locally. |
| Release job fails **uploading the Crashlytics mapping** (`-Pdist=github`) — e.g. 403 / "The caller does not have permission" / Crashlytics not configured | Crashlytics not enabled in Firebase, or the project/app isn't wired for it | Enable **Crashlytics** in the Firebase console; or switch the release build to `-Pdist=ci`; or pass `-Pinclude-telemetry=false` (see [§3.2](#32-the-exact-flag-to-avoid-google-even-when-ci-uses--pdistci)). |
| `400`/`403` from the Google plugin, or a JSON parse error | Secret pasted as base64, wrapped in quotes, or with stray characters | Re-copy the **raw** JSON (no base64, no surrounding quotes). |
| Build/agent error about `applicationId` mismatch | Registered the wrong package (`app.komikun` vs `app.komikun.beta`) | Register every variant package you actually build ([§2](#2-which-application-ids-you-must-register)). |
| Google Drive sign-in fails at runtime with `FileNotFoundException: client_secrets.json` | File not packaged — wrong path | Put it at `app/src/main/assets/client_secrets.json` (create the folder). |
| Google Drive sign-in: `invalid_client` / `unauthorized_client` / redirect error | Wrong OAuth client type, missing redirect URI, or consent screen in **Testing** without your account as a test user | Use an `installed` client with `eu.kanade.google.oauth:/oauth2redirect` allowed, and add yourself as a test user or publish the app. |
| Sign-in rejected for a specific APK | SHA-1 registered doesn't match the APK's signing cert | Re-read the SHA-1 (`keytool -printcert -jarfile …`) and update the OAuth/Firebase client. |

---

## 7. Privacy & trust notes

- **The `telemetry/` module is a real no-op unless it's compiled in.** Its
  `build.gradle.kts` swaps source sets: `src/noop/kotlin` (empty methods) when
  `Config.includeTelemetry` is false, `src/firebase/kotlin` (Firebase Analytics +
  Crashlytics) when true. Build without `-Pdist=ci`/`github` and there is no
  Firebase code at all.
- **Even when compiled in, a personal fork collects nothing at runtime.**
  `TelemetryConfig.init` bails out unless **both** conditions hold:
  ```kotlin
  private val MIHON_PACKAGES = hashSetOf("app.komikku", "app.komikku.beta")
  private const val MIHON_CERTIFICATE_FINGERPRINT = "CB:EC:…" // SHA-256 of the upstream signing cert
  ```
  It checks the installed `packageName` against `MIHON_PACKAGES` **and** compares
  the app's signing certificate fingerprint (SHA-256) with the hardcoded upstream
  value. Sign with your own keystore (which you must, to sign CI builds) and the
  check fails, so Firebase never initializes.
- **Collection is opt-in anyway.** `telemetry/src/main/AndroidManifest.xml` sets
  `firebase_analytics_collection_enabled=false` and
  `firebase_crashlytics_collection_enabled=false`, and removes ad-id
  permissions. Analytics/Crashlytics are only enabled through the app's privacy
  preferences (`privacyPreferences.analytics` / `.crashlytics` in `App.kt`).
- **What would be sent if it were active:** Firebase Analytics usage events, and
  Crashlytics crash reports plus ERROR-level logs (via `CrashlyticsPrinter` in the
  firebase source set).
- **A personal fork can simply skip all of this.** Point your builds at
  `-Pdist=local` / `-Pinclude-telemetry=false` and never create the Firebase
  project. The only cost is the same-repo CI paths needing a workflow tweak (or
  the Google secrets).

---

## 8. Repo facts I could not fully pin down

1. **Exact `client_secrets.json` client type/package.**
   The code only requires `installed.clientId` and `installed.clientSecret`; it
   doesn't constrain the client type. `CONTRIBUTING.md` says to create an
   **Android** client and set the package name to `eu.kanade.google.oauth`, while
   the app's actual `applicationId` is `app.komikun`. **Fallback documented:** use
   `app.komikun` as the package name for the Android client (conventional) and
   enable the custom scheme `eu.kanade.google.oauth`; or use a **Desktop** client
   (which needs no SHA-1) with `eu.kanade.google.oauth:/oauth2redirect` added to
   its redirect URIs. Both produce the `installed` object the code expects.
2. **Exact full schema of `client_secrets.json`.** Not pinned by the repo beyond
   the `installed.client_id`/`client_secret` fields. Minimal shape documented in
   [§4.4](#44-create-the-google-drive-oauth-client-client_secretsjson); a real
   Google download contains extra `auth_uri`/`token_uri`/`redirect_uris` fields
   and works as-is.
3. **Exact wording of the Google Services plugin failure.** The repo pins the
   *behavior* (the plugin fails if the file is missing or no client matches the
   variant package), not the literal message. Error strings above are the
   conventional plugin output. **Fallback:** if your error differs, search the
   build log for `google-services` / the package name.
4. **Whether Firebase Auth/Dynamic Links/App Check are used.** They are not
   referenced in this repo's build, so the SHA-1 registration is needed for the
   Google Cloud OAuth (Drive) client; treat Firebase-side SHA-1 as optional.
