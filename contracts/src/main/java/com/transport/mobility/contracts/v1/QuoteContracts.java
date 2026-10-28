package com.transport.mobility.contracts.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuoteContracts {
    private QuoteContracts() {}

    public record Selection(int travelerCount, List<String> participants, List<SelectedSegment> segments, String promoCode) {}
    public record SelectedSegment(int segmentIndex, String origin, String destination,
            OffsetDateTime plannedDeparture, OffsetDateTime plannedArrival, List<SelectedAllocation> allocations) {}
    public record SelectedAllocation(Offer.Operator operator, String offerId, int passengerCount,
            List<String> participantAliases, String driverAlias, LocalDate startDate, LocalDate endDate) {}

    public record Basket(Itinerary itinerary, String promoCode, Map<UUID, String> categories) {}
    public record Issue(String actorUserId, Basket basket) {}
    public record Claim(String actorUserId, UUID reservationId) {}
    public record PreviewBatch(List<Basket> baskets) {}

    public enum Status { ISSUED, CLAIMED, EXPIRED, CONSUMED, VOID }

    public record Quote(UUID id, UUID reservationId, Status status, Instant createdAt, Instant expiresAt,
            String itineraryHash, int version, Price price) {}
    public record Price(Money total, String promoCode, String rulesetVersion, Itinerary itinerary, Map<UUID, String> categories, List<Line> lines) {}
    public record Line(UUID lineId, UUID allocationId, int segmentIndex, long grossMinor, long feeMinor,
            long discountMinor, long totalMinor, List<Adjustment> adjustments) {}
    public record Adjustment(String sourceType, String sourceId, int sourceVersion, long amountMinor, String parameters) {}
}
