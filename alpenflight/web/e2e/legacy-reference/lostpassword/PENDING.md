# J-19 password recovery — legacy reference shots PENDING

`e2e/tests/auth/lostpassword-parity-J19.spec.ts` captures the legacy password-recovery screens. The
spec drives the Node-8 `flsweb` and Mono `flsserver` and MSSQL stack. That stack does not run on this
dev box, so the PNGs are not committed here yet.

Expected view filenames. The view key is the pairing key that CI `add_shot` uses with `side=legacy`:

```
lostpassword/
├── form.png       the legacy /lostpassword form (one text field, one button)
├── success.png    the same screen after the send, with the success message
└── confirm.png    the legacy /confirm form (two password fields, one button)
```

`alpenflight-proof-fanout.yml` runs the spec, stages the three PNGs, and names them
`legacy-lostpassword-<view>.png`. Copy them here under the bare view name after the first green run.
Then delete this file.

**No parity claim.** ADR 0007 gives every credential action to Keycloak. The legacy app collects the
new password itself. AlpenFlight sends a reset link and Keycloak collects the password. The two
products differ by design. These shots let the operator read the old screens beside the new ones.

**No per-push pairing yet.** `ci.yml` gets a J-19 `add_pair` block after these refs land and after
the AlpenFlight `/lostpassword` and `/confirm` captures exist.
