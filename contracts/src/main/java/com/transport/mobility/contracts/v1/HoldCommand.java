package com.transport.mobility.contracts.v1;

import java.time.LocalDate;
import java.util.UUID;

public record HoldCommand(UUID operationId, String offerId, Integer seats, CarDetails car,
                          String actorUserId, UUID reservationLineId) {
    public record CarDetails(LocalDate startDate, LocalDate endDate, String origin, String destination,
                             int passengerCount, String driverAlias, long expectedDailyRateMinor,
                             long expectedPriceVersion) {}
}
