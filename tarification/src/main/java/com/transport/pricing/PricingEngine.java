package com.transport.pricing;

import com.transport.mobility.contracts.v1.Allocation;
import com.transport.mobility.contracts.v1.Money;
import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.pricing.PricingRules.*;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import tools.jackson.databind.json.JsonMapper;

final class PricingEngine {
    private static final BigInteger BASIS = BigInteger.valueOf(10000);
    private final JsonMapper json;

    PricingEngine(JsonMapper json) {
        this.json = json;
    }

    Price calculate(Basket basket, List<Rule> rules, Promotion promotion, Instant now, boolean issue) {
        if (basket == null || basket.itinerary() == null || basket.itinerary().travelerCount() < 1) {
            throw new IllegalArgumentException("Invalid basket");
        }
        var lines = new ArrayList<WorkingLine>();
        var ids = new HashSet<UUID>();
        for (var segment : basket.itinerary().segments()) {
            for (var allocation : segment.allocations()) {
                if (allocation.allocationId() == null || !ids.add(allocation.allocationId())) {
                    throw new IllegalArgumentException("Invalid allocation id");
                }
                lines.add(new WorkingLine(segment.segmentIndex(), allocation));
            }
        }
        if (lines.isEmpty() || lines.size() > 30) {
            throw new IllegalArgumentException("Basket must contain 1 to 30 allocations");
        }
        long gross = sum(lines, line -> line.gross);
        long modes = lines.stream().map(line -> line.allocation.mode()).distinct().count();
        var active = rules.stream()
                .filter(rule -> valid(rule.settings(), now))
                .sorted(Comparator.comparingInt((Rule rule) -> rule.settings().priority()).thenComparing(Rule::id))
                .toList();

        for (boolean fees : new boolean[]{true, false}) {
            for (Rule rule : active) {
                var spec = rule.settings();
                boolean feeRule = spec.type() == Type.FIXED_FEE_PER_ALLOCATION || spec.type() == Type.FIXED_FEE_PER_BOOKING;
                if (fees != feeRule) {
                    continue;
                }
                var eligible = lines.stream().filter(line -> (spec.mode() == null || spec.mode() == line.allocation.mode())
                        && matches(spec.condition(), line, basket, gross, modes)).toList();
                var template = new Adjustment("RULE", rule.id().toString(), rule.version(), 0, json.writeValueAsString(spec));
                if (eligible.isEmpty()) {
                    continue;
                }
                switch (spec.type()) {
                    case FIXED_FEE_PER_ALLOCATION -> eligible.forEach(line -> line.apply(spec.amountMinor(), template));
                    case FIXED_FEE_PER_BOOKING -> distribute(eligible, spec.amountMinor(), true, template);
                    case FIXED_DISCOUNT -> distribute(eligible, Math.min(spec.amountMinor(), sum(eligible, WorkingLine::net)), false, template);
                    case PERCENT_DISCOUNT -> distribute(eligible, percentage(sum(eligible, WorkingLine::net), spec.rateBps()), false, template);
                }
            }
        }

        if (basket.promoCode() != null) {
            if (promotion == null || !PricingRules.validAt(promotion.settings().active(),
                    promotion.settings().validFrom(), promotion.settings().validTo(), now)) {
                throw new IllegalArgumentException("Promotion is unknown or inactive");
            }
            var settings = promotion.settings();
            var eligible = lines.stream()
                    .filter(line -> settings.modes().isEmpty() || settings.modes().contains(line.allocation.mode())).toList();
            if (eligible.isEmpty()) {
                throw new IllegalArgumentException("Promotion has no eligible allocation");
            }
            var template = new Adjustment("PROMOTION", promotion.code(), promotion.version(), 0, json.writeValueAsString(settings));
            eligible.forEach(line -> line.apply(-percentage(line.net(), settings.rateBps()), template));
        }

        String version = active.stream().map(rule -> rule.id() + ":" + rule.version())
                .collect(java.util.stream.Collectors.joining(","));
        if (promotion != null) {
            version += "|" + promotion.code() + ":" + promotion.version();
        }
        var priced = lines.stream().map(line -> new Line(issue ? UUID.randomUUID() : null,
                line.allocation.allocationId(), line.segment, line.gross, line.fee, line.discount,
                Math.addExact(line.net(), line.fee), List.copyOf(line.adjustments))).toList();
        long total = priced.stream().mapToLong(Line::totalMinor).reduce(0, Math::addExact);
        return new Price(new Money(total, Money.Currency.MAD), promotion == null ? null : promotion.code(),
                QuoteStore.hash(version), basket.itinerary(),
                basket.categories() == null ? java.util.Map.of() : java.util.Map.copyOf(basket.categories()), priced);
    }

    private static boolean valid(RuleInput rule, Instant now) {
        return PricingRules.validAt(rule.active(), rule.validFrom(), rule.validTo(), now);
    }

    private static boolean matches(Condition condition, WorkingLine line, Basket basket, long gross, long modes) {
        if (condition == null) {
            return true;
        }
        return (condition.minDistinctModes() == null || modes >= condition.minDistinctModes())
                && (condition.minSubtotalMinor() == null || gross >= condition.minSubtotalMinor())
                && (condition.minTravelers() == null || basket.itinerary().travelerCount() >= condition.minTravelers())
                && (condition.mode() == null || condition.mode() == line.allocation.mode())
                && (condition.minRentalDays() == null || line.allocation.mode() == Offer.Mode.VOITURE
                    && line.allocation.quantity() >= condition.minRentalDays())
                && (condition.category() == null || line.allocation.mode() == Offer.Mode.VOITURE
                    && basket.categories() != null && condition.category().equals(basket.categories().get(line.allocation.allocationId())));
    }

    private static long sum(List<WorkingLine> lines, ToLongFunction<WorkingLine> value) {
        return lines.stream().mapToLong(value).reduce(0, Math::addExact);
    }

    private static long percentage(long amount, int bps) {
        return BigInteger.valueOf(amount).multiply(BigInteger.valueOf(bps)).add(BASIS.shiftRight(1))
                .divide(BASIS).longValueExact();
    }

    private static void distribute(List<WorkingLine> lines, long amount, boolean fee, Adjustment template) {
        if (amount == 0) {
            return;
        }
        ToLongFunction<WorkingLine> weights = fee ? line -> line.gross : WorkingLine::net;
        long sum = sum(lines, weights);
        record Share(int index, long floor, BigInteger remainder) {}
        var shares = new ArrayList<Share>();
        long residual = amount;
        for (int i = 0; i < lines.size(); i++) {
            var division = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(sum == 0 ? 1 : weights.applyAsLong(lines.get(i))))
                    .divideAndRemainder(BigInteger.valueOf(sum == 0 ? lines.size() : sum));
            long floor = division[0].longValueExact();
            residual -= floor;
            shares.add(new Share(i, floor, division[1]));
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed().thenComparingInt(Share::index));
        for (int i = 0; i < shares.size(); i++) {
            Share share = shares.get(i);
            long portion = share.floor() + (i < residual ? 1 : 0);
            lines.get(share.index()).apply(fee ? portion : -portion, template);
        }
    }

    private static final class WorkingLine {
        final int segment;
        final Allocation allocation;
        final long gross;
        final List<Adjustment> adjustments = new ArrayList<>();
        long fee;
        long discount;

        WorkingLine(int segment, Allocation allocation) {
            this.segment = segment;
            this.allocation = allocation;
            if (allocation.operator() == null || allocation.unitType() == null || allocation.unitPrice() == null
                    || allocation.quantity() < 1 || allocation.seatsOrPassengers() < 1) {
                throw new IllegalArgumentException("Invalid allocation");
            }
            Offer.Mode expectedMode = switch (allocation.operator()) {
                case RAIL -> Offer.Mode.TRAIN;
                case AIR -> Offer.Mode.AVION;
                case CAR -> Offer.Mode.VOITURE;
            };
            boolean car = expectedMode == Offer.Mode.VOITURE;
            if (allocation.mode() != expectedMode || allocation.unitType() != (car ? Offer.UnitType.DAY : Offer.UnitType.SEAT)
                    || (car ? allocation.startDate() == null || allocation.endDate() == null
                            || allocation.quantity() != ChronoUnit.DAYS.between(allocation.startDate(), allocation.endDate()) + 1
                        : allocation.quantity() != allocation.seatsOrPassengers())) {
                throw new IllegalArgumentException("Invalid pricing quantity or unit");
            }
            gross = Math.multiplyExact(allocation.unitPrice().amountMinor(), allocation.quantity());
        }

        long net() {
            return gross - discount;
        }

        void apply(long signed, Adjustment source) {
            if (signed == 0) {
                return;
            }
            if (signed > 0) {
                fee = Math.addExact(fee, signed);
            } else {
                discount = Math.addExact(discount, -signed);
            }
            adjustments.add(new Adjustment(source.sourceType(), source.sourceId(), source.sourceVersion(), signed, source.parameters()));
        }
    }
}
