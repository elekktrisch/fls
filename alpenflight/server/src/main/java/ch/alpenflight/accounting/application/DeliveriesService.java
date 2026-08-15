package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryDetail;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryOverview;
import ch.alpenflight.accounting.application.DeliveryDtos.DeliveryPage;
import ch.alpenflight.accounting.domain.Delivery;
import ch.alpenflight.accounting.domain.DeliveryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DeliveriesService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;

    private final DeliveryRepository deliveries;

    public DeliveriesService(DeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    public DeliveryPage page(int pageStart, int pageSize) {
        int safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int safeStart = Math.max(pageStart, 0);
        int pageNumber = safeStart / safeSize;
        List<DeliveryOverview> items = deliveries.findActivePage(PageRequest.of(pageNumber, safeSize)).stream()
                .map(DeliveryDetailMapper::toOverview)
                .toList();
        return new DeliveryPage(items, safeStart, safeSize, deliveries.countActive());
    }

    public DeliveryDetail getDetail(UUID id) {
        return DeliveryDetailMapper.toDetail(deliveries.findActiveById(id)
                .orElseThrow(() -> new DeliveryNotFoundException(id)));
    }
}
