---
id: E-11
title: Excel / report export parity
status: todo
adr_refs: [0012]
---

## Goal
Build the Apache POI export infrastructure (SXSSF streaming for large workbooks, `ExcelExportSupport` helper class, `java.util.zip.ZipOutputStream` for the per-recipient zip) so that E-10's scheduled-job ports (DeliveryMailExport, AircraftStatisticReport) and any synchronous report endpoint produce feature-equivalent (C16) Excel output. Includes parity verification harness.

## Scope
- In: POI infrastructure + helper class; column-spec inventory of every legacy export; per-export parity verification harness (cell-by-cell tolerant comparator); sync flight-reports export.
- Out: the *scheduled* jobs themselves (live in E-10 and just call into this epic's POI helpers).

## Stories
- [ ] S-093 — Inventory every Excel export (column/row spec each emits) — becomes the parity oracle
- [ ] S-094 — `ExcelExportSupport` helper class (header styles, currency / date formatting, auto-size, SXSSF streaming defaults)
- [ ] S-095 — Port flight-reports synchronous Excel export
- [ ] S-096 — Parity-verification harness (run legacy + new against same input, diff cells with tolerant comparator)

## Done when
- Helper class wraps every export pattern in <50 lines per call site.
- Parity verification harness produces zero-delta reports against legacy outputs for at least 3 production-shape inputs per export (DeliveryMailExport, AircraftStatisticReport, FlightReports).
- The verification harness is part of CI on `next/server/` (smoke run against fixture data).
