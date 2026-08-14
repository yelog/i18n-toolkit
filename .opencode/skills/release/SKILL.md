---
name: release
description: Use ONLY when preparing and publishing a new i18n-toolkit version, including pluginVersion bumping, CHANGELOG.md and plugin.xml release notes, release commit, vX.Y.Z tag creation, and pushing the release.
---

# Release

Prepare and publish the next project release from the current branch. Complete the workflow end to end unless a preflight check or validation fails. Never skip checks, force-push, rewrite history, or reuse an existing version tag.

## 1. Preflight

1. Read `gradle.properties`, `CHANGELOG.md`, and the `<change-notes>` section of `src/main/resources/META-INF/plugin.xml`.
2. Inspect `git status --short`, the current branch, its upstream, remotes, recent commits, and all reachable version tags.
3. Stop and explain the blocker if any of these conditions apply:
   - The worktree or index is not clean.
   - `HEAD` is detached.
   - The current branch has no upstream or its remote cannot be identified.
   - `pluginVersion` is not exactly three non-negative numeric components.
   - The current commit is not ahead of the latest reachable semantic version tag.
4. Recognize existing version tags with or without a leading `v`, but always create new tags as `vX.Y.Z`.
5. Select the highest semantic version tag reachable from `HEAD` as the changelog range start. Do not rely only on lexicographic tag order.

## 2. Calculate The Version

Read `pluginVersion` from `gradle.properties` and increment it using base-10 carry across `X.Y.Z`:

- If `Z < 9`, produce `X.Y.(Z+1)`.
- If `Z == 9` and `Y < 9`, produce `X.(Y+1).0`.
- If `Z == 9` and `Y == 9`, produce `(X+1).0.0`.

Examples: `0.0.6 -> 0.0.7`, `0.0.9 -> 0.1.0`, and `0.9.9 -> 1.0.0`.

Set the target tag to `vX.Y.Z`. Fetch tags from the upstream remote, then stop if the target tag already exists locally or remotely.

## 3. Generate Release Notes

1. Inspect every commit from the previous version tag exclusive through `HEAD` inclusive. Review commit bodies and diffs when subjects alone are insufficient.
2. Summarize user-visible changes rather than copying commit subjects verbatim.
3. Group entries under only the applicable Keep a Changelog headings: `Added`, `Changed`, `Fixed`, `Deprecated`, `Removed`, `Security`, or `Compatibility`.
4. Account for all commits in the range. Consolidate merge commits, release bookkeeping, dependencies, tests, and related internal changes into concise entries instead of silently omitting them.
5. Stop if the selected range contains no commits; do not publish an empty release.

## 4. Update Project Files

Make only these release preparation edits:

1. In `gradle.properties`, replace `pluginVersion` with the new version without changing surrounding formatting.
2. In `CHANGELOG.md`, keep `## [Unreleased]` at the top, leave it empty, and insert `## X.Y.Z` immediately after it. Add the categorized Markdown release notes under that heading.
3. In `src/main/resources/META-INF/plugin.xml`, prepend equivalent HTML inside `<change-notes><![CDATA[` using `<h3>X.Y.Z</h3>`, `<h4>` category headings, `<ul>`, and `<li>` entries. Preserve all existing release notes and escape content correctly for XML/HTML CDATA.
4. Confirm that the new version appears exactly once as a release heading in each changelog location and that older notes remain unchanged.

Do not add a standalone plugin version element to `plugin.xml`; Gradle supplies the plugin version from `pluginVersion`. The version in `plugin.xml` belongs in the new `<change-notes>` heading.

## 5. Validate And Review

1. Run `./gradlew check`.
2. Run `./gradlew buildPlugin`.
3. Confirm the distribution ZIP exists under `build/distributions/` and its filename contains the new version.
4. Inspect `git diff --check`, `git status --short`, and the complete diff for the three intended release files.
5. Stop without committing if validation fails, the notes are inaccurate, or unrelated files changed. Report the exact failure and retain the edits for review.

## 6. Commit, Tag, And Push

1. Stage only:
   - `gradle.properties`
   - `CHANGELOG.md`
   - `src/main/resources/META-INF/plugin.xml`
2. Commit with `chore(release): prepare vX.Y.Z`.
3. Verify the commit succeeded and the worktree is clean.
4. Create an annotated tag with `git tag -a vX.Y.Z -m "vX.Y.Z"`.
5. Push the release commit to the configured upstream branch without force.
6. Push only the new tag to the same remote without force.
7. Report the commit hash, tag, remote branch, and that GitHub Actions will create or update the corresponding Release.

If the commit push succeeds but the tag push fails, do not alter history or recreate the commit. Report the partial state and the exact safe tag-push command that can be retried.
