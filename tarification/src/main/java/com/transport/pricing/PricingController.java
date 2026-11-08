package com.transport.pricing;

import com.transport.mobility.contracts.v1.QuoteContracts.*;
import com.transport.pricing.PricingRules.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
class PricingController {
    private final PricingService prices;
    private final QuoteStore store;

    PricingController(PricingService prices, QuoteStore store) {
        this.prices = prices;
        this.store = store;
    }

    @PostMapping("/internal/v1/pricing/preview")
    List<Price> preview(@RequestBody PreviewBatch request) {
        return prices.preview(request);
    }

    @PostMapping("/internal/v1/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    Quote issue(@RequestBody Issue request) {
        return prices.issue(request);
    }

    @GetMapping("/internal/v1/quotes/{id}")
    Quote quote(@PathVariable UUID id, @RequestParam String actorUserId) {
        return prices.get(id, actorUserId);
    }

    @PostMapping("/internal/v1/quotes/{id}/claim")
    Quote claim(@PathVariable UUID id, @RequestBody Claim request) {
        return prices.claim(id, request);
    }

    @GetMapping("/api/v1/admin/regles")
    List<Rule> rules() {
        return store.rules();
    }

    @PutMapping("/api/v1/admin/regles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void saveRule(@PathVariable UUID id, @RequestBody RuleInput rule) {
        store.saveRule(id, rule);
    }

    @DeleteMapping("/api/v1/admin/regles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRule(@PathVariable UUID id) {
        store.deactivate("regle", "id", id);
    }

    @GetMapping("/api/v1/admin/promotions")
    List<Promotion> promotions() {
        return store.promotions();
    }

    @PutMapping("/api/v1/admin/promotions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void savePromotion(@PathVariable String code, @RequestBody PromotionInput promotion) {
        store.savePromotion(PricingService.code(code), promotion);
    }

    @DeleteMapping("/api/v1/admin/promotions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePromotion(@PathVariable String code) {
        store.deactivate("promotion", "code", PricingService.code(code));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException invalid) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, invalid.getMessage());
    }

    @ExceptionHandler(ArithmeticException.class)
    ProblemDetail overflow() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Pricing amount out of range");
    }
}
