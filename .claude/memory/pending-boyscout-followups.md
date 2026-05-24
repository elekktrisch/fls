---
name: pending-boyscout-followups
description: "Trivial cleanups pending for the next story's PR (per the boyscout-rule \"trivial cleanups never get their own PR\" extension). The next /modernize-implement run reads this list, folds each item into its branch, and removes the entry after merge."
metadata: 
  node_type: memory
  type: project
  originSessionId: bb002e94-1656-4854-8ab1-df47661620e3
---

Queue of trivial cleanups waiting for the next story's PR to bundle them in. Per [[feedback-boyscout-rule-over-clean-prs]]: trivial workflow-hygiene items don't get their own PR — the next `/modernize-implement S-NNN` run folds them in alongside its primary work.

**Lifecycle:** Each entry has a `Status:` line. When the entry is folded into a story's PR and merged, **remove the entry from this file**. The list is a queue, not a log — if you want history, `git log` this memory file.

---

## Pending items (oldest first)

### CI job-ids rename `next-*` → `alpenflight-*` (S-152 follow-up)

`Status:` pending

`.github/workflows/ci.yml` still has job ids `next-build` and `next-auth-realm-shape`, plus `outputs.next` and related identifier surface (the anchored `\bnext/` regex correctly skipped these slash-less identifiers in S-152). Couples to branch-protection rules — needs a coordinated rebind of required checks on `main` after rename. Deferred from S-152's review (PR #82) for blast-radius isolation.

Surface to touch (search `\bnext-(build|auth-realm-shape)\b` and `outputs\.next\b` from `/c/Users/roman/IdeaProjects/fls`):
- `.github/workflows/ci.yml` — rename job ids + outputs references
- Branch protection rules on `main` (GitHub UI / `gh api`) — rebind required status checks to new job names *in lockstep* with the merge.

### Docker compose profile `next` rename (S-152 follow-up)

`Status:` pending

Compose profile literal `"next"` survives (separate concern per S-152 design notes §7). Renaming requires every contributor to `docker compose -p alpenflight-dev --profile next down -v` once after pulling, then re-up under the new profile name. Files: `docker-compose.yml`, `.github/workflows/compose-{lint,smoke}.yml`, `alpenflight/ops/{dev-up-full.sh,lint-compose.sh,README.md,.env.example,pgadmin/Dockerfile}`. Plan a contributor-comms moment for this one.

### Atom + molecule extractions across masterdata features (S-050 follow-up)

`Status:` pending

Three features (clubs / locations / aircraft) now duplicate the same UI primitives:

- **`<af-checkbox>` atom** — five-checkbox stack with `w-4 h-4 accent-brand-500` in `locations-edit.page.ts` (lines 216-239) + `aircraft-edit.page.ts` (lines 287-326). Mobile touch target ≥ 44px gap.
- **`<af-row-actions>` molecule** — kebab-menu pattern (nz-dropdown + 8x8 button + Edit/Delete `<ul role="menu">`) in `clubs-list.page.ts`, `locations-list.page.ts`, `aircraft-list.page.ts`.
- **`<af-dialog>` for destructive confirms** — `window.confirm` for delete in clubs / locations / aircraft list pages (UI7 / M15 from S-050 reviewer panel). Replace with ng-zorro `NzModalService.confirm` per kit conventions.
- **`<af-textarea>` atom** — multi-line comment input is currently single-line `<af-input>` on `aircraft-edit.page.ts` + `locations-edit.page.ts` IOP description.

### Aircraft module hygiene (S-050 follow-up)

`Status:` pending

Deferred by S-050's round-2 reviewer panel as not-this-PR fix-the-code-and-move-on items:

- **`Aircraft.changeState` rename to `changeStateInMemory`** — keep public for unit-test convenience but make the persistence-correct two-step (`closeCurrentStatePeriodAt` + flush + `openStatePeriod`) the only natural path for service callers. (M3.)
- **Move close→flush→open dance into infra** — `JpaAircraftRepository.persistStateChange(Aircraft, …)` so `flush()` doesn't leak through the domain port. (M13.)
- **`RegisterAircraftCommand` / `UpdateMasterdataCommand` records** to replace the 22-arg AR factory + `updateMasterdata` positional parameter lists. (M5.)
- **`CounterUnitTypeId` typed-id** parallel to `AircraftTypeId` — retrofit `Aircraft` FK columns + DTO fields + Jackson + path converter. (M2-1.)
- **`@SQLDelete` + `@Where(deleted_on IS NULL)` on Aircraft** vs the current JPQL-only soft-delete filter — pattern alignment with S-049. Touches `JpaAircraftRepository` queries. (M4 / M6.)
- **`AircraftValidationException(field, reason)`** for setter-level validation — replace catch-all `IllegalArgumentException` echoing raw input via `pd.setDetail(e.getMessage())`. Cross-cutting handler refactor. (M2-4 / S2-3.)
- **Counter-monotonicity vs duplicate-at_date_time exception split** — currently both translate the partial-unique DataIntegrityViolation through `CounterMonotonicityException`; misleads users when the timestamp collides without totals regressing. (PI5.)
- **`SecurityContextHolder` reach-around in `AircraftsService.getAircraft`** — thread `Authentication` from controller per the LocationsController convention rather than `SecurityContextHolder.getContext().getAuthentication()`. (M2-3.)
- **`MOTOR_CODES` `Set<String>` brittle to new motor types** — prefer `findActiveListRowsByTypeLegacyIntIdGte(4)` so new seed rows auto-include. (P2-5.)
- **`since {validFromDisplay}` / `recorded {atDateTime}` raw ISO format** in `aircraft-edit.page.ts` — humanize via `Intl.DateTimeFormat`. (U2-3.)
- **Suppress current-state pill until ref-data resolves** — `aircraft-edit.page` falls back to displaying the state UUID if the ref-data race hasn't settled by the time the detail arrives. (U2-5.)
