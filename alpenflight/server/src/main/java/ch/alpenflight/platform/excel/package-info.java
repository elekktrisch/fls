/**
 * Shared-kernel Apache POI / SXSSF helper for synchronous Excel (.xlsx)
 * exports. {@link ch.alpenflight.platform.excel.ExcelExportSupport} keeps the
 * POI verbosity (streaming workbook, per-cell {@code CellStyle} data-format
 * caching, autosize column-tracking) out of feature code. Introduced at J-7
 * T-06 (S-094) as AlpenFlight's first synchronous Excel export infrastructure;
 * consumed by the flight-reports export (J-7 T-07) and, later, the
 * deliveries/statistics exports (J-10).
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.platform.excel;
