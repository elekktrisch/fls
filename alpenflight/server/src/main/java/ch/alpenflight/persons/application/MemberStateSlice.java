package ch.alpenflight.persons.application;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.domain.MemberStateRepository;
import ch.alpenflight.persons.application.PersonDtos.MemberStateListItem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped read slice for {@code member_state} — feeds the form
 * picker via {@code GET /api/v1/club/member-states}. Hibernate's
 * {@code @TenantId} discriminator on {@code MemberState.clubId} scopes the
 * underlying {@code findAll()} to the caller's tenant automatically, so
 * this read intentionally does NOT call any unscoped path.
 *
 * <p>Counterpart to S-047's cross-tenant reference-data pattern: same shape
 * (slim listitem, sorted by name, no admin CRUD here) but per-club scope.
 * The first per-club listitem endpoint in the system — establishes the
 * {@code /api/v1/club/<thing>} URL convention. Full {@code member_state}
 * admin CRUD is deferred to a follow-up story.
 */
@Service
public class MemberStateSlice {

    private final MemberStateRepository repository;

    public MemberStateSlice(MemberStateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<MemberStateListItem> listInCurrentTenant() {
        return repository.findAll().stream()
                .map(MemberStateSlice::toListItem)
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    /**
     * Returns the underlying entities for callers that need {@code id +
     * name} as a pair (e.g. the person-mapper's display-name lookup).
     */
    @Transactional(readOnly = true)
    public List<MemberStateNameRow> nameRowsInCurrentTenant() {
        return repository.findAll().stream()
                .map(MemberStateSlice::toNameRow)
                .toList();
    }

    private static MemberStateListItem toListItem(MemberState ms) {
        return new MemberStateListItem(
                Objects.requireNonNull(ms.getId(), "MemberState id is null"),
                ms.getName());
    }

    private static MemberStateNameRow toNameRow(MemberState ms) {
        UUID id = Objects.requireNonNull(ms.getId(), "MemberState id is null");
        String name = ms.getName();
        return new MemberStateNameRow(id, name);
    }

    /** Public lookup row consumed by {@link PersonMapper.MemberStateNameLookup}. */
    public record MemberStateNameRow(UUID id, String name) implements PersonMapper.MemberStateNameRow {
    }
}
