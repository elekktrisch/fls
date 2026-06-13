# J-8 accounting — legacy reference shots PENDING

The legacy `flsweb` accounting-rule-filter screens (`masterdata/accountingRules/`)
are captured by `e2e/tests/accounting/accounting-parity-J8.spec.ts` (the legacy
half of the J-8 side-by-side parity gallery). That spec drives the Node-8
`flsweb` + Mono `flsserver` + MSSQL stack, which **does not run on the
Alpine/musl dev box** (no browser, no Mono/MSSQL) — so the PNGs are NOT committed
here yet.

Expected view filenames (the pairing key CI `add_shot` uses, side=legacy):

```
accounting/
├── list.png   the legacy /masterdata/accountingRuleFilters ng-table
│              (Active · Name · Description · Target · Type)
└── form.png   the legacy rule-filter edit form (type selectize + the
               filter-type-driven article-target / aircraft-filter sections)
```

These land here ONCE the fan-out workflow (`alpenflight-proof-fanout.yml`) brings
up the legacy stack, runs `accounting-parity-J8.spec.ts`, and commits the
captured full-page PNGs (same capture-once lineage as `planning/`). Until then
CI's `add_shot` degrades gracefully to the AlpenFlight side only.

Delete this file when the two PNGs are committed.
