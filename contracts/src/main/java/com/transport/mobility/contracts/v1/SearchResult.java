package com.transport.mobility.contracts.v1;

import java.util.List;
import java.util.Map;

public record SearchResult(List<Offer> offers, boolean partial, boolean truncated,
                           Map<Offer.Operator, OperatorError> errors, List<Candidate> itineraries,
                           List<String> assumptions, String pricingError) {
    public SearchResult(List<Offer> offers, boolean partial, boolean truncated, Map<Offer.Operator, OperatorError> errors) {
        this(offers, partial, truncated, errors, List.of(), List.of(), null);
    }

    public record Candidate(QuoteContracts.Selection selection, Money estimatedTotal, List<ObservedAllocation> availability) {
        public Candidate withEstimate(Money total) {
            return new Candidate(selection, total, availability);
        }
    }

    public record ObservedAllocation(int segmentIndex, Offer.Operator operator, String offerId, int capacity,
                                     Offer.AvailabilityKind availabilityKind, String category) {}
}
