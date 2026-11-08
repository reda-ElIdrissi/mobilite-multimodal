package com.transport.pricing;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.pricing.PricingRules.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class PricingEngineTest {
    static final Instant NOW = Instant.parse("2026-11-15T06:00:00Z");
    private final PricingEngine engine = new PricingEngine(JsonMapper.builder().build());

    @Test
    void computesRequiredDiscountOrderAndInclusiveCarDays() {
        var rules = List.of(rule(Type.PERCENT_DISCOUNT, new Condition(3, null, null, null, null, null), null, null, 500),
                rule(Type.FIXED_FEE_PER_ALLOCATION, null, Offer.Mode.VOITURE, 1000L, null));
        var promotion = new Promotion("DEMO", 1, new PromotionInput(1000, true, null, null, Set.of(Offer.Mode.VOITURE)));
        var price = engine.calculate(sample("DEMO"), rules, promotion, NOW, false);
        assertEquals(409500, price.total().amountMinor());
        assertEquals(List.of(800L, 18000L, 8700L), price.lines().stream().map(Line::discountMinor).toList());
        assertEquals(1000, price.lines().get(2).feeMinor());
        assertTrue(price.lines().stream().allMatch(line -> line.lineId() == null));
    }

    @Test
    void usesStableRemaindersAndHandlesZeroBaseAndFullDiscount() {
        var fee = rule(Type.FIXED_FEE_PER_BOOKING, null, null, 2L, null);
        var full = rule(Type.PERCENT_DISCOUNT, null, null, null, 10000);
        var noDiscount = rule(Type.PERCENT_DISCOUNT, null, null, null, 0);
        var free = basket(null, allocation(Offer.Operator.RAIL, 0), allocation(Offer.Operator.AIR, 0),
                allocation(Offer.Operator.CAR, 0));
        assertEquals(List.of(1L, 1L, 0L), engine.calculate(free, List.of(fee, full, noDiscount), null, NOW, false)
                .lines().stream().map(Line::totalMinor).toList());
        var oneEach = basket(null, allocation(Offer.Operator.RAIL, 1), allocation(Offer.Operator.AIR, 1),
                allocation(Offer.Operator.CAR, 1));
        assertEquals(List.of(1L, 1L, 0L), engine.calculate(oneEach, List.of(fee, full), null, NOW, false)
                .lines().stream().map(Line::totalMinor).toList());
        assertThrows(ArithmeticException.class, () -> engine.calculate(
                basket(null, allocation(Offer.Operator.RAIL, Long.MAX_VALUE)), List.of(), null, NOW, false));
    }

    @Test
    void rejectsInvalidRulesExpiredPromotionsAndIneligibleModes() {
        assertThrows(IllegalArgumentException.class, () -> rule(Type.PERCENT_DISCOUNT, null, null, null, 10001));
        assertThrows(IllegalArgumentException.class, () -> rule(Type.FIXED_DISCOUNT, null, null, -1L, null));
        var expired = new Promotion("DEMO", 1, new PromotionInput(1000, true, NOW.minusSeconds(1), NOW, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(sample("DEMO"), List.of(), expired, NOW, false));
        var carOnly = new Promotion("DEMO", 1, new PromotionInput(1000, true, null, null, Set.of(Offer.Mode.VOITURE)));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(
                basket("DEMO", allocation(Offer.Operator.RAIL, 1)), List.of(), carOnly, NOW, false));
    }

    static Basket sample(String code) {
        return basket(code, allocation(Offer.Operator.RAIL, 8000), allocation(Offer.Operator.AIR, 180000),
                allocation(Offer.Operator.CAR, 30000));
    }

    private static Basket basket(String code, Allocation... allocations) {
        return new Basket(new Itinerary(2, List.of("p1", "p2"),
                List.of(new Segment(0, null, null, null, null, List.of(allocations))), List.of()), code, Map.of());
    }

    private static Allocation allocation(Offer.Operator operator, long rate) {
        var mode = switch (operator) {
            case RAIL -> Offer.Mode.TRAIN;
            case AIR -> Offer.Mode.AVION;
            case CAR -> Offer.Mode.VOITURE;
        };
        boolean car = operator == Offer.Operator.CAR;
        var date = LocalDate.of(2026, 11, 15);
        return new Allocation(UUID.randomUUID(), operator, operator.name(), mode, 2, 2,
                car ? Offer.UnitType.DAY : Offer.UnitType.SEAT, null, null, car ? date : null,
                car ? date.plusDays(1) : null, List.of("p1", "p2"), car ? "p1" : null,
                new Money(rate, Money.Currency.MAD), car ? 0L : null);
    }

    private static Rule rule(Type type, Condition condition, Offer.Mode mode, Long amount, Integer bps) {
        return new Rule(UUID.randomUUID(), 1, new RuleInput(type, condition, mode, amount, bps, 0, true, null, null));
    }
}
