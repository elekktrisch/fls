package ch.alpenflight.tenancy.sandbox.domain;

import java.util.List;

public interface DemoSeatRepository {

    List<DemoSeat> findAllInSeatNumberOrder();

    DemoSeat save(DemoSeat seat);
}
