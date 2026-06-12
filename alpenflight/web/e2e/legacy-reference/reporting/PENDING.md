# J-7 reporting — legacy reference shots PENDING

The legacy `flsweb` reporting screens are captured by
`e2e/tests/reporting/reporting-parity-J7.spec.ts` (the legacy half of the J-7
side-by-side parity gallery). That spec drives the Node-8 `flsweb` + Mono
`flsserver` + MSSQL stack, which **does not run on the Alpine/musl dev box** (no
browser, no Mono/MSSQL) — so the PNGs are NOT committed here yet.

Expected view filenames (the pairing key CI `add_pair` uses, side=legacy):

```
reporting/
├── picker.png   the legacy /flightreports category + canned-report tile grid
├── result.png   a canned report's filter panel + summary + flights table
└── custom.png   the custom-builder filter form
```

These land here ONCE the fan-out workflow (`alpenflight-proof-fanout.yml`) brings
up the legacy stack, runs `reporting-parity-J7.spec.ts`, and commits the captured
full-page PNGs (same capture-once lineage as `planning/`). Until then CI's
`add_pair` degrades gracefully to the AlpenFlight side only.

Delete this file when the three PNGs are committed.
