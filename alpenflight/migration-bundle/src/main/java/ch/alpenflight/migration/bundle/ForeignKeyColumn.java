package ch.alpenflight.migration.bundle;

import org.jspecify.annotations.Nullable;

/**
 * Declares a non-canonical FK {@code column} on a mapper's destination row and
 * the bundle {@link EntityType} it resolves through. Opt-in via
 * {@link Mapper#foreignKeyColumns()}.
 *
 * <p>The default resolver convention derives the FK column from the target as
 * {@code <target.name().toLowerCase()>_id} — adequate while each FK target is
 * referenced by exactly one canonically-named column (S-187 vertical slice). An
 * entity that names its FK columns differently (e.g. {@code Aircraft.homebase_id}
 * → LOCATION) or references the SAME target through MULTIPLE columns (e.g.
 * {@code Aircraft.managing_club_id} AND {@code Aircraft.owner_club_id} both →
 * CLUB) cannot be expressed by the one-column-per-target convention. Such a
 * mapper declares each {@code (column, target)} pair explicitly here; the
 * resolver iterates the declarations (so two columns to one target both
 * resolve) and falls back to the convention for any target NOT declared.
 *
 * <p>{@code disambiguatorColumn} is the wire-only field naming the referencer's
 * OWN legacy club, used only when {@code target} {@link EntityType#fansOut()} to
 * pick which per-club replica a {@code (legacy_guid, club_id)} composite lookup
 * lands on. {@code null} for a non-fan-out target, and — for a fan-out target —
 * lets the resolver fall back to its default referencer-club field. A fan-out
 * referencer that carries its own club under a non-default column (e.g. AIRCRAFT
 * disambiguating {@code homebase_id} via its own {@code managing_club_id}, T-05b)
 * names that column here.
 *
 * <p>Distinct from {@link Mapper#foreignKeys()}: that list still enumerates the
 * FK TARGETS (driving the ArchUnit ingest-order rule and the parity oracle);
 * this record only overrides the COLUMN NAME(S) per target. A mapper that
 * declares {@code foreignKeyColumns()} must still list each referenced target in
 * {@code foreignKeys()}. Default empty so existing mappers are unaffected — a
 * mapper opts in by overriding {@link Mapper#foreignKeyColumns()}.
 */
public record ForeignKeyColumn(
        String column, EntityType target, @Nullable String disambiguatorColumn) {

    public ForeignKeyColumn {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("ForeignKeyColumn column must be non-blank");
        }
        if (target == null) {
            throw new IllegalArgumentException("ForeignKeyColumn target must not be null");
        }
    }

    /** Non-fan-out FK column: no disambiguator needed. */
    public ForeignKeyColumn(String column, EntityType target) {
        this(column, target, null);
    }
}
