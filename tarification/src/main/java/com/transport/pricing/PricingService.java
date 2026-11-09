package com.transport.pricing;

import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.pricing.PricingRules.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
class PricingService {
    private final QuoteStore store;
    private final PricingEngine engine;
    private final Clock clock;
    private final Duration ttl;

    PricingService(QuoteStore store, JsonMapper json, Clock clock, @Value("${pricing.quote-ttl:PT5M}") Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Invalid quote TTL");
        }
        this.store = store;
        this.engine = new PricingEngine(json);
        this.clock = clock;
        this.ttl = ttl;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Price> preview(PreviewBatch batch) {
        if (batch.baskets() == null || batch.baskets().isEmpty() || batch.baskets().size() > 20) {
            throw new IllegalArgumentException("Expected 1 to 20 baskets");
        }
        var rules = store.rules();
        var promotions = store.promotions();
        var now = clock.instant();
        return batch.baskets().stream().map(basket -> engine.calculate(basket, rules, promo(basket, promotions), now, false)).toList();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Quote issue(Issue request) {
        String subject = subject(request.actorUserId());
        var basket = request.basket();
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var price = engine.calculate(basket, store.rules(), promo(basket, store.promotions()), now, true);
        var quote = new Quote(UUID.randomUUID(), null, Status.ISSUED, now, now.plus(ttl),
                store.itineraryHash(basket), 0, price);
        store.insert(subject, quote);
        return quote;
    }

    @Transactional(readOnly = true)
    public Quote get(UUID id, String actor) {
        var quote = find(id, actor, false);
        return expired(quote) ? state(quote, Status.EXPIRED) : quote;
    }

    @Transactional
    public Quote claim(UUID id, Claim request) {
        if (request.reservationId() == null) {
            throw new IllegalArgumentException("Missing reservation id");
        }
        var quote = find(id, request.actorUserId(), true);
        if (request.reservationId().equals(quote.reservationId())) {
            return quote;
        }
        if (quote.reservationId() != null || quote.status() != Status.ISSUED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUOTE_ALREADY_CLAIMED");
        }
        if (expired(quote)) {
            throw new ResponseStatusException(HttpStatus.GONE, "QUOTE_EXPIRED");
        }
        try {
            store.claim(id, request.reservationId());
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "RESERVATION_ALREADY_HAS_QUOTE");
        }
        return find(id, request.actorUserId(), false);
    }

    private Quote find(UUID id, String actor, boolean lock) {
        return store.find(id, subject(actor), lock)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND"));
    }

    private boolean expired(Quote quote) {
        return quote.status() == Status.ISSUED && !clock.instant().isBefore(quote.expiresAt());
    }

    private static Quote state(Quote quote, Status status) {
        return new Quote(quote.id(), quote.reservationId(), status, quote.createdAt(), quote.expiresAt(),
                quote.itineraryHash(), quote.version(), quote.price());
    }

    private static Promotion promo(Basket basket, List<Promotion> promotions) {
        if (basket == null || basket.promoCode() == null) {
            return null;
        }
        var code = code(basket.promoCode());
        return promotions.stream().filter(promotion -> code.equals(promotion.code())).findFirst().orElse(null);
    }

    static String code(String code) {
        var normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid promotion code");
        }
        return normalized;
    }

    static String subject(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Invalid subject");
        }
        return value;
    }
}
