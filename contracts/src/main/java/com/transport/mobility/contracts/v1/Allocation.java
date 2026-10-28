package com.transport.mobility.contracts.v1;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Allocation(UUID allocationId, Offer.Operator operator, String offerId, Offer.Mode mode,
                         int seatsOrPassengers, long quantity, Offer.UnitType unitType,
                         OffsetDateTime departureAt, OffsetDateTime arrivalAt, LocalDate startDate,
                         LocalDate endDate, List<String> participantAliases, String driverAlias,
                         Money unitPrice, Long priceVersion) {
    public Allocation {
        participantAliases = List.copyOf(participantAliases);
    }
}
