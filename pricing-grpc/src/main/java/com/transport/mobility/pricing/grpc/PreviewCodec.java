package com.transport.mobility.pricing.grpc;

import com.transport.mobility.contracts.v1.Allocation;
import com.transport.mobility.contracts.v1.Itinerary;
import com.transport.mobility.contracts.v1.Money;
import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.Place;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.mobility.contracts.v1.Segment;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public final class PreviewCodec {
    private PreviewCodec() {}

    public static PricingPreview.PreviewRequest encode(PreviewBatch batch) {
        return PricingPreview.PreviewRequest.newBuilder()
                .addAllBaskets(batch.baskets().stream().map(PreviewCodec::encodeBasket).toList()).build();
    }

    public static PreviewBatch decode(PricingPreview.PreviewRequest request) {
        if (request.getBasketsCount() < 1 || request.getBasketsCount() > 20) {
            throw new IllegalArgumentException("Expected 1 to 20 baskets");
        }
        return new PreviewBatch(request.getBasketsList().stream().map(PreviewCodec::decodeBasket).toList());
    }

    private static PricingPreview.Basket encodeBasket(Basket basket) {
        var itinerary = basket.itinerary();
        var wire = PricingPreview.Basket.newBuilder().setTravelerCount(itinerary.travelerCount())
                .addAllParticipants(itinerary.participants()).addAllTimingAssumptions(itinerary.timingAssumptions())
                .addAllSegments(itinerary.segments().stream().map(PreviewCodec::encodeSegment).toList());
        if (basket.promoCode() != null) {
            wire.setPromoCode(basket.promoCode());
        }
        if (basket.categories() != null) {
            basket.categories().forEach((id, value) -> wire.putCategories(id.toString(), value));
        }
        return wire.build();
    }

    private static Basket decodeBasket(PricingPreview.Basket wire) {
        var categories = wire.getCategoriesMap().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(entry -> UUID.fromString(entry.getKey()), Map.Entry::getValue));
        var itinerary = new Itinerary(wire.getTravelerCount(), wire.getParticipantsList(),
                wire.getSegmentsList().stream().map(PreviewCodec::decodeSegment).toList(), wire.getTimingAssumptionsList());
        return new Basket(itinerary, wire.hasPromoCode() ? wire.getPromoCode() : null, categories);
    }

    private static PricingPreview.Segment encodeSegment(Segment segment) {
        var wire = PricingPreview.Segment.newBuilder().setIndex(segment.segmentIndex())
                .addAllAllocations(segment.allocations().stream().map(PreviewCodec::encodeAllocation).toList());
        if (segment.origin() != null) wire.setOrigin(encodePlace(segment.origin()));
        if (segment.destination() != null) wire.setDestination(encodePlace(segment.destination()));
        if (segment.plannedDeparture() != null) wire.setPlannedDeparture(segment.plannedDeparture().toString());
        if (segment.plannedArrival() != null) wire.setPlannedArrival(segment.plannedArrival().toString());
        return wire.build();
    }

    private static Segment decodeSegment(PricingPreview.Segment segment) {
        return new Segment(segment.getIndex(), segment.hasOrigin() ? decodePlace(segment.getOrigin()) : null,
                segment.hasDestination() ? decodePlace(segment.getDestination()) : null,
                segment.hasPlannedDeparture() ? OffsetDateTime.parse(segment.getPlannedDeparture()) : null,
                segment.hasPlannedArrival() ? OffsetDateTime.parse(segment.getPlannedArrival()) : null,
                segment.getAllocationsList().stream().map(PreviewCodec::decodeAllocation).toList());
    }

    private static PricingPreview.Place encodePlace(Place place) {
        return PricingPreview.Place.newBuilder().setCityCode(place.cityCode()).setDisplayName(place.displayName())
                .setZoneId(place.zoneId().getId()).build();
    }

    private static Place decodePlace(PricingPreview.Place wire) {
        return new Place(wire.getCityCode(), wire.getDisplayName(), ZoneId.of(wire.getZoneId()));
    }

    private static PricingPreview.Allocation encodeAllocation(Allocation allocation) {
        var wire = PricingPreview.Allocation.newBuilder().setId(allocation.allocationId().toString())
                .setOperator(PricingPreview.Operator.valueOf(allocation.operator().name())).setOfferId(allocation.offerId())
                .setMode(PricingPreview.Mode.valueOf(allocation.mode().name())).setSeatsOrPassengers(allocation.seatsOrPassengers())
                .setQuantity(allocation.quantity()).setUnit(PricingPreview.Unit.valueOf(allocation.unitType().name()))
                .addAllParticipantAliases(allocation.participantAliases()).setUnitPrice(encodeMoney(allocation.unitPrice()));
        if (allocation.departureAt() != null) wire.setDepartureAt(allocation.departureAt().toString());
        if (allocation.arrivalAt() != null) wire.setArrivalAt(allocation.arrivalAt().toString());
        if (allocation.startDate() != null) wire.setStartDate(allocation.startDate().toString());
        if (allocation.endDate() != null) wire.setEndDate(allocation.endDate().toString());
        if (allocation.driverAlias() != null) wire.setDriverAlias(allocation.driverAlias());
        if (allocation.priceVersion() != null) wire.setPriceVersion(allocation.priceVersion());
        return wire.build();
    }

    private static Allocation decodeAllocation(PricingPreview.Allocation a) {
        return new Allocation(UUID.fromString(a.getId()), Offer.Operator.valueOf(a.getOperator().name()),
                a.getOfferId(), Offer.Mode.valueOf(a.getMode().name()), a.getSeatsOrPassengers(), a.getQuantity(),
                Offer.UnitType.valueOf(a.getUnit().name()),
                a.hasDepartureAt() ? OffsetDateTime.parse(a.getDepartureAt()) : null,
                a.hasArrivalAt() ? OffsetDateTime.parse(a.getArrivalAt()) : null,
                a.hasStartDate() ? LocalDate.parse(a.getStartDate()) : null,
                a.hasEndDate() ? LocalDate.parse(a.getEndDate()) : null, a.getParticipantAliasesList(),
                a.hasDriverAlias() ? a.getDriverAlias() : null, decodeMoney(a.getUnitPrice()),
                a.hasPriceVersion() ? a.getPriceVersion() : null);
    }

    private static PricingPreview.Money encodeMoney(Money money) {
        return PricingPreview.Money.newBuilder().setAmountMinor(money.amountMinor())
                .setCurrency(PricingPreview.Currency.valueOf(money.currency().name())).build();
    }

    private static Money decodeMoney(PricingPreview.Money wire) {
        return new Money(wire.getAmountMinor(), Money.Currency.valueOf(wire.getCurrency().name()));
    }

    public static PricingPreview.PreviewResponse encodePrices(List<Price> prices) {
        return PricingPreview.PreviewResponse.newBuilder().addAllPrices(prices.stream().map(price -> {
            var value = PricingPreview.Price.newBuilder().setTotal(encodeMoney(price.total()))
                    .setRulesetVersion(price.rulesetVersion()).addAllLines(price.lines().stream().map(PreviewCodec::encodeLine).toList());
            if (price.promoCode() != null) value.setPromoCode(price.promoCode());
            return value.build();
        }).toList()).build();
    }

    private static PricingPreview.Line encodeLine(Line line) {
        return PricingPreview.Line.newBuilder().setAllocationId(line.allocationId().toString())
                .setSegmentIndex(line.segmentIndex()).setGrossMinor(line.grossMinor()).setFeeMinor(line.feeMinor())
                .setDiscountMinor(line.discountMinor()).setTotalMinor(line.totalMinor())
                .addAllAdjustments(line.adjustments().stream().map(adjustment -> PricingPreview.Adjustment.newBuilder()
                        .setSourceType(adjustment.sourceType()).setSourceId(adjustment.sourceId())
                        .setSourceVersion(adjustment.sourceVersion()).setAmountMinor(adjustment.amountMinor())
                        .setParameters(adjustment.parameters()).build()).toList()).build();
    }

    public static List<Price> decodePrices(PreviewBatch batch, PricingPreview.PreviewResponse response) {
        if (response.getPricesCount() != batch.baskets().size()) {
            throw new IllegalArgumentException("Unexpected preview response count");
        }
        return IntStream.range(0, response.getPricesCount()).mapToObj(index -> {
            var basket = batch.baskets().get(index);
            var wire = response.getPrices(index);
            return new Price(decodeMoney(wire.getTotal()), wire.hasPromoCode() ? wire.getPromoCode() : null,
                    wire.getRulesetVersion(), basket.itinerary(), basket.categories() == null ? Map.of() : basket.categories(),
                    wire.getLinesList().stream().map(PreviewCodec::decodeLine).toList());
        }).toList();
    }

    private static Line decodeLine(PricingPreview.Line line) {
        return new Line(null, UUID.fromString(line.getAllocationId()), line.getSegmentIndex(), line.getGrossMinor(),
                line.getFeeMinor(), line.getDiscountMinor(), line.getTotalMinor(), line.getAdjustmentsList().stream()
                        .map(a -> new Adjustment(a.getSourceType(), a.getSourceId(), a.getSourceVersion(),
                                a.getAmountMinor(), a.getParameters())).toList());
    }
}
