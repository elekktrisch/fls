package ch.alpenflight.flighttypes.application;

import ch.alpenflight.flighttypes.application.FlightCostBalanceTypeDtos.FlightCostBalanceTypeResponse;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service for the {@link ch.alpenflight.flighttypes.domain.FlightCostBalanceType}
 * reference catalogue. Cross-tenant (S-047 pattern); rows are Flyway-seeded
 * (V3 — 5 rows). Sysadmin admin CRUD is deferred until S-058 / S-072 demand
 * it; until then the only caller is the FE picker.
 */
@Service
@Transactional(readOnly = true)
public class FlightCostBalanceTypesService {

    private final FlightCostBalanceTypeRepository repo;

    public FlightCostBalanceTypesService(FlightCostBalanceTypeRepository repo) {
        this.repo = repo;
    }

    public List<FlightCostBalanceTypeResponse> listAll() {
        return repo.findAllOrderedByLegacyId().stream()
                .map(FlightTypeMapper::toResponse)
                .toList();
    }
}
