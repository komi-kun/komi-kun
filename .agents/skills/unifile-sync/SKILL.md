---
name: unifile-sync
description: Keep komi-kun's vendored UniFile dependency fork (komi-kun/UniFile) current with upstream mihonapp/unifile — rebase the fork's mkdir commit on upstream, build, push to GitHub, and update gradle/libs.versions.toml. Use when asked to update/bump UniFile, pull upstream UniFile changes, or sync the UniFile fork.
---

# Syncing the UniFile fork

`komi-kun` consumes `UniFile` from its own fork (`komi-kun/UniFile`) via JitPack.

| Property | Value |
|---|---|
| Upstream repository | `https://github.com/mihonapp/unifile.git` (branch `main`) |
| Fork repository | `git@github.com:komi-kun/UniFile.git` (branch `main`) |
| Dependency coordinate | `unifile = "com.github.komi-kun:UniFile:<commit-sha>"` in `gradle/libs.versions.toml` |
| Fork delta | 1 commit: `support File.mkdir()` (commit `b7f6a2ef47`, needed for sync/export directory creation) |

---

## 1. Inspect what is new upstream

In a clone of `komi-kun/UniFile` (or temporary clone):

```bash
git clone git@github.com:komi-kun/UniFile.git /tmp/unifile
cd /tmp/unifile
git remote add upstream https://github.com/mihonapp/unifile.git
git fetch upstream main
git log --oneline main..upstream/main
```

If `main..upstream/main` is empty, the fork is already up-to-date with upstream.

---

## 2. Rebase the fork commit onto upstream

Rebase the single fork commit (`support File.mkdir()`) onto `upstream/main`:

```bash
git rebase upstream/main
```

Verify that the `support File.mkdir()` commit is cleanly applied on top of upstream's latest HEAD.

---

## 3. Verify the build

```bash
./gradlew assemble
./gradlew test
```

---

## 4. Push to the fork repository

Push the rebased `main` branch to `komi-kun/UniFile`:

```bash
env -u SSH_AUTH_SOCK git push origin main
```

Get the new HEAD commit SHA:

```bash
NEW_SHA=$(git rev-parse --short=10 HEAD)
echo "New UniFile commit SHA: $NEW_SHA"
```

---

## 5. Update the komi-kun app repository

In the `komi-kun` repository:

1. Update `gradle/libs.versions.toml`:
   ```toml
   unifile = "com.github.komi-kun:UniFile:<NEW_SHA>"
   ```

2. Verify compilation:
   ```bash
   flock /tmp/opencode/gradle.lock ./gradlew :app:compileDebugKotlin
   flock /tmp/opencode/gradle.lock ./gradlew spotlessCheck
   ```
