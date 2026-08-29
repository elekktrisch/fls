# Sprint Change Proposal — 2026-08-29

**Raised by:** Roman
**Trigger:** Story 1.1 implementation, in progress
**Scope classification:** Minor — implemented directly, no backlog reorganization, no replan

## 1. Issue Summary

AlpenFlight had no stated rule about code comments. During Story 1.1's implementation, the build
subagent wrote rationale comments into generated Java and TypeScript files (citing story numbers
and architecture-decision IDs). Roman flagged this: implementation code must carry no comments at
all. A name and a structure must carry the intent instead, so the code stays the single source of
truth, and no comment can drift out of sync with the code it describes.

## 2. Impact Analysis

- **Epic impact:** None. Epic 1's scope, sequence, and acceptance criteria are unchanged. This is
  a cross-cutting code-style convention, not a feature or a requirement.
- **Story impact:** Story 1.1 (in progress) is directly affected — its already-written files
  contain comments that must be removed and, where a comment carried real meaning, replaced by a
  better name or a clearer structure. Every later story inherits the same rule going forward.
- **Artifact conflicts:**
  - PRD — none.
  - Architecture spine — updated. `ARCHITECTURE-SPINE.md`'s Consistency Conventions table gets a
    new "Comments" row, so the rule is authoritative and durable, independent of any one session.
  - UX — none.
  - `epic-1-context.md` (compiled planning context for Epic 1) — updated, so future story dispatch
    inherits the rule from the distilled context without re-reading the full spine.
  - Story 1.1 spec (`spec-1-1-the-build-tree-and-the-image.md`) — its locked
    `<frozen-after-approval>` block amended (Roman is the human renegotiating it, which is the only
    party allowed to touch that block), and a `Spec Change Log` entry added recording the amendment.
- **Technical impact:** The running Story 1.1 implementation subagent was messaged directly with
  the new constraint and asked to sweep every file it has written so far and remove comments,
  renaming/restructuring where a comment carried real meaning. One narrow exception: a
  `package-info.java` file's sole content is its package-level Javadoc — that stays, because it is
  idiomatic Java, not an explanatory comment inside code.

## 3. Recommended Approach

**Direct Adjustment.** Update the two durable planning artifacts (architecture spine, epic
context) and the in-progress spec, then relay the rule to the already-running implementation
subagent so it corrects its own output before finishing — no rollback, no MVP change, no epic
reordering. Effort: Low. Risk: Low — the rule only removes text, it changes no runtime behavior,
and Roman decided against a CI-enforced gate for it, so there's no build-gate design or violation
test to build.

**Enforcement decision:** convention only, recorded in the architecture spine and the spec —
not a CI-enforced lint gate. Roman weighed a 10th build gate (comment-detection lint that fails
CI) against a documented convention, and chose the convention: lower ceremony for one person
supporting the whole system, and the spine + spec already reach every future story and every
future agent.

## 4. Detailed Change Proposals

### `ARCHITECTURE-SPINE.md` — Consistency Conventions table

```diff
 | Naming | `domain-model.md` is the authority. ... |
+| Comments | No comment in implementation code. A name and a structure carry the intent instead, so the code stays the one source of truth and no comment can drift out of sync with it. Exception: a `package-info.java` file, whose sole content is its package-level Javadoc. |
```

### `epic-1-context.md` — Technical Decisions → Conventions bullet

```diff
- **Conventions:** naming from `domain-model.md`; money as `BigDecimal`/`numeric`; ...
+ **Conventions:** naming from `domain-model.md`; no comment in implementation code — a name and a
+  structure carry the intent, so the code stays the one source of truth (exception:
+  `package-info.java`'s Javadoc); money as `BigDecimal`/`numeric`; ...
```

### `spec-1-1-the-build-tree-and-the-image.md` — `Boundaries & Constraints` → `Always` (frozen block)

```diff
 One Dockerfile produces one image that serves both the API and the built client.
+No comment in implementation code — a name and a structure carry the intent instead, so the code
+stays the one source of truth (exception: a `package-info.java` file's Javadoc).
```

Plus a new `## Spec Change Log` entry recording the amendment, its rationale, and the known-bad
state it avoids (a rationale comment drifting out of sync with the code it describes).

### Running implementation subagent (Story 1.1)

Messaged directly with the rule, the three files already found to violate it (`PlatformApplication.java`,
`SystemStatusController.java`, `system-status-card.ts`), the `package-info.java` exception, and an
instruction to sweep every file it has produced so far, not just those three, then continue the
rest of the story under the rule.

## 5. Implementation Handoff

**Minor scope — implemented directly in this session:**

- [x] `ARCHITECTURE-SPINE.md` — Comments row added
- [x] `epic-1-context.md` — Conventions bullet updated
- [x] `spec-1-1-the-build-tree-and-the-image.md` — frozen block amended, Spec Change Log entry added
- [x] Running Story 1.1 subagent messaged with the rule and told to sweep existing output
- [x] Verified the subagent's sweep: repo-wide grep for `//`/`/*` returns only the three
      `package-info.java` Javadoc blocks and two `http://localhost:...` URL string literals in
      hand-authored implementation code. (Untouched Angular-CLI/VS Code scaffold config files —
      `tsconfig*.json`, `.vscode/*.json` — retain their stock comments; these are not
      "implementation code" under the rule, same footing as the `package-info.java` exception.)

**Success criteria:** no `//`, `/* */`, `/** */`, or JSDoc block remains in any implementation
file Story 1.1 produces, except a `package-info.java` file's package Javadoc; the rule is visible
in the architecture spine and the epic context for every later story with no further action needed.

---

## Addendum — Version numbers in planning markdown (same day, same session)

**Trigger:** Roman asked for the same single-source-of-truth treatment applied to version numbers:
a planning markdown file may name a framework or a tool, never its pinned version. The version
lives once, in the source config that consumes it (`build.gradle.kts`, `libs.versions.toml`,
`package.json`).

**Evidence found:** the Story 1.1 subagent had already created `alpenflight/deploy/VERSIONS.md`, a
markdown audit trail restating the exact version and source URL for every pinned tool — precisely
the duplication risk the comments rule was written to avoid, one layer up (prose next to code,
instead of inside it).

**Scope decision (asked, not assumed):** `ARCHITECTURE-SPINE.md`'s Stack table (Java 25 LTS,
Spring Boot 4.1.1, PostgreSQL 18.6, Angular 22.0.1) is a **ratified architecture decision record**,
not incidental prose — Roman chose to leave it untouched. The rule applies to everything else:
the Story 1.1 spec's Design Notes, `VERSIONS.md`, and every future planning document.

**Impact:**
- `ARCHITECTURE-SPINE.md` — new "Versions" row in the Consistency Conventions table, with the
  Stack table named as its explicit exception.
- `epic-1-context.md` — Conventions bullet extended with the same rule and exception.
- `spec-1-1-the-build-tree-and-the-image.md` — frozen "Always" block amended again (a second
  renegotiation); Design Notes rewritten to drop every provisional version number, keeping only
  tool names and where to verify them; the `deploy/VERSIONS.md` task option removed in favor of
  `libs.versions.toml` only; a second Spec Change Log entry added.
- Running Story 1.1 subagent — messaged with the rule, told to delete or fold `VERSIONS.md`
  (verification evidence belongs in the commit message, not a tracked file), and to check
  `AGENTS.md` and any other file it wrote for restated version numbers.

**Scope classification:** Minor, same as the comments change — implemented directly.

**Success criteria:** no markdown file this story produces states an exact version number outside
the tables/config that already own it (`libs.versions.toml`, `package.json`); `VERSIONS.md` is
gone or no longer restates numbers already in the version catalog.
