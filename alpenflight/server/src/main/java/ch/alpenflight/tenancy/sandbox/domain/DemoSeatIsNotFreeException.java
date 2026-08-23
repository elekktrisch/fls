package ch.alpenflight.tenancy.sandbox.domain;

public class DemoSeatIsNotFreeException extends IllegalStateException {

    private final int seatNumber;
    private final DemoSeat.LeaseState leaseState;

    public DemoSeatIsNotFreeException(int seatNumber, DemoSeat.LeaseState leaseState) {
        super("demo seat " + seatNumber + " is " + leaseState
                + "; only a FREE seat accepts a new lease");
        this.seatNumber = seatNumber;
        this.leaseState = leaseState;
    }

    public int seatNumber() {
        return this.seatNumber;
    }

    public DemoSeat.LeaseState leaseState() {
        return this.leaseState;
    }
}
