package com.transport.pricing;

import com.transport.mobility.contracts.v1.Offer.Mode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

final class PricingRules {
    private PricingRules() {}

    enum Type { FIXED_FEE_PER_ALLOCATION, FIXED_FEE_PER_BOOKING, PERCENT_DISCOUNT, FIXED_DISCOUNT }

    record Condition(Integer minDistinctModes, Long minSubtotalMinor, Integer minTravelers,
                     Integer minRentalDays, String category, Mode mode) {
        Condition {
            if (minDistinctModes != null && (minDistinctModes < 1 || minDistinctModes > 3)
                    || minSubtotalMinor != null && minSubtotalMinor < 0
                    || minTravelers != null && minTravelers < 1
                    || minRentalDays != null && minRentalDays < 1
                    || category != null && category.isBlank()) {
                throw new IllegalArgumentException("Invalid rule condition");
            }
        }
    }

    record RuleInput(Type type, Condition condition, Mode mode, Long amountMinor, Integer rateBps,
                     int priority, boolean active, Instant validFrom, Instant validTo) {
        RuleInput {
            if (type == null || (type == Type.PERCENT_DISCOUNT
                    ? amountMinor != null || !validRate(rateBps)
                    : rateBps != null || amountMinor == null || amountMinor < 0)) {
                throw new IllegalArgumentException("Invalid rule amount or rate");
            }
            dates(validFrom, validTo);
        }
    }

    record PromotionInput(Integer rateBps, boolean active, Instant validFrom, Instant validTo, Set<Mode> modes) {
        PromotionInput {
            if (!validRate(rateBps)) {
                throw new IllegalArgumentException("Invalid promotion rate");
            }
            dates(validFrom, validTo);
            modes = modes == null ? Set.of() : Set.copyOf(modes);
        }
    }

    record Rule(UUID id, int version, RuleInput settings) {}
    record Promotion(String code, int version, PromotionInput settings) {}

    static boolean validAt(boolean active, Instant start, Instant end, Instant now) {
        return active && (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }

    private static boolean validRate(Integer value) {
        return value != null && value >= 0 && value <= 10000;
    }

    private static void dates(Instant start, Instant end) {
        if (start != null && end != null && !start.isBefore(end)) {
            throw new IllegalArgumentException("Invalid validity interval");
        }
    }
}
