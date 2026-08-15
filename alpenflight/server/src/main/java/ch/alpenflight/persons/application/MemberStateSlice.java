package ch.alpenflight.persons.application;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.domain.MemberStateRepository;
import ch.alpenflight.persons.application.PersonDtos.MemberStateListItem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public record MemberStateNameRow(UUID id, String name) implements PersonMapper.MemberStateNameRow {
    }
}
