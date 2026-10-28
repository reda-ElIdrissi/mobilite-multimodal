package com.transport.mobility.contracts.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Offer(Operator operator, Mode mode, String offerId, Place origin, Place destination,
                    OffsetDateTime departureAt, OffsetDateTime arrivalAt, LocalDate startDate,
                    LocalDate endDate, long unitPriceMinor, UnitType unitType, Long priceVersion,
                    int capacity, AvailabilityKind availabilityKind, String category, Instant observedAt) {
    public enum Operator { RAIL, AIR, CAR }
    public enum Mode { TRAIN, AVION, VOITURE }
    public enum UnitType { SEAT, DAY }
    public enum AvailabilityKind { AVAILABLE_SEATS, VEHICLE_CAPACITY }
}
