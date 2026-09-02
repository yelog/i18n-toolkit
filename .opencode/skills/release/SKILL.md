---
name: release
description: Use ONLY when preparing and publishing a new i18n-toolkit version, including pluginVersion bumping, CHANGELOG.md and plugin.xml release notes, release commit, vX.Y.Z tag creation, GitHub Actions verification, and pushing the release.
---

# Release

Prepare and publish the next project release from the current branch. Complete the workflow end to end unless a preflight check or validation fails. Never skip checks, force-push, rewrite history, reuse a version tag, or manually publish a GitHub Release outside the repository release workflow.

The repository uses `.github/workflows/release.yml` as the only Release publisher. The workflow must create the Release and upload the plugin ZIP in one `gh release create TAG ASSET` operation. This is required because the repository uses immutable Releases: a published Release cannot receive or replace assets afterward.

## 1. Preflight

Read these files before changing anything:

- `gradle.properties`
- `CHANGELOG.md`
- `src/main/resources/META-INF/plugin.xml` (`<change-notes>`)
- `.github/workflows/release.yml`

Run and inspect:

```bash
git status --short
git branch --show-current
git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}'
git remote -v
git log --oneline --decorate -12
git fetch --tags <upstream-remote>
```

Stop and report the exact blocker if:

- the worktree or index is not clean;
- `HEAD` is detached;
- the current branch has no upstream or its remote cannot be identified;
- `pluginVersion` is not exactly `X.Y.Z` with three non-negative numeric components;
- the current `HEAD` is not ahead of the latest reachable semantic version tag;
- the release workflow is missing, does not have `contents: write`, or uploads assets separately after publishing;
- `gh auth status` fails or the repository cannot be queried with `gh`.

Check the current repository state:

```bash
gh auth status
gh repo view --json nameWithOwner
gh release list --limit 100 --json tagName,isDraft,isImmutable,publishedAt
```

Treat a tag as permanently reserved when it exists locally, exists on the remote, or is referenced by an existing GitHub Release. Never reuse it, including after deleting a failed or empty Release. A published immutable Release is never edited or deleted by this skill. A draft Release may only be removed when it is clearly an artifact of the current failed attempt and contains no asset; otherwise stop for manual review.

Recognize tags with or without a leading `v`. Select the highest semantic version tag reachable from `HEAD` as the changelog range start; do not rely on lexicographic ordering. Record the selected tag, current version, target version, upstream remote, upstream branch, and `HEAD` SHA before editing.

## 2. Calculate The Version

Increment `pluginVersion` with base-10 carry:

- `0.0.6 -> 0.0.7`
- `0.0.9 -> 0.1.0`
- `0.9.9 -> 1.0.0`

The target tag is always `vX.Y.Z`. Before editing, check both local and remote tags and all GitHub Releases for the target:

```bash
git tag --list "vX.Y.Z" "X.Y.Z"
git ls-remote --tags <upstream-remote> "refs/tags/vX.Y.Z" "refs/tags/X.Y.Z"
gh release view "vX.Y.Z" --json tagName,isDraft,isImmutable 2>/dev/null || true
```

If any check finds the target, stop. Do not delete, retag, force-push, or retry that version. Choose the next unused version only after confirming that the version file and changelog range justify it; do not silently skip a release without explaining why.

## 3. Generate Release Notes

Inspect every commit after the selected version tag through `HEAD`, including merge commits:

```bash
git log --reverse --stat <previous-tag>..HEAD
git log --format=fuller <previous-tag>..HEAD
```

Review diffs and commit bodies when subjects are insufficient. Summarize user-visible changes rather than copying commit subjects. Group only applicable entries under:

- `Added`
- `Changed`
- `Fixed`
- `Deprecated`
- `Removed`
- `Security`
- `Compatibility`

Account for dependency fixes, CI/release automation, tests, and merge commits. Consolidate internal-only work into concise user-facing entries instead of silently omitting it. Stop if the selected range has no commits.

## 4. Update Project Files

Modify only these three release files:

- `gradle.properties`: replace only `pluginVersion` with the target version.
- `CHANGELOG.md`: keep `## [Unreleased]` first and empty; insert `## X.Y.Z` immediately after it with the generated Markdown notes.
- `src/main/resources/META-INF/plugin.xml`: prepend equivalent HTML inside `<change-notes><![CDATA[` using `<h3>`, `<h4>`, `<ul>`, and `<li>`; preserve all older notes.

Do not add a standalone plugin version element to `plugin.xml`. Gradle supplies the plugin version from `pluginVersion`.

Validate the edited files before building:

```bash
git diff --check
grep -c '^## X.Y.Z$' CHANGELOG.md
grep -c '^    <h3>X.Y.Z</h3>$' src/main/resources/META-INF/plugin.xml
```

Each count must be exactly `1`. Confirm that `## [Unreleased]` remains first, the new release notes are non-empty, and no older release notes changed except where a direct, documented correction is required.

## 5. Validate Locally

Run in order:

```bash
./gradlew check
./gradlew buildPlugin
```

Do not commit or tag if either command fails. Existing unrelated failures must be reproduced on unchanged `HEAD`, identified precisely, and fixed or explicitly reported before continuing; never hide them by weakening tests.

Confirm the artifact:

```bash
find build/distributions -maxdepth 1 -type f -name "*-X.Y.Z.zip" -print
```

Require at least one matching ZIP. Then inspect:

```bash
git diff --check
git status --short
git diff -- gradle.properties CHANGELOG.md src/main/resources/META-INF/plugin.xml
```

Only the three intended release files may be modified. Generated `build/` output must not be staged.

## 6. Commit And Tag Safely

Stage only the three release files and commit:

```bash
git add gradle.properties CHANGELOG.md src/main/resources/META-INF/plugin.xml
git diff --staged --check
git commit -m "chore(release): prepare vX.Y.Z"
```

Verify the commit SHA and clean worktree. Create the annotated tag only after the commit succeeds:

```bash
git status --short
git tag -a vX.Y.Z -m "vX.Y.Z"
git show --no-patch --format=fuller vX.Y.Z
```

If commit creation succeeds but a later operation fails, resume from the recorded commit/tag state. Never create a second release commit or recreate an existing tag.

## 7. Push And Verify GitHub Actions

Push the release commit first, then only the new tag:

```bash
git push <upstream-remote> <upstream-branch>
git push <upstream-remote> vX.Y.Z
```

Immediately locate the tag-triggered workflow run:

```bash
gh run list --workflow release.yml --limit 5 --json databaseId,status,conclusion,headBranch,headSha,url
```

Watch the run to completion:

```bash
gh run watch <run-id> --exit-status
```

Require these workflow stages to succeed:

- release version validation;
- plugin build;
- release notes and asset preparation;
- atomic GitHub Release creation with the ZIP asset.

Then verify the published Release and asset:

```bash
gh release view vX.Y.Z --json tagName,name,isDraft,isPrerelease,isImmutable,assets,publishedAt,url
```

Require `isDraft=false`, a matching ZIP named `i18n-toolkit-X.Y.Z.zip`, and a successful workflow run. If the workflow fails after creating an immutable Release or consuming the tag, do not retry the same tag. Diagnose the logs, fix the workflow in a separate commit, and release the next unused patch version.

## 8. Failure Recovery Rules

- Validation or build failure before commit: retain edits, do not commit/tag/push, and report the failure.
- Commit push succeeds but tag push fails: retain the commit and tag; retry only `git push <upstream-remote> vX.Y.Z` after confirming the remote tag is absent.
- Tag push succeeds but workflow fails before Release creation: fix the workflow and use a new unused version tag; do not move the existing tag.
- Workflow creates an empty/draft artifact: delete only an unambiguous draft artifact if it has no assets, then use a new tag if GitHub reports the old tag as immutable or reserved.
- Workflow creates a published immutable Release: never edit, upload, replace, delete, or reuse it; report its URL and create the next release version.
- Never manually run `gh release upload` against a published Release. The workflow must create the Release and asset atomically.

Report the final release commit SHA, tag, remote branch, workflow run URL, Release URL, asset URL, and any residual failed-attempt artifacts.
