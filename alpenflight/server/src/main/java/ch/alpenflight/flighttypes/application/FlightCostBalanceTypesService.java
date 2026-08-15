package ch.alpenflight.flighttypes.application;

import ch.alpenflight.flighttypes.application.FlightCostBalanceTypeDtos.FlightCostBalanceTypeResponse;
import ch.alpenflight.flighttypes.domain.FlightCostBalanceTypeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
