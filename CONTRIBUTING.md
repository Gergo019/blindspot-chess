# Golden rules

These rules keep BlindSpot maintainable as it grows: `main` always works, every change is traceable, and anyone (including future me) can see why something was done. They apply to every contributor and every tool, human or AI.

## Product rules

These shape design and tests more than any Git convention:

1. **Completed games only.** The importer and the analysis pipeline never touch a game in progress.
2. **Every coaching claim shown to a player has board evidence** they can play through: a finding without an evidence line is invalid.
3. **Only verified explanations are shown.** Player-facing code reads explanations through `ExplanationRepository.findShowable`, never directly. In v1 that means text rendered from validated typed claims; free-form AI text stays an experiment (ADR 0003).
4. **Analysis is reproducible.** Every evaluation records the engine version and search depth.

## Workflow

- **Never commit or push directly to `main`.** Every change goes through a branch and a pull request, however small.
- `main` must always build and pass CI. Protect it in GitHub (Settings > Branches): require a PR and green checks, block force pushes.
- **One short-lived branch per change**, from an up-to-date `main`: `<type>/<short-description>`, optionally with an issue number (`feat/12-lichess-import`). Types: `feat`, `fix`, `refactor`, `docs`, `test`, `chore`.
- **Focused PRs, squash-merged** once CI is green. The PR title becomes the commit on `main`. Delete the branch after merging.
- **Issues for work worth tracking.** A project board and milestones are optional for solo work.
- **Decision records** in `docs/decisions/` for choices you will want to explain later (context, decision, consequences, half a page).

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <imperative summary>`, for example `feat(backend): import recent rapid games from Lichess`. Scopes: `backend`, `analysis`, `frontend`, `db`, `infra`, `docs`.

- Small, logical commits that each compile; the body explains *why*.
- **The human who owns the work is the author. No AI co-author or attribution lines** (`Co-Authored-By: Claude`, "Generated with ...") in commits, PR titles or PR descriptions.

## Database

- Flyway owns the schema (`backend/src/main/resources/db/migration`). **Never edit a migration merged to `main`**; add `V<n>__description.sql`.
- Hibernate runs with `ddl-auto: validate` and is never switched to `update` or `create`.
- Rules that protect data belong in the database too (check and unique constraints), not only in Java.

## Code and tests

- Tests come with the code they test. A bug fix includes a test that would have caught it.
- Domain rules live in the entities and are unit-tested without Spring; integration tests use Testcontainers, never H2.
- Backend layering: controller -> service -> repository. Entities never leave the API; use DTOs (records).
- External APIs are tested against recorded responses, never live.
- No secrets in Git. New settings go into `.env.example` with a comment; a leaked secret is rotated immediately.
- No `TODO` without an issue number (`// TODO(#34): ...`).

## Documentation and releases

- The README describes how to run the project *today*; a change that alters setup updates it in the same PR.
- Notable changes go into `CHANGELOG.md` under `Unreleased`.
- Every PR adds or updates an entry in `docs/devlog.md`.
- Each finished phase is tagged with [Semantic Versioning](https://semver.org/) (`v0.1.0`, `v0.2.0`, ...) and gets a GitHub Release.

## Quick reference

```bash
git switch main && git pull
git switch -c feat/<description>
# ...commit with Conventional Commits...
git push -u origin HEAD
gh pr create --fill          # CI green -> squash and merge -> delete branch
```
