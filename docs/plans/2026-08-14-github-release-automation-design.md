# GitHub Release Automation Design

## Goal

Publish an IntelliJ plugin package automatically when a version tag is pushed, and provide a project-level OpenCode skill that prepares and pushes releases consistently.

## GitHub Actions Workflow

- Trigger on tags matching `v*.*.*`.
- Strip the leading `v` and verify the result matches `pluginVersion` in `gradle.properties`.
- Set up JDK 21 and Gradle, then run `./gradlew buildPlugin`.
- Extract the matching `## X.Y.Z` section from `CHANGELOG.md`.
- Use the runner's authenticated GitHub CLI and `GITHUB_TOKEN` to create or update the tag's GitHub Release.
- Upload `build/distributions/*.zip`, replacing an existing asset with the same name so reruns are safe.
- Grant only `contents: write` permission.

## OpenCode Release Skill

Create `.opencode/skills/release/SKILL.md`. When explicitly invoked to prepare a release, it will:

1. Require a clean worktree and identify the current branch and remote.
2. Increment the three-part numeric `pluginVersion`. Each component carries at 10, for example `0.0.9` becomes `0.1.0` and `0.9.9` becomes `1.0.0`.
3. Summarize commits since the most recent version tag into categorized release notes.
4. Add the new version section to `CHANGELOG.md` and prepend equivalent HTML to `plugin.xml` change notes.
5. Run focused validation and inspect the final diff.
6. Commit with `chore(release): prepare vX.Y.Z`.
7. Create an annotated `vX.Y.Z` tag and push the commit followed by the tag.

The skill must stop before destructive or ambiguous operations, including a dirty worktree, an invalid version, no commits to release, or an existing local/remote target tag.

## Data Flow

The OpenCode skill prepares and pushes the release commit and tag. GitHub receives the tag and starts the workflow. The workflow builds from that tagged commit, reads release notes from the same source revision, and creates or updates the corresponding GitHub Release without requiring local GitHub CLI usage.

## Verification

- Validate workflow YAML structure and shell scripts locally where practical.
- Run `./gradlew buildPlugin` to verify the expected distribution ZIP is produced.
- Inspect the skill frontmatter and release safety checks.
- The first real tag push provides the end-to-end GitHub permission and Release upload verification.
