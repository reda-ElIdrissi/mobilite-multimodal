package com.transport.mobility.contracts.v1;

import java.time.OffsetDateTime;
import java.util.List;

public record Segment(int segmentIndex, Place origin, Place destination, OffsetDateTime plannedDeparture,
                      OffsetDateTime plannedArrival, List<Allocation> allocations) {
    public Segment {
        allocations = List.copyOf(allocations);
    }
}
