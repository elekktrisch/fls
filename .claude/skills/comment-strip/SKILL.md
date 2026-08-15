---
name: comment-strip
description: Delete every human-written comment in alpenflight/ + e2e/ and restore understandability through naming instead. Repeals the why-only comment policy first, then sweeps module by module with sharded judge subagents, then applies cross-file renames serially. Stops at a PR. Trigger: /comment-strip [--batch NAME | --check].
---

# comment-strip — no comments, better names

Delete every comment a human wrote for a human. Where a comment carried real
understanding, put that understanding into the **name** instead — long names are
fine. History lives in git; rationale lives in `docs/modernization/`. Neither
belongs in the source.

Read [ADR 0022](../../../docs/modernization/adrs/0022-modernization-primary-directives.md).
This skill ships behavior change in the codebase's readability, not a document.

**Three comments survive, nothing else:**

1. **Tool-parsed directives** — `eslint-disable*`, `@ts-ignore`, `@ts-expect-error`,
   `@ts-nocheck`, `prettier-ignore`, `noinspection`, `language=`, `@formatter:off/on`,
   shebangs. The build reads these; they are not prose.
2. **`// ext:` markers** at a boundary an outside party owns (Proffix field names,
   the OGN device feed, Keycloak claims, env-var names) — and **only where no pin
   exists**. Prefer the pin: `@JsonProperty("Artikelnummer")` or
   `@Column(name = "…")` is enforced by the machine, then rename the Java
   identifier freely. A comment saying "Proffix calls this X" is unenforced
   folklore. Keep `ext:` markers telegraphic; concision beats grammar.
3. **`// @mocked: <seam> — <reason>`** — `/do-ship` §4 mock governance, which
   `gap-hunter` greps to tell a declared mock from an undeclared one (an
   undeclared mock is a red chain). It survives *because* it is enforced:
   `--check` opens its report with a `mocked seams (N)` section listing every
   tag's file, line, seam and reason, so the PR's "Mocked seams" list is
   generated from the code. The tag must stand on a comment of its own; a prose
   block that buries one is reported, never stripped, until the tag is hoisted
   out.

## Preconditions — refuse or warn

- **Refuse** if the working tree is dirty.
- **State the merge-window cost, then let the operator set it.** This sweep touches
  ~1,800 files: any branch open across it conflicts on rebase and the conflicts are
  unresolvable by tooling (both sides changed the same lines), and the sweep branch
  itself rots against whatever lands on `main` meanwhile. So name the open branches and
  the cost of the window, and let the operator judge how long the sweep may run — a
  same-day merge is the answer when others are shipping in parallel, not a rule. With
  two people on the repo and nothing else in flight, taking the time it needs is fine.
- Cut a dedicated branch off `main`: `chore/comment-strip` — or run it as a journey on
  `integration/J-NNN`, which is what buys the sweep a real gate and a proof video.

## Step 1 — repeal the policy, then sweep

Do this **first**, as its own commit. If the sweep lands first and a journey
ships before the policy changes, `/do-task` writes fresh why-comments into the
clean tree and the sweep is paid for twice.

| File | Change |
| --- | --- |
| `.claude/skills/do-ship/SKILL.md` (~L302) | "self-explanatory code, why-only comments" → no comments; name things instead |
| `.claude/skills/do-task/SKILL.md` (~L177) | same |
| `alpenflight/server/CONVENTIONS.md` (~L271) | drop the `-- covers tombstones: <reason>` mandate; the reason moves into the **index name** (`ix_pda_planning_day` → `ix_pda_planning_day_covers_tombstones_cascade_target`) |
| `alpenflight/web/eslint.config.mjs` (~L47) | drop "+ reviewer approval comment" from the `bypassSecurityTrust` message |
| `docs/modernization/stories/_BOYSCOUT.md` (~L108) | retire the narrow `[COMMENT-STRIP]` item — this skill supersedes it |
| memory `feedback_self_explanatory_no_history_comments` | rewrite: the rule is now *no comments*, not *why-only* |
| `.gitignore` | add `.comment-strip/` |

The detector that keeps the policy from decaying is a stage in
`alpenflight/web/scripts/preflight.sh` and a CI step on every push (no path filter —
a path-skipped guard reads green having run nothing):

```
node .claude/skills/comment-strip/scripts/strip.mjs --check alpenflight e2e
```

`--check` exits non-zero on any prose comment **and on any leftover `RENAME:`
marker**. Policy text decays; a gate does not.

**Wire the gate when it can land green — after the last batch, not with the repeal.**
The repeal commit is what stops a worker writing fresh comments into the tree; the gate
is what stops the policy decaying later, and it can only do that from green. Landed with
the repeal it reds every push for the sweep's whole length (~21,000 violations on day
one), and a permanently-red required check hides every real red behind the expected one.
So: repeal first, sweep, then land `--check` as the last commit before the gate run. Only
when the sweep finishes in one sitting can the two share a commit.

## Step 2 — sweep, batch by batch

Ten batches. Each is one revert unit and gets its own cheap gate.

Every `alpenflight/*` module is its own **standalone** Gradle build (each has its
own `settings.gradle.kts` + wrapper), so every gate below runs from that module's
directory with **bare** task names — there is no aggregate root build and no
`:module:` path. A module's `build.gradle.kts` / `settings.gradle.kts` belongs to
**its own batch**, not to batch 10: it is what that batch's gate compiles with.

| # | Batch | Paths | Cheap gate (from the module dir) |
| --- | --- | --- | --- |
| 1 | `server-main` | `alpenflight/server/src/main` *(excluding `resources/db/migration`)*, `server/build.gradle.kts`, `server/settings.gradle.kts` | `./gradlew compileJava` |
| 2 | `server-test` | `alpenflight/server/src/test`, `src/archDemo`, `src/nullawayDemo` | `./gradlew compileTestJava` |
| 3 | `web-src` | `alpenflight/web/src` | `pnpm lint` |
| 4 | `web-e2e` | `alpenflight/web/e2e`, `alpenflight/web/scripts`, `e2e/` | `pnpm lint` |
| 5 | `database-extract` | `alpenflight/database` *(incl. `extract/build.gradle.kts`, `extract/settings.gradle.kts`)* | `cd alpenflight/database/extract && ./gradlew compileJava` |
| 6 | `migration-tool` | `alpenflight/migration-tool` *(incl. its build files)* | `./gradlew compileJava compileTestJava` |
| 7 | `migration-bundle` | `alpenflight/migration-bundle` *(incl. its build files)* | `./gradlew compileJava compileTestJava` |
| 8 | `migrations-sql` | `alpenflight/server/src/main/resources/db/migration` | `--check` only |
| 9 | `ops-shell` | `alpenflight/ops`, `alpenflight/auth`, root `*.sh` | `bash -n` per file |
| 10 | `module-root config` | config that sits outside every batch glob yet still reds the final tree-wide `--check`: `alpenflight/web/orval.config.ts`, `alpenflight/web/eslint.config.mjs`, `web/pnpm-workspace.yaml`, `qodana.yaml`, `docker-compose.yml`, root `*.mjs` | `pnpm lint` + `--check` |

Batch 5's build is `rootProject.name = "alpenflight-legacy-extract"` — `:extract:compileJava`
does not resolve from anywhere.

Batches 1–2 gate on `compileJava`/`compileTestJava` for speed, but a judge who
verifies a file with `javac -proc:none` sees **neither NullAway nor ErrorProne**:
both are Gradle-configured annotation processors, so a nullness or ErrorProne
regression a strip introduces stays invisible until the real build runs. Run the
Gradle task, not bare `javac`, before calling a Java batch green.

Batch 8 note: Flyway checksums every migration, so stripping them invalidates
any database that already ran them. There is no production database, so this is
accepted — but **say so in the final report**: dev boxes and the LAN Postgres
used by the real-idp gate need `flyway repair` or a recreate.

### Per batch

1. **Shard** the batch's files into disjoint groups, grouped within one package so
   a judge sees enough context to name things well. Size a shard by **comment
   count, not file count** — density varies ~10× across batches (an e2e spec
   carries ~45 comments, a Java file ~4), so 30–50 files is a sane shard in
   `server-*` and a judge-drowning one in `web-e2e`. Target **~200 comments** per
   shard; `--check <files…>` counts them before you split.
2. **Dispatch `comment-judge` subagents, 8 in flight.** Each shard runs the
   stripper over *its own files only*:

   ```
   node .claude/skills/comment-strip/scripts/strip.mjs \
     --shard <batch>-<n> --manifest .comment-strip/manifest/<batch>-<n>.jsonl <files…>
   ```

   The script deletes; the judge judges. The script asserts the **non-comment
   token stream is unchanged** (it compares every string/regex/heredoc literal
   before and after) and aborts on any file where that fails, leaving it
   untouched. 31 lines in this repo have `//` inside a string literal — including
   an SSRF `@Pattern` regex — so this guarantee is the reason a script does the
   deleting and a model does not.
3. Each judge applies **file-local renames directly** and leaves
   **`// RENAME: old -> new` markers** for anything crossing a file boundary.
   See `.claude/agents/comment-judge.md`.
4. **Commit twice**: `comment-strip(<batch>): strip` then
   `comment-strip(<batch>): renames`. Mechanical and judgment never mix in one
   diff — the first is machine-verifiable, the second needs human eyes.
5. Run the cheap gate. Run `pnpm format:fix` for web batches; removing a comment
   can free Prettier to re-wrap the code it was splitting.
6. Update `.comment-strip/state.json` and print
   `batch 4/10 · shard 7/12 · 812 removed · 37 renames · 2 escalations`.

## Step 3 — the serial rename pass

Only after **all** batches are done.

1. `rg 'RENAME:' alpenflight e2e` collects every proposal. Markers move with the
   code, so unlike a report file with line numbers they cannot go stale.
2. Dedup, and resolve collisions where two judges proposed the same new name for
   different symbols.
3. Apply in **groups of ~20, one compile per group**, deleting each marker as its
   rename lands. Bisect a group only when it fails — per-rename compiles are
   unaffordable at this volume, and grouping keeps attribution.
4. **Regenerate in the same commit** — a rename that skips this reds CI for a
   reason that looks nothing like its cause:

   ```
   ./gradlew generateOpenApiSnapshot     # in alpenflight/server
   pnpm generate-api                     # in alpenflight/web
   ```

   Commit the regenerated client with the renames. Split across two commits, a
   revert of one leaves a tree that compiles but is inconsistent.
5. `rg 'RENAME:'` must come back empty. `--check` treats a leftover as an error.

## Step 4 — gate and hand over

Run the full gate once: `pnpm preflight` (gradle suite + web + gallery + e2e).
Then open the PR and **stop**. Never run this unattended — it repeals a policy,
rewrites ~1,800 files and renames across module boundaries.

**Report:**

- files touched, comments removed, per-batch breakdown
- surviving `// ext:` markers, each with its justification
- the `mocked seams (N)` section from the final `--check` run, verbatim — that is
  the PR's "Mocked seams" list
- renames applied vs. proposed-and-skipped
- **entries above the score threshold that no judge reviewed** — state the count
  explicitly; a silent cap reads as full coverage when it was not
- the `flyway repair` footnote for dev databases

## The script

`scripts/strip.mjs` — Node 22, stdlib only, no dependencies. Lexers for
Java/Kotlin/TS/JS/CSS, SQL, shell, YAML and HTML that understand string, char,
text-block, template, regex, dollar-quote and heredoc contexts.
`scripts/strip.test.mjs` covers the traps (`node --test`). Line endings are
preserved byte-for-byte.

```
strip.mjs <paths…>                     strip in place
strip.mjs --manifest <f> <paths…>      also write a scored JSONL manifest
strip.mjs --check <paths…>             detector: non-zero on prose or RENAME leftovers
```

A shell heredoc body is classified by the command it feeds. Fed to an
interpreter (`python3`, `node`, `psql`, `sh`/`bash`, `jq`, and the same through a
wrapper such as `ssh`/`docker`/`timeout`) it is a *program*: lexed in that
language, its comments stripped, its own string literals joining the
unchanged-token-stream guarantee. Printed for a human (`cat`/`echo`/`printf`
with no redirection away from the terminal) it is *data* — an `INFO` banner or
generated config, where a leading `#` is output the operator reads — and stays
byte-identical. Anything else, including a printer whose output is redirected to
a file, is *undetermined*: `--check` reports its comments, nothing strips them.
With an unquoted delimiter the shell expands the body, so a comment carrying
`$`, a backtick or a backslash is reported rather than stripped. Python
docstrings are string literals, not comments: reported, never auto-removed.

One manifest entry is one removed *block* of rationale: a block comment, or a
contiguous run of own-line `//` / `#` / `--` lines, spanning `line`–`endLine`.
Each entry is scored on its combined text so judges read the dense ones first:
length, causal vocabulary (`because`, `never`, `workaround`, `defaults to`),
external facts (browser/version/URL/story-id), an opaque attached identifier, a
bare literal nearby. Threshold 8. Scoring a run line-by-line would rank the
densest rationale in a batch below one-line narration.

**Excluded everywhere:** `flsserver/`, `flsweb/` (read-only upstream),
`node_modules/`, `build/`, `target/`, `.gradle/`, `dist/`, `.angular/`,
generated clients, `gradlew*`, all Markdown and `docs/`.
