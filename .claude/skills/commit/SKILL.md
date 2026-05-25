---
name: commit
description: Use whenever you need to commit changes in this repo. Enforces the project's Conventional Commits style — lowercase imperative subject, scoped type.
---

# Commit

This repo's commits follow Conventional Commits. Every commit must match this style.

## Format

```
<type>(<scope>): <subject>

<optional body>

<optional footer>
```

## Rules

**Type** — exactly one of: `feat`, `fix`, `refactor`, `perf`, `test`, `docs`,
`style`, `build`, `ci`, `chore`, `revert`. Pick the narrowest that fits:

- `feat` — new user-visible behavior (new endpoint, new field, new metric).
- `fix` — bug fix in existing behavior.
- `refactor` — internal change with no behavior delta.
- `perf` — measurable performance improvement.
- `test` — adding or changing tests only.
- `docs` — documentation only (README, code comments, `notes/`).
- `style` — formatting, whitespace; no semantic change.
- `build` — Gradle, dependencies, Docker build, Flyway config.
- `ci` — GitHub Actions, pre-commit hook scripts.
- `chore` — housekeeping that doesn't fit elsewhere (Claude config, `.gitignore`).
- `revert` — reverting a prior commit.

**Scope** — lowercase, short, the subsystem touched.

**Subject** —
- imperative mood ("add", "wire", "fix" — not "added", "adds", "fixed")
- lowercase first letter
- no trailing period
- ≤ 72 characters
- focuses on the *why* or the user-visible effect, not the *what* of the diff

**Body** (optional) —
- separated from subject by a blank line
- wrap at ~72 chars
- explain motivation and contrast with prior behavior; the diff already shows
  the *what*

**Footer** (optional) —
- `BREAKING CHANGE: <description>` for breaking changes
- issue refs like `Refs: #123` if applicable
- **never** add `Co-Authored-By: Claude` — `includeCoAuthoredBy` is `false` for this project

## Procedure

When the user asks to commit:

1. Run these three in parallel:
   - `git status` (no `-uall`)
   - `git diff` (staged + unstaged)
   - `git log -n 5 --oneline` to confirm the style continues to match
2. Group changes into one cohesive commit. If the diff spans unrelated concerns,
   stop and ask whether to split into multiple commits before staging.
3. Draft the message following the rules above.
4. Stage explicitly by filename — never `git add .` or `git add -A`. Skip any
   file that looks like it contains secrets (`.env`, credentials, key material)
   and warn the user.
5. Commit with a heredoc message:
   ```
   git commit -m "$(cat <<'EOF'
   <type>(<scope>): <subject>

   <body if any>
   EOF
   )"
   ```
6. Run `git status` after to confirm the commit landed.

## Fixups

When a change is a true correction of a recent commit (typo, missed file,
small bug in code that commit just introduced), prefer a fixup:

```
git commit --fixup=<sha>
```

The user can autosquash later with `git rebase -i --autosquash <base>`. Do not
autosquash automatically — rewriting history is the user's call.

## Things to refuse or push back on

- Using `--no-verify` to skip hooks — never, unless the user explicitly asks.
- Amending a commit that has already been pushed.
- Committing files outside the explicit change set (drive-by formatting in
  unrelated files, generated artifacts, IDE config).