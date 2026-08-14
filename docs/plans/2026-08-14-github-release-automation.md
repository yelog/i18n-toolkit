# GitHub Release Automation Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Automatically build and attach the IntelliJ plugin to a GitHub Release after a version tag is pushed, and add a project-level OpenCode release preparation skill.

**Architecture:** A tag-triggered GitHub Actions workflow validates the tag against the Gradle project version, builds the plugin, extracts that version's changelog section, and creates or updates the GitHub Release using the runner's GitHub CLI. A declarative OpenCode skill documents the guarded local release preparation sequence that produces the release commit and tag.

**Tech Stack:** GitHub Actions, Bash, GitHub CLI, Gradle Wrapper, JDK 21, OpenCode skills.

---

### Task 1: Add the tag-triggered release workflow

**Files:**
- Create: `.github/workflows/release.yml`

**Step 1: Define trigger and permissions**

Trigger only pushes of tags matching `v*.*.*`, set `contents: write`, and prevent two jobs for the same ref from running concurrently.

**Step 2: Configure the build environment**

Check out the tagged commit, install Temurin JDK 21, and configure Gradle caching with official actions.

**Step 3: Validate the release version**

Strip `v` from `GITHUB_REF_NAME`, require a strict three-part numeric version, read `pluginVersion` from `gradle.properties`, and fail when they differ.

**Step 4: Build the plugin**

Run:

```bash
./gradlew buildPlugin
```

Expected: one or more plugin ZIP files under `build/distributions/`.

**Step 5: Extract release notes**

Use `awk` to copy content after `## X.Y.Z` up to the next level-two heading. Fail if the version section is absent or empty.

**Step 6: Create or update the Release**

Use `gh release view` to choose between `gh release create` and `gh release edit`, then upload all distribution ZIPs with `gh release upload --clobber`.

### Task 2: Add the project release skill

**Files:**
- Create: `.opencode/skills/release/SKILL.md`

**Step 1: Add valid skill frontmatter**

Set `name: release` and a concrete description that triggers only for preparing and publishing a project release.

**Step 2: Define preflight guards**

Require a clean worktree, an upstream branch, reachable remote, valid current version, commits since the previous version tag, and no existing target tag locally or remotely.

**Step 3: Define version and notes generation**

Increment each numeric version component with base-10 carry, gather commits from the latest semantic version tag to `HEAD`, and summarize user-facing changes under appropriate Keep a Changelog groups.

**Step 4: Define project edits**

Update `pluginVersion`, replace the Unreleased content with a new version section in `CHANGELOG.md`, and prepend matching HTML under `<change-notes>` in `plugin.xml`.

**Step 5: Define validation and publication**

Run `./gradlew check` and `./gradlew buildPlugin`, inspect the diff, commit only intended files, create an annotated `vX.Y.Z` tag, push the branch commit, and then push the tag.

### Task 3: Verify the implementation

**Files:**
- Verify: `.github/workflows/release.yml`
- Verify: `.opencode/skills/release/SKILL.md`

**Step 1: Parse the workflow YAML**

Use an available YAML parser to ensure the workflow is syntactically valid.

**Step 2: Test the embedded version and changelog scripts**

Run the workflow shell logic against the current `pluginVersion` and `CHANGELOG.md`, using a matching synthetic tag where needed.

**Step 3: Build the plugin**

Run:

```bash
./gradlew buildPlugin
```

Expected: `BUILD SUCCESSFUL` and a plugin ZIP under `build/distributions/`.

**Step 4: Inspect changes**

Run `git diff --check` and `git diff --` for the newly created files. Do not commit, tag, or push during implementation.
